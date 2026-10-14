package app.tcg.app.data.sync

import app.tcg.core.domain.model.CardVariant
import app.tcg.app.auth.AuthSession
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

enum class SyncErrorKind {
    UNAUTHORIZED,
    DEVICE_REVOKED,
    UNAVAILABLE,
    PRE_DISPATCH,
    INVALID_RESPONSE,
    COMMIT_UNCERTAIN,
}

class SupabaseSyncException(
    val kind: SyncErrorKind,
    message: String,
) : Exception(message)

data class LinkPreview(
    val planId: String,
    val digest: String,
    val expiresAt: String,
    val remoteHighWater: Long,
    val localOnly: List<String>,
    val cloudOnly: List<String>,
    val identical: List<String>,
    val conflicting: List<LinkConflict>,
    val duplicateConfigurations: List<LinkDuplicate>,
    val remoteItems: List<RemoteLinkItem>,
)

data class LinkConflict(val itemId: String, val reason: String)

data class LinkDuplicate(val localItemId: String, val remoteItemId: String)

data class RemoteLinkItem(val item: SyncItem, val revision: Long)

sealed interface LinkCommitResult {
    data class Linked(val highWater: Long, val outcomes: List<PushOutcome>) : LinkCommitResult
    data class Partial(val highWater: Long, val outcomes: List<PushOutcome>) : LinkCommitResult
    data object StalePreview : LinkCommitResult
    data object DigestMismatch : LinkCommitResult
    data object Expired : LinkCommitResult
    data object Consumed : LinkCommitResult
    data object NotFound : LinkCommitResult
    data object PayloadMismatch : LinkCommitResult
}

/**
 * The first-link RPC surface the coordinator needs. Kept separate from [SyncTransport]
 * so the link flow can be faked without faking push/pull, and so the local digest that
 * guards a stale preview is computed by one shared function on both sides.
 */
interface SyncLinkTransport {
    suspend fun prepareLink(items: List<SyncItem>, expectedIdentity: String): LinkPreview
    suspend fun commitLink(
        plan: LinkPreview,
        deviceId: String,
        operations: List<SyncOperation>,
        expectedIdentity: String,
    ): LinkCommitResult
}

private const val MAX_LINK_ITEMS = 5_000

/** Canonical, order-independent digest of a local snapshot; the server echoes it back to detect a changed collection. */
fun syncSnapshotDigest(items: List<SyncItem>): String {
    if (items.size > MAX_LINK_ITEMS) throw SupabaseSyncException(SyncErrorKind.UNAVAILABLE, "A prévia aceita até $MAX_LINK_ITEMS posses")
    val sorted = items.sortedBy { it.id }.map { encodeSyncItem(it) }
    val canonical = canonicalizeJson(JsonArray(sorted))
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(canonical.toString().toByteArray(Charsets.UTF_8))
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun encodeSyncItem(item: SyncItem): JsonObject = JsonObject(
    buildMap {
        put("id", JsonPrimitive(item.id))
        put("cardId", JsonPrimitive(item.cardId))
        put("quantity", JsonPrimitive(item.quantity))
        put("variant", JsonPrimitive(item.variant.name))
        put("condition", JsonPrimitive(item.condition.name))
        put(
            "pricePaid",
            item.pricePaid?.let { money ->
                JsonObject(
                    mapOf(
                        "amount" to JsonPrimitive(toMinorUnitsJson(money.amount)),
                        "currency" to JsonPrimitive(money.currency.name),
                    ),
                )
            } ?: JsonNull,
        )
        put("addedAt", JsonPrimitive(item.addedAt))
    },
)

private fun canonicalizeJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries
            .sortedBy { (key, _) -> key }
            .associate { (key, value) -> key to canonicalizeJson(value) },
    )
    is JsonArray -> JsonArray(element.map { value -> canonicalizeJson(value) })
    else -> element
}

private fun toMinorUnitsJson(amount: Double): Long {
    val scaled = amount * 100
    val rounded = Math.round(scaled)
    if (abs(scaled - rounded) > 1e-7) {
        throw SupabaseSyncException(SyncErrorKind.UNAVAILABLE, "Preço pago precisa ter no máximo duas casas decimais")
    }
    return rounded
}

/**
 * Supabase RPC boundary for the operation-log sync contract. Mirrors the web
 * transport exactly: push/pull go through the two SECURITY DEFINER functions, the
 * client signs each operation so the server can detect opId reuse, and the cursor
 * advances only on an acknowledged pull page.
 */
class SupabaseSyncTransport(
    private val baseUrl: String,
    private val apiKey: String,
    private val httpClient: HttpClient,
    private val sessionProvider: () -> AuthSession?,
) : SyncTransport, SyncLinkTransport {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun push(identity: String, operations: List<SyncOperation>): List<PushOutcome> {
        if (operations.isEmpty()) return emptyList()

        val deviceId = operations.first().deviceId
        val payload = JsonObject(
            mapOf(
                "p_device_id" to JsonPrimitive(deviceId),
                "p_operations" to JsonArray(operations.map { encodeOperation(it, deviceId) }),
            ),
        )

        val response = call("push_collection_operations", payload, expectedIdentity = identity)
        val status = response.string("status")
        if (status == "device-revoked") {
            throw SupabaseSyncException(SyncErrorKind.DEVICE_REVOKED, "Este dispositivo foi desconectado da conta")
        }
        if (status != "ok") {
            throw SupabaseSyncException(SyncErrorKind.UNAVAILABLE, "Push recusado pelo servidor: ${status ?: "resposta inválida"}")
        }

        val results = response["results"] as? JsonArray ?: JsonArray(emptyList())
        return results.map { entry -> mapPushOutcome(entry as? JsonObject ?: throw unavailable("Resultado de push inválido")) }
    }

    override suspend fun listPushConflicts(expectedIdentity: String): List<PushConflict> {
        val response = callElement("read_collection_push_conflicts", JsonObject(emptyMap()), expectedIdentity)
        val rows = response as? JsonArray ?: throw invalidResponse("A lista de conflitos remotos é inválida")
        return rows.map { parsePushConflict(it as? JsonObject ?: throw invalidResponse("Conflito remoto inválido")) }
    }

    override suspend fun resolvePushConflict(
        conflictId: String,
        choice: PushConflictChoice,
        deviceId: String,
        expectedIdentity: String,
    ): PushResolutionResponse {
        if (!isUuid(conflictId) || !isDeviceId(deviceId)) throw SupabaseSyncException(SyncErrorKind.PRE_DISPATCH, "Conflito ou dispositivo inválido")
        val response = call(
            "resolve_collection_push_conflict",
            JsonObject(mapOf(
                "p_conflict_id" to JsonPrimitive(conflictId),
                "p_choice" to JsonPrimitive(choice.wireValue),
                "p_device_id" to JsonPrimitive(deviceId),
            )),
            expectedIdentity,
        )
        return parsePushResolution(response, requireReceipt = true)
    }

    override suspend fun acknowledgePushConflictResolution(
        conflictId: String,
        resolutionId: String,
        expectedIdentity: String,
    ): PushResolutionResponse {
        if (!isUuid(conflictId) || !isUuid(resolutionId)) throw SupabaseSyncException(SyncErrorKind.PRE_DISPATCH, "Recibo de resolução inválido")
        val response = call(
            "ack_collection_push_conflict_resolution",
            JsonObject(mapOf(
                "p_conflict_id" to JsonPrimitive(conflictId),
                "p_resolution_id" to JsonPrimitive(resolutionId),
            )),
            expectedIdentity,
        )
        return parsePushResolution(response, requireReceipt = false)
    }

    override suspend fun pull(identity: String, afterSequence: Long): PullPage {
        val payload = JsonObject(
            mapOf(
                "p_after_sequence" to JsonPrimitive(afterSequence),
                "p_page_limit" to JsonPrimitive(PAGE_LIMIT),
            ),
        )
        val response = call("pull_collection_operations", payload, expectedIdentity = identity)

        val operations = (response["operations"] as? JsonArray ?: JsonArray(emptyList()))
            .map { entry -> parseSequenced(entry as? JsonObject ?: throw unavailable("Operação remota inválida")) }
            .sortedBy { it.sequence }

        val highWaterMark = response.jsonPrimitiveOrNull("highWater") ?: afterSequence
        return PullPage(operations, highWaterMark)
    }

    /**
     * First-link preview. Read-only: it registers a link plan bound to this owner, the
     * caller's local digest and the server's own high-water sequence, and returns the
     * exact preview rows so the UI can show what would be sent, added, or blocked.
     */
    override suspend fun prepareLink(items: List<SyncItem>, expectedIdentity: String): LinkPreview {
        if (sessionProvider()?.userId != expectedIdentity) {
            throw SupabaseSyncException(SyncErrorKind.UNAUTHORIZED, "Sem sessão de conta para vincular")
        }
        val localItems = JsonArray(items.sortedBy { it.id }.map { encodeItem(it) })
        val digest = syncSnapshotDigest(items)

        val response = call(
            "prepare_collection_link",
            JsonObject(
                mapOf(
                    "p_local_digest" to JsonPrimitive(digest),
                    "p_local_items" to localItems,
                ),
            ),
            expectedIdentity = expectedIdentity,
        )
        val preview = response["preview"] as? JsonObject ?: throw unavailable("Prévia de vínculo ausente")
        val planId = response.string("planId") ?: throw unavailable("Prévia sem plano")
        val expiresAt = response.string("expiresAt") ?: throw unavailable("Prévia sem expiração")
        val highWater = response.jsonPrimitiveOrNull("remoteHighWater")
            ?: throw unavailable("Prévia sem sequência remota")

        val snapshot = call(
            "read_collection_link_snapshot",
            JsonObject(mapOf("p_expected_high_water" to JsonPrimitive(highWater))),
            expectedIdentity = expectedIdentity,
        )
        val snapshotHighWater = snapshot.jsonPrimitiveOrNull("highWater")
        val items = snapshot["items"] as? JsonArray ?: throw unavailable("Instantâneo remoto ausente")
        if (snapshotHighWater != highWater) {
            throw unavailable("A coleção remota mudou durante a prévia. Gere uma nova prévia.")
        }

        return LinkPreview(
            planId = planId,
            digest = digest,
            expiresAt = expiresAt,
            remoteHighWater = highWater,
            localOnly = preview.stringList("localOnly"),
            cloudOnly = preview.stringList("cloudOnly"),
            identical = preview.stringList("identical"),
            conflicting = (preview["conflicting"] as? JsonArray).orEmpty().map { element ->
                val row = element as? JsonObject ?: throw unavailable("Conflito remoto inválido")
                LinkConflict(
                    itemId = row.string("itemId") ?: throw unavailable("Conflito sem item"),
                    reason = row.string("reason") ?: throw unavailable("Conflito sem motivo"),
                )
            },
            duplicateConfigurations = (preview["duplicateConfigurations"] as? JsonArray).orEmpty().map { element ->
                val row = element as? JsonObject ?: throw unavailable("Duplicata remota inválida")
                LinkDuplicate(
                    localItemId = row.string("localItemId") ?: throw unavailable("Duplicata sem item local"),
                    remoteItemId = row.string("remoteItemId") ?: throw unavailable("Duplicata sem item remoto"),
                )
            },
            remoteItems = items.map { element ->
                val row = element as? JsonObject ?: throw unavailable("Item remoto inválido")
                val revision = row.jsonPrimitiveOrNull("revision")
                    ?: throw unavailable("Item remoto sem revisão")
                RemoteLinkItem(item = parseItem(row), revision = revision)
            },
        )
    }

    /**
     * Confirms a preview. Every refusal the server can return is mapped to a typed value
     * instead of an exception, so the UI can explain it without mutating local data.
     */
    override suspend fun commitLink(
        plan: LinkPreview,
        deviceId: String,
        operations: List<SyncOperation>,
        expectedIdentity: String,
    ): LinkCommitResult {
        if (sessionProvider()?.userId != expectedIdentity) {
            throw SupabaseSyncException(SyncErrorKind.UNAUTHORIZED, "Sem sessão de conta para vincular")
        }
        if (deviceId.length < 8 || operations.any { it.deviceId != deviceId }) {
            throw SupabaseSyncException(SyncErrorKind.PRE_DISPATCH, "Nada foi enviado. Dispositivo inválido para confirmar o vínculo")
        }
        val encodedOperations = try {
            JsonArray(operations.map { encodeOperation(it, deviceId) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw SupabaseSyncException(SyncErrorKind.PRE_DISPATCH, "Nada foi enviado. ${error.message ?: "Operação local inválida"}")
        }
        val response = try {
            call(
                "commit_collection_link",
                JsonObject(
                    mapOf(
                        "p_plan_id" to JsonPrimitive(plan.planId),
                        "p_digest" to JsonPrimitive(plan.digest),
                        "p_device_id" to JsonPrimitive(deviceId),
                        "p_operations" to encodedOperations,
                    ),
                ),
                expectedIdentity = expectedIdentity,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SupabaseSyncException) {
            if (error.kind == SyncErrorKind.UNAUTHORIZED || error.kind == SyncErrorKind.INVALID_RESPONSE) throw error
            throw SupabaseSyncException(
                SyncErrorKind.COMMIT_UNCERTAIN,
                "A resposta da nuvem não chegou e ela pode já ter recebido itens da prévia. Gere uma nova prévia e confira qualquer conflito.",
            )
        }
        return when (val status = response.string("status")) {
            "linked", "partial" -> {
                val push = response["push"] as? JsonObject ?: throw unavailable("Vínculo sem confirmação de push")
                val pushStatus = push.string("status")
                val highWater = push.jsonPrimitiveOrNull("highWater")
                if ((pushStatus != "ok" && !(status == "partial" && pushStatus == "device-revoked")) || highWater == null) {
                    throw invalidResponse("O servidor retornou um resultado de vínculo inválido")
                }
                val results = push["results"] as? JsonArray ?: throw invalidResponse("Resultado de operações ausente")
                if (pushStatus != "device-revoked" && results.size != operations.size) {
                    throw invalidResponse("O resultado não corresponde às operações enviadas")
                }
                val outcomes = try {
                    results.map { mapPushOutcome(it as? JsonObject ?: throw invalidResponse("Resultado de push inválido")) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (error is SupabaseSyncException && error.kind == SyncErrorKind.INVALID_RESPONSE) throw error
                    throw invalidResponse("O servidor retornou resultados de operação inválidos")
                }
                if (status == "linked" && outcomes.any { it is PushOutcome.Conflict }) {
                    throw invalidResponse("O servidor marcou como concluído um vínculo com operações recusadas")
                }
                if (status == "linked") LinkCommitResult.Linked(highWater, outcomes) else LinkCommitResult.Partial(highWater, outcomes)
            }
            "stale-preview" -> LinkCommitResult.StalePreview
            "digest-mismatch" -> LinkCommitResult.DigestMismatch
            "expired" -> LinkCommitResult.Expired
            "consumed" -> LinkCommitResult.Consumed
            "not-found" -> LinkCommitResult.NotFound
            "payload-mismatch" -> LinkCommitResult.PayloadMismatch
            else -> throw invalidResponse("O servidor retornou um status de vínculo inválido: ${status ?: "desconhecido"}")
        }
    }

    private suspend fun call(function: String, payload: JsonObject, expectedIdentity: String? = null): JsonObject =
        callElement(function, payload, expectedIdentity) as? JsonObject ?: throw invalidResponse("Resposta inválida do servidor")

    private suspend fun callElement(function: String, payload: JsonObject, expectedIdentity: String? = null): JsonElement {
        val session = sessionProvider()
        if (expectedIdentity != null && session?.userId != expectedIdentity) {
            throw SupabaseSyncException(SyncErrorKind.UNAUTHORIZED, "A conta mudou antes da sincronização")
        }
        val response = try {
            httpClient.post("$baseUrl/rest/v1/rpc/$function") {
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
                header(HttpHeaders.Authorization, session?.accessToken ?: apiKey)
                header("apikey", apiKey)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw SupabaseSyncException(SyncErrorKind.UNAVAILABLE, error.message ?: "Falha de rede na sincronização")
        }

        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            throw SupabaseSyncException(SyncErrorKind.UNAUTHORIZED, "Sem permissão de sincronização")
        }
        if (!response.status.isSuccess()) {
            throw SupabaseSyncException(SyncErrorKind.UNAVAILABLE, "Servidor de sync respondeu ${response.status.value}")
        }

        val body = try {
            json.parseToJsonElement(response.bodyAsText())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw SupabaseSyncException(SyncErrorKind.INVALID_RESPONSE, "Resposta inválida do servidor")
        }

        val message = (body as? JsonObject)?.string("message").orEmpty()
        if (UNAUTHORIZED_PATTERN.containsMatchIn(message)) {
            throw SupabaseSyncException(SyncErrorKind.UNAUTHORIZED, message)
        }
        return body
    }

    private fun mapPushOutcome(row: JsonObject): PushOutcome {
        val status = row.string("status")
        val reason = row.string("reason")
        val opId = row.string("opId")
        val sequence = row.jsonPrimitiveOrNull("revision")
        return when (status) {
            "applied" -> PushOutcome.Applied(sequence ?: throw unavailable("Applied sem revisão"), opId)
            "already-applied" -> PushOutcome.AlreadyApplied(sequence ?: throw unavailable("AlreadyApplied sem revisão"), opId)
            "conflict" -> PushOutcome.Conflict(
                reason = reason ?: throw invalidResponse("Conflito sem motivo"),
                opId = opId,
                conflictId = row.string("conflictId")?.takeIf(::isUuid) ?: throw invalidResponse("Conflito sem id válido"),
                highWater = row.jsonPrimitiveOrNull("highWater") ?: throw invalidResponse("Conflito sem high-water"),
                rejectedOperation = parseRejectedOperation(row["rejectedOperation"]),
                cloudItem = parsePushCloudItem(row["cloudItem"]),
                conflictingItem = parsePushCloudItem(row["conflictingItem"]),
            )
            "rejected" -> PushOutcome.Rejected(
                reason ?: throw invalidResponse("Rejection sem motivo"),
                opId,
                row.string("conflictId")?.takeIf(::isUuid) ?: throw invalidResponse("Rejection sem conflito persistido"),
            )
            else -> throw invalidResponse("Status de push desconhecido")
        }
    }

    private fun parsePushConflict(row: JsonObject): PushConflict {
        val id = row.string("id")?.takeIf(::isUuid) ?: throw invalidResponse("Conflito sem id válido")
        val itemId = row.string("itemId")?.takeIf(::isDeviceId) ?: throw invalidResponse("Conflito sem item válido")
        val opId = when (val value = row["opId"]) {
            null, JsonNull -> null
            else -> value.jsonPrimitive.contentOrNull?.takeIf(::isUuid) ?: throw invalidResponse("Conflito com opId inválido")
        }
        val deviceId = row.string("deviceId")?.takeIf(::isDeviceId) ?: throw invalidResponse("Conflito sem dispositivo válido")
        val reason = row.string("reason")?.takeIf(String::isNotBlank) ?: throw invalidResponse("Conflito sem motivo")
        val highWater = row.jsonPrimitiveOrNull("highWater")?.takeIf { it >= 0 } ?: throw invalidResponse("Conflito sem high-water válido")
        val operation = try {
            parseRejectedOperation(row["rejectedOperation"])
        } catch (error: SupabaseSyncException) {
            if (reason != "invalid-payload") throw error
            null
        }
        val resolution = when (val receipt = row["resolution"]) {
            null, JsonNull -> null
            else -> parsePushReceipt(receipt as? JsonObject ?: throw invalidResponse("Recibo remoto inválido"))
        }
        return PushConflict(
            id, opId, itemId, reason, deviceId, operation,
            parsePushCloudItem(row["cloudItem"]), parsePushCloudItem(row["conflictingItem"]), highWater, resolution,
        )
    }

    private fun parsePushCloudItem(element: JsonElement?): PushCloudItem? {
        if (element == null || element is JsonNull) return null
        val row = element as? JsonObject ?: throw invalidResponse("Snapshot de conflito inválido")
        val id = row.string("itemId")?.takeIf(::isDeviceId) ?: throw invalidResponse("Snapshot sem item válido")
        val revision = row.jsonPrimitiveOrNull("revision")?.takeIf { it >= 0 } ?: throw invalidResponse("Snapshot sem revisão válida")
        return when (row["deleted"]?.jsonPrimitive?.contentOrNull) {
            "true" -> PushCloudItem.Deleted(id, revision)
            "false" -> {
                if (revision < 1) throw invalidResponse("Snapshot sem revisão válida")
                val item = parseItem(JsonObject(row + ("id" to JsonPrimitive(id))))
                PushCloudItem.Present(item, revision)
            }
            else -> throw invalidResponse("Snapshot sem estado válido")
        }
    }

    private fun parseRejectedOperation(element: JsonElement?): SyncOperation {
        val row = element as? JsonObject ?: throw invalidResponse("Operação rejeitada inválida")
        val opId = row.string("opId")?.takeIf(::isUuid) ?: throw invalidResponse("Operação sem id válido")
        val deviceId = row.string("deviceId")?.takeIf(::isDeviceId) ?: throw invalidResponse("Operação sem dispositivo válido")
        return when (row.string("kind")) {
            "create" -> SyncOperation.Create(opId, deviceId, parseItem(row["item"]))
            "replace" -> SyncOperation.Replace(opId, deviceId, row.jsonPrimitiveOrNull("baseRevision")?.takeIf { it >= 0 } ?: throw invalidResponse("Operação sem revisão base"), parseItem(row["item"]))
            "delete" -> SyncOperation.Delete(opId, deviceId, row.jsonPrimitiveOrNull("baseRevision")?.takeIf { it >= 0 } ?: throw invalidResponse("Operação sem revisão base"), row.string("itemId")?.takeIf(::isDeviceId) ?: throw invalidResponse("Operação sem item válido"))
            else -> throw invalidResponse("Tipo de operação rejeitada inválido")
        }
    }

    private fun parsePushReceipt(row: JsonObject): PushResolutionReceipt {
        val id = row.string("id")?.takeIf(::isUuid) ?: throw invalidResponse("Recibo sem id válido")
        val choice = parseChoice(row.string("choice")) ?: throw invalidResponse("Recibo sem escolha válida")
        val result = row["result"] as? JsonObject ?: throw invalidResponse("Recibo sem resultado")
        return PushResolutionReceipt(id, choice, result)
    }

    private fun parsePushResolution(row: JsonObject, requireReceipt: Boolean): PushResolutionResponse {
        val status = row.string("status")?.takeIf { it in setOf("resolved", "already-resolved", "stale-conflict", "not-found", "device-revoked", "invalid-resolution") }
            ?: throw invalidResponse("Status de resolução inválido")
        val conflictId = row.string("conflictId")?.takeIf(::isUuid) ?: throw invalidResponse("Resposta de resolução sem conflito válido")
        val choice = parseChoice(row.string("choice"))
        val resolutionId = row.string("resolutionId")?.takeIf(::isUuid)
        val result = row["result"] as? JsonObject
        if (requireReceipt && status in setOf("resolved", "already-resolved") && (choice == null || resolutionId == null || result == null)) throw invalidResponse("Recibo de resolução incompleto")
        return PushResolutionResponse(status, conflictId, choice, resolutionId, row.string("reason"), result)
    }

    private fun parseChoice(value: String?): PushConflictChoice? = when (value) {
        "keep-local" -> PushConflictChoice.KEEP_LOCAL
        "keep-cloud" -> PushConflictChoice.KEEP_CLOUD
        else -> null
    }

    private fun parseSequenced(row: JsonObject): SequencedOperation {
        val sequence = row.jsonPrimitiveOrNull("serverSequence")
            ?: throw unavailable("Operação remota sem sequência")
        val opId = row.string("opId") ?: throw unavailable("Operação remota sem opId")
        val deviceId = row.string("deviceId") ?: "unknown-device"
        val kind = row.string("kind")
        val baseRevision = row.jsonPrimitiveOrNull("baseRevision")
        return SequencedOperation(
            sequence = sequence,
            operation = when (kind) {
                "create" -> SyncOperation.Create(opId, deviceId, parseItem(row["item"]))
                "replace" -> SyncOperation.Replace(
                    opId,
                    deviceId,
                    baseRevision ?: throw unavailable("Replace sem revisão base"),
                    parseItem(row["item"]),
                )
                "delete" -> SyncOperation.Delete(
                    opId,
                    deviceId,
                    baseRevision ?: throw unavailable("Delete sem revisão base"),
                    row.string("itemId") ?: throw unavailable("Delete sem item"),
                )
                else -> throw unavailable("Operação remota desconhecida: $kind")
            },
        )
    }

    private fun parseItem(element: JsonElement?): SyncItem {
        val item = element as? JsonObject ?: throw unavailable("Item remoto ausente")
        val id = item.string("id") ?: throw unavailable("Item remoto sem id")
        val cardId = item.string("cardId") ?: throw unavailable("Item remoto sem carta")
        val variant = enumValueOrNull<CardVariant>(item.string("variant")) ?: throw unavailable("Variante remota desconhecida")
        val condition = enumValueOrNull<Condition>(item.string("condition")) ?: throw unavailable("Condição remota desconhecida")
        val quantity = item.jsonPrimitiveOrNull("quantity")
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) throw unavailable("Quantidade remota inválida")
        val addedAt = item.jsonPrimitiveOrNull("addedAt") ?: throw unavailable("Data remota inválida")
        return SyncItem(
            id = id,
            cardId = cardId,
            quantity = quantity.toInt(),
            variant = variant,
            condition = condition,
            pricePaid = parseRemotePrice(item["pricePaid"]),
            addedAt = addedAt,
        )
    }

    private fun parseRemotePrice(element: JsonElement?): Money? {
        if (element == null || element is JsonNull) return null
        val price = element as? JsonObject ?: throw unavailable("Preço remoto inválido")
        val amount = price.jsonPrimitiveOrNull("amount")
        if (amount == null || amount < 0) throw unavailable("Preço remoto inválido")
        val currency = enumValueOrNull<Currency>(price.string("currency")) ?: throw unavailable("Moeda remota desconhecida")
        return Money(amount = amount.toDouble() / MINOR_UNITS_PER_MAJOR, currency = currency)
    }
    private fun encodeOperation(operation: SyncOperation, deviceId: String): JsonObject {
        val base = when (operation) {
            is SyncOperation.Create -> JsonObject(
                mapOf(
                    "kind" to JsonPrimitive("create"),
                    "opId" to JsonPrimitive(operation.opId),
                    "item" to encodeItem(operation.item),
                ),
            )
            is SyncOperation.Replace -> JsonObject(
                mapOf(
                    "kind" to JsonPrimitive("replace"),
                    "opId" to JsonPrimitive(operation.opId),
                    "item" to encodeItem(operation.item),
                    "baseRevision" to JsonPrimitive(operation.baseRevision),
                ),
            )
            is SyncOperation.Delete -> JsonObject(
                mapOf(
                    "kind" to JsonPrimitive("delete"),
                    "opId" to JsonPrimitive(operation.opId),
                    "itemId" to JsonPrimitive(operation.itemId),
                    "baseRevision" to JsonPrimitive(operation.baseRevision),
                ),
            )
        }
        return JsonObject(
            base +
                mapOf(
                    "deviceId" to JsonPrimitive(deviceId),
                    "signature" to JsonPrimitive(signatureOf(base)),
                ),
        )
    }

    private fun encodeItem(item: SyncItem): JsonObject = JsonObject(
        buildMap {
            put("id", JsonPrimitive(item.id))
            put("cardId", JsonPrimitive(item.cardId))
            put("quantity", JsonPrimitive(item.quantity))
            put("variant", JsonPrimitive(item.variant.name))
            put("condition", JsonPrimitive(item.condition.name))
            put(
                "pricePaid",
                item.pricePaid?.let { money ->
                    JsonObject(
                        mapOf(
                            "amount" to JsonPrimitive(toMinorUnits(money.amount)),
                            "currency" to JsonPrimitive(money.currency.name),
                        ),
                    )
                } ?: JsonNull,
            )
            put("addedAt", JsonPrimitive(item.addedAt))
        },
    )

    /** Deterministic content signature so the server can detect opId reuse with different content. */
    private fun signatureOf(operation: JsonObject): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(canonicalize(operation).toString().toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { (key, _) -> key }
                .associate { (key, value) -> key to canonicalize(value) },
        )
        is JsonArray -> JsonArray(element.map { value -> canonicalize(value) })
        else -> element
    }

    private fun unavailable(message: String) = SupabaseSyncException(SyncErrorKind.UNAVAILABLE, message)

    private fun invalidResponse(message: String) = SupabaseSyncException(SyncErrorKind.INVALID_RESPONSE, message)

    private fun toMinorUnits(amount: Double): Long = try {
        BigDecimal.valueOf(amount)
            .movePointRight(MINOR_UNIT_SCALE)
            .setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact()
    } catch (_: ArithmeticException) {
        throw unavailable("Preço pago precisa ter no máximo duas casas decimais")
    }

    private fun JsonObject.string(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive is JsonNull) null else primitive.contentOrNull
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val array = this[key] as? JsonArray ?: throw unavailable("Prévia sem a lista $key")
        return array.map { element ->
            val primitive = element as? JsonPrimitive ?: throw unavailable("Prévia com item inválido")
            if (primitive is JsonNull) throw unavailable("Prévia com item inválido") else primitive.content
        }
    }

    private fun JsonObject.jsonPrimitiveOrNull(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive is JsonNull) null else primitive.longOrNull
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? =
        name?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

    private companion object {
        const val PAGE_LIMIT = 500
        const val MAX_QUANTITY = 100_000L
        const val MINOR_UNIT_SCALE = 2
        const val MINOR_UNITS_PER_MAJOR = 100.0
        val UNAUTHORIZED_PATTERN = Regex("not authenticated|permission denied|jwt", RegexOption.IGNORE_CASE)
        val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        val ITEM_ID_PATTERN = Regex("^[a-zA-Z0-9-]{8,80}$")
    }

    private fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

    private fun isDeviceId(value: String): Boolean = ITEM_ID_PATTERN.matches(value)
}
