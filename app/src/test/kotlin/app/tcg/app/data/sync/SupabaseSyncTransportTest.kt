package app.tcg.app.data.sync

import app.tcg.app.auth.AuthSession
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SupabaseSyncTransportTest {

    private fun item(id: String, quantity: Int = 2) = SyncItem(
        id = id,
        cardId = "base1-4",
        quantity = quantity,
        variant = CardVariant.NORMAL,
        condition = Condition.NEAR_MINT,
        pricePaid = null,
        addedAt = 1_791_667_200_000L,
    )

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun bodyText(request: HttpRequestData): String = when (val content = request.body) {
        is TextContent -> content.text
        is ByteArrayContent -> content.bytes().decodeToString()
        else -> error("unexpected request body ${content::class}")
    }

    private fun transport(
        engine: MockEngine,
        userId: String? = "account-a",
    ) = SupabaseSyncTransport(
        baseUrl = "https://example.supabase.co",
        apiKey = "publishable-key",
        httpClient = client(engine),
        sessionProvider = { userId?.let { AuthSession(it, "user@example.com", "token-$it", "refresh-$it", 9_999L) } },
    )

    @Test
    fun `push refuses when current session does not match queued identity`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val failure = runCatching {
            transport(engine, userId = "account-b").push(
                "account-a",
                listOf(SyncOperation.Create("op-1", "dev-1", item("item-1"))),
            )
        }.exceptionOrNull()

        assertEquals(SyncErrorKind.UNAUTHORIZED, (failure as SupabaseSyncException).kind)
        assertEquals(0, requests)
    }

    @Test
    fun `link commit refuses a changed account before dispatch`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val plan = LinkPreview(
            planId = "9f86d081-884c-4d65-9c67-41b405d56001", digest = "digest-12345678",
            expiresAt = "2026-10-12T20:00:00Z", remoteHighWater = 7L,
            localOnly = emptyList(), cloudOnly = emptyList(), identical = emptyList(),
            conflicting = emptyList(), duplicateConfigurations = emptyList(), remoteItems = emptyList(),
        )

        val failure = runCatching {
            transport(engine, userId = "account-b").commitLink(plan, "device-0001", emptyList(), expectedIdentity = "account-a")
        }.exceptionOrNull()

        assertTrue(failure is SupabaseSyncException)
        assertEquals(SyncErrorKind.UNAUTHORIZED, (failure as SupabaseSyncException).kind)
        assertEquals(0, requests)
    }

    @Test
    fun `push serializes operations and maps per-operation outcomes`() = runTest {
        lateinit var body: String
        val engine = MockEngine { request ->
            body = bodyText(request)
            respond(
                content = """{"status":"ok","results":[{"opId":"op-1","status":"applied","reason":null,"revision":1}],"highWater":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val operation = SyncOperation.Create(opId = "op-1", deviceId = "dev-1", item = item("item-1"))

        val outcomes = transport(engine).push("account-a", listOf(operation))

        assertTrue(body.contains("\"p_device_id\":\"dev-1\""))
        assertTrue(body.contains("\"opId\":\"op-1\""))
        assertTrue(body.contains("\"kind\":\"create\""))
        assertTrue("client must sign operations for replay detection", body.contains("\"signature\":"))
        assertTrue(outcomes.single() is PushOutcome.Applied)
        assertEquals(1L, (outcomes.single() as PushOutcome.Applied).sequence)
    }

    @Test
    fun `push serializes purchase money as exact minor units`() = runTest {
        lateinit var body: String
        val engine = MockEngine { request ->
            body = bodyText(request)
            respond(
                content = """{"status":"ok","results":[{"opId":"op-paid","status":"applied","revision":1}],"highWater":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val paid = item("item-paid").copy(pricePaid = Money(12.34, Currency.USD))

        transport(engine).push("account-a", listOf(SyncOperation.Create("op-paid", "dev-1", paid)))

        val request = Json.parseToJsonElement(body).jsonObject
        val item = request["p_operations"]!!.jsonArray.single().jsonObject["item"]!!.jsonObject
        assertEquals("1234", item["pricePaid"]!!.jsonObject["amount"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pull parses minor units back to purchase money`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"operations":[{"opId":"op-paid","serverSequence":1,"deviceId":"dev-1","itemId":"item-paid","kind":"create","baseRevision":null,"item":{"id":"item-paid","cardId":"base1-4","quantity":1,"variant":"NORMAL","condition":"NEAR_MINT","pricePaid":{"amount":1234,"currency":"USD"},"addedAt":1791667200000}}],"highWater":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val page = transport(engine).pull("account-a", 0)
        val op = page.operations.single().operation as SyncOperation.Create

        assertEquals(Money(12.34, Currency.USD), op.item.pricePaid)
    }

    @Test
    fun `push maps conflict, already-applied and rejection distinctly`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"status":"ok","results":[
                  {"opId":"op-1","status":"conflict","reason":"stale","revision":null,"conflictId":"30000000-0000-4000-8000-000000000001","highWater":7,"rejectedOperation":{"kind":"create","opId":"10000000-0000-4000-8000-000000000001","deviceId":"20000000-0000-4000-8000-000000000001","item":{"id":"00000000-0000-4000-8000-000000000001","cardId":"base1-4","quantity":2,"variant":"NORMAL","condition":"NEAR_MINT","pricePaid":null,"addedAt":1791667200000}},"cloudItem":null,"conflictingItem":null},
                  {"opId":"op-1","status":"already-applied","reason":null,"revision":4},
                  {"opId":"op-1","status":"rejected","reason":"invalid-payload","revision":null,"conflictId":"30000000-0000-4000-8000-000000000002"}
                ],"highWater":4}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val operation = SyncOperation.Create(opId = "op-1", deviceId = "dev-1", item = item("item-1"))

        val outcomes = transport(engine).push("account-a", listOf(operation, operation, operation))

        assertTrue(outcomes[0] is PushOutcome.Conflict)
        assertEquals("stale", (outcomes[0] as PushOutcome.Conflict).reason)
        assertTrue(outcomes[1] is PushOutcome.AlreadyApplied)
        assertEquals(4L, (outcomes[1] as PushOutcome.AlreadyApplied).sequence)
        assertTrue("permanent rejection stays distinct from content conflict", outcomes[2] is PushOutcome.Rejected)
        assertEquals("invalid-payload", (outcomes[2] as PushOutcome.Rejected).reason)
    }

    @Test
    fun `push parses durable content conflict and validated cloud snapshot`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"status":"ok","results":[{"opId":"10000000-0000-4000-8000-000000000001","status":"conflict","reason":"stale","conflictId":"30000000-0000-4000-8000-000000000001","highWater":7,"rejectedOperation":{"kind":"create","opId":"10000000-0000-4000-8000-000000000001","deviceId":"20000000-0000-4000-8000-000000000001","item":{"id":"00000000-0000-4000-8000-000000000001","cardId":"base1-4","quantity":2,"variant":"NORMAL","condition":"NEAR_MINT","pricePaid":null,"addedAt":1791667200000}},"cloudItem":{"itemId":"00000000-0000-4000-8000-000000000001","cardId":"base1-4","quantity":3,"variant":"NORMAL","condition":"NEAR_MINT","pricePaid":null,"addedAt":1791667200000,"revision":4,"deleted":false},"conflictingItem":null}],"highWater":7}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val operation = SyncOperation.Create("10000000-0000-4000-8000-000000000001", "20000000-0000-4000-8000-000000000001", item("00000000-0000-4000-8000-000000000001"))

        val outcome = transport(engine).push("account-a", listOf(operation)).single() as PushOutcome.Conflict

        assertEquals("30000000-0000-4000-8000-000000000001", outcome.conflictId)
        assertEquals(7L, outcome.highWater)
        assertEquals(3, (outcome.cloudItem as PushCloudItem.Present).item.quantity)
    }

    @Test
    fun `push conflict list recovers lost response and resolution receipt replays through acknowledgment`() = runTest {
        val conflictId = "30000000-0000-4000-8000-000000000001"
        val resolutionId = "40000000-0000-4000-8000-000000000001"
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += path
            val response = when (path.substringAfterLast('/')) {
                "read_collection_push_conflicts" -> """[{"id":"$conflictId","opId":"10000000-0000-4000-8000-000000000001","itemId":"00000000-0000-4000-8000-000000000001","reason":"stale","deviceId":"20000000-0000-4000-8000-000000000001","rejectedOperation":{"kind":"create","opId":"10000000-0000-4000-8000-000000000001","deviceId":"20000000-0000-4000-8000-000000000001","item":{"id":"00000000-0000-4000-8000-000000000001","cardId":"base1-4","quantity":2,"variant":"NORMAL","condition":"NEAR_MINT","pricePaid":null,"addedAt":1791667200000}},"cloudItem":null,"conflictingItem":null,"highWater":7,"resolution":null}]"""
                "resolve_collection_push_conflict" -> """{"status":"resolved","conflictId":"$conflictId","choice":"keep-cloud","resolutionId":"$resolutionId","result":{"kind":"keep-cloud","itemId":"00000000-0000-4000-8000-000000000001","cloudItem":null}}"""
                else -> """{"status":"resolved","conflictId":"$conflictId","choice":"keep-cloud","resolutionId":"$resolutionId"}"""
            }
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val sync = transport(engine)

        val conflicts = sync.listPushConflicts("account-a")
        val receipt = sync.resolvePushConflict(conflictId, PushConflictChoice.KEEP_CLOUD, "20000000-0000-4000-8000-000000000001", "account-a")
        val ack = sync.acknowledgePushConflictResolution(conflictId, resolutionId, "account-a")

        assertEquals(conflictId, conflicts.single().id)
        assertEquals(resolutionId, receipt.resolutionId)
        assertEquals("resolved", ack.status)
        assertEquals(listOf("read_collection_push_conflicts", "resolve_collection_push_conflict", "ack_collection_push_conflict_resolution"), requests.map { it.substringAfterLast('/') })
    }

    @Test
    fun `push conflict list rejects malformed snapshot and resolution refuses changed account before dispatch`() = runTest {
        val malformed = MockEngine {
            respond("""[{"id":"bad","opId":null,"itemId":"bad","reason":"stale","deviceId":"bad","rejectedOperation":{},"cloudItem":{"itemId":"bad","deleted":false},"conflictingItem":null,"highWater":-1,"resolution":null}]""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val failure = runCatching { transport(malformed).listPushConflicts("account-a") }.exceptionOrNull()
        assertTrue(failure is SupabaseSyncException)
        assertEquals(SyncErrorKind.INVALID_RESPONSE, (failure as SupabaseSyncException).kind)

        var requests = 0
        val changed = MockEngine { requests++; respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val identityFailure = runCatching {
            transport(changed, userId = "account-b").resolvePushConflict("30000000-0000-4000-8000-000000000001", PushConflictChoice.KEEP_LOCAL, "20000000-0000-4000-8000-000000000001", "account-a")
        }.exceptionOrNull()
        assertEquals(SyncErrorKind.UNAUTHORIZED, (identityFailure as SupabaseSyncException).kind)
        assertEquals(0, requests)
    }

    @Test
    fun `revoked device and unauthorized push raise typed errors`() = runTest {
        val revoked = MockEngine {
            respond(
                content = """{"status":"device-revoked","results":[],"highWater":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val operation = SyncOperation.Create(opId = "op-1", deviceId = "dev-1", item = item("item-1"))
        try {
            transport(revoked).push("account-a", listOf(operation))
            fail("expected device-revoked error")
        } catch (error: SupabaseSyncException) {
            assertEquals(SyncErrorKind.DEVICE_REVOKED, error.kind)
        }

        val denied = MockEngine {
            respond(
                content = """{"message":"permission denied for function push_collection_operations","code":"42501"}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        try {
            transport(denied).push("account-a", listOf(operation))
            fail("expected unauthorized error")
        } catch (error: SupabaseSyncException) {
            assertEquals(SyncErrorKind.UNAUTHORIZED, error.kind)
        }
    }

    @Test
    fun `anonymous session is refused before any network call`() = runTest {
        val engine = MockEngine { error("must not perform network call without a signed-in user") }
        try {
            transport(engine, userId = null).push(
                "account-a",
                listOf(SyncOperation.Create(opId = "op-1", deviceId = "dev-1", item = item("item-1"))),
            )
            fail("expected unauthorized error")
        } catch (error: SupabaseSyncException) {
            assertEquals(SyncErrorKind.UNAUTHORIZED, error.kind)
        }
    }

    @Test
    fun `pull requests the cursor page and returns ordered operations`() = runTest {
        lateinit var body: String
        val engine = MockEngine { request ->
            body = bodyText(request)
            respond(
                content = """{"operations":[
                  {"opId":"op-7","serverSequence":7,"deviceId":"dev-1","itemId":"item-1","kind":"create","baseRevision":null,
                   "item":{"id":"item-1","cardId":"base1-4","quantity":5,"variant":"NORMAL","condition":"NEAR_MINT","pricePaid":null,"addedAt":1791667200000}},
                  {"opId":"op-8","serverSequence":8,"deviceId":"dev-1","itemId":"item-1","kind":"delete","baseRevision":1,"item":null}
                ],"highWater":8}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val page = transport(engine).pull("account-a", 3L)

        assertTrue(body.contains("\"p_after_sequence\":3"))
        assertEquals(8L, page.highWaterMark)
        assertEquals(listOf(7L, 8L), page.operations.map { it.sequence })
        val create = page.operations[0].operation
        assertTrue(create is SyncOperation.Create)
        assertEquals(CardVariant.NORMAL, (create as SyncOperation.Create).item.variant)
        val delete = page.operations[1].operation
        assertTrue(delete is SyncOperation.Delete)
        assertEquals(1L, (delete as SyncOperation.Delete).baseRevision)
    }

    @Test
    fun `prepareLink sends the local digest and parses the preview snapshot`() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += bodyText(request)
            val url = request.url.toString()
            val content = when {
                url.endsWith("/rpc/prepare_collection_link") ->
                    """{"planId":"9f86d081-884c-4d65-9c67-41b405d56001","expiresAt":"2026-10-12T20:00:00Z",
                       "remoteHighWater":7,"preview":{"localOnly":["item-1"],"cloudOnly":["item-2"],"identical":[],
                       "conflicting":[{"itemId":"item-3","reason":"different-content"}],
                       "duplicateConfigurations":[{"localItemId":"item-4","remoteItemId":"item-5"}]}}"""
                url.endsWith("/rpc/read_collection_link_snapshot") ->
                    """{"highWater":7,"items":[{"id":"item-2","cardId":"base1-4","quantity":1,"variant":"NORMAL",
                       "condition":"NEAR_MINT","pricePaid":null,"addedAt":1791667200000,"revision":3}]}"""
                else -> error("unexpected call $url")
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val preview = transport(engine).prepareLink(listOf(item("item-1")), "account-a")

        assertTrue(bodies[0].contains("\"p_local_digest\":\""))
        assertFalse(bodies[0].contains("p_remote_sequence"))
        assertEquals("9f86d081-884c-4d65-9c67-41b405d56001", preview.planId)
        assertEquals(7L, preview.remoteHighWater)
        assertEquals(listOf("item-1"), preview.localOnly)
        assertEquals(listOf("item-2"), preview.cloudOnly)
        assertEquals(1, preview.conflicting.size)
        assertEquals("different-content", preview.conflicting[0].reason)
        assertEquals(1, preview.duplicateConfigurations.size)
        assertEquals(1, preview.remoteItems.size)
        assertEquals(3L, preview.remoteItems[0].revision)
    }

    @Test
    fun `commitLink signs operations and maps every refusal without throwing`() = runTest {
        val deviceId = "device-0001"
        val responses = ArrayDeque(listOf(
            """{"status":"linked","push":{"status":"ok","results":[{"opId":"op-1","status":"applied","reason":null,"revision":1}],"highWater":9}}""",
            """{"status":"stale-preview"}""",
            """{"status":"partial","push":{"status":"ok","results":[{"opId":"op-1","status":"conflict","reason":"id-collision","revision":null,"conflictId":"30000000-0000-4000-8000-000000000001","highWater":7,"rejectedOperation":{"kind":"create","opId":"10000000-0000-4000-8000-000000000001","deviceId":"20000000-0000-4000-8000-000000000001","item":{"id":"00000000-0000-4000-8000-000000000001","cardId":"base1-4","quantity":2,"variant":"NORMAL","condition":"NEAR_MINT","pricePaid":null,"addedAt":1791667200000}},"cloudItem":null,"conflictingItem":null}],"highWater":10}}""",
        ))
        lateinit var lastBody: String
        val engine = MockEngine { request ->
            lastBody = bodyText(request)
            respond(
                content = responses.removeFirst(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val plan = LinkPreview(
            planId = "9f86d081-884c-4d65-9c67-41b405d56001",
            digest = "digest-12345678",
            expiresAt = "2026-10-12T20:00:00Z",
            remoteHighWater = 7L,
            localOnly = listOf("item-1"),
            cloudOnly = emptyList(),
            identical = emptyList(),
            conflicting = emptyList(),
            duplicateConfigurations = emptyList(),
            remoteItems = emptyList(),
        )
        val operation = SyncOperation.Create(opId = "op-1", deviceId = deviceId, item = item("item-1"))

        val linked = transport(engine).commitLink(plan, deviceId, listOf(operation), "account-a")

        assertTrue(linked is LinkCommitResult.Linked)
        assertEquals(9L, (linked as LinkCommitResult.Linked).highWater)
        assertTrue(linked.outcomes.single() is PushOutcome.Applied)
        assertTrue(lastBody.contains("\"p_plan_id\":\"9f86d081-884c-4d65-9c67-41b405d56001\""))
        assertTrue(lastBody.contains("\"p_digest\":\"digest-12345678\""))
        assertTrue(lastBody.contains("\"p_device_id\":\"device-0001\""))
        assertTrue(lastBody.contains("\"signature\":"))

        val refused = transport(engine).commitLink(plan, deviceId, listOf(operation), "account-a")
        assertEquals(LinkCommitResult.StalePreview, refused)

        val partial = transport(engine).commitLink(plan, deviceId, listOf(operation), "account-a")
        assertTrue(partial is LinkCommitResult.Partial)
        assertEquals(10L, (partial as LinkCommitResult.Partial).highWater)
        assertTrue(partial.outcomes.single() is PushOutcome.Conflict)
        assertEquals("id-collision", (partial.outcomes.single() as PushOutcome.Conflict).reason)
    }

    @Test
    fun `commitLink classifies local serialization failure before dispatch`() = runTest {
        val engine = MockEngine { error("must not dispatch invalid money") }
        val plan = LinkPreview(
            planId = "9f86d081-884c-4d65-9c67-41b405d56001", digest = "digest-12345678",
            expiresAt = "2026-10-12T20:00:00Z", remoteHighWater = 7L,
            localOnly = listOf("item-1"), cloudOnly = emptyList(), identical = emptyList(),
            conflicting = emptyList(), duplicateConfigurations = emptyList(), remoteItems = emptyList(),
        )
        val invalid = item("item-1").copy(pricePaid = Money(12.345, Currency.USD))
        val failure = runCatching {
            transport(engine).commitLink(plan, "device-0001", listOf(SyncOperation.Create("op-1", "device-0001", invalid)), "account-a")
        }.exceptionOrNull()

        assertTrue(failure is SupabaseSyncException)
        assertEquals(SyncErrorKind.PRE_DISPATCH, (failure as SupabaseSyncException).kind)
    }

    @Test
    fun `commitLink distinguishes invalid response from uncertain network failure`() = runTest {
        val plan = LinkPreview(
            planId = "9f86d081-884c-4d65-9c67-41b405d56001", digest = "digest-12345678",
            expiresAt = "2026-10-12T20:00:00Z", remoteHighWater = 7L,
            localOnly = emptyList(), cloudOnly = emptyList(), identical = emptyList(),
            conflicting = emptyList(), duplicateConfigurations = emptyList(), remoteItems = emptyList(),
        )
        val invalidEngine = MockEngine {
            respond(content = "{bad", status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val invalid = runCatching { transport(invalidEngine).commitLink(plan, "device-0001", emptyList(), "account-a") }.exceptionOrNull()
        assertTrue(invalid is SupabaseSyncException)
        assertEquals(SyncErrorKind.INVALID_RESPONSE, (invalid as SupabaseSyncException).kind)

        val lostEngine = MockEngine { throw java.io.IOException("connection lost") }
        val lost = runCatching { transport(lostEngine).commitLink(plan, "device-0001", emptyList(), "account-a") }.exceptionOrNull()
        assertTrue(lost is SupabaseSyncException)
        assertEquals(SyncErrorKind.COMMIT_UNCERTAIN, (lost as SupabaseSyncException).kind)
        assertTrue(lost.message.orEmpty().contains("pode já ter recebido"))
    }

    @Test
    fun `prepareLink refuses an anonymous session before any network call`() = runTest {
        val engine = MockEngine { error("must not perform network call without a signed-in user") }
        try {
            transport(engine, userId = null).prepareLink(listOf(item("item-1")), "account-a")
            fail("expected unauthorized error")
        } catch (error: SupabaseSyncException) {
            assertEquals(SyncErrorKind.UNAUTHORIZED, error.kind)
        }
    }
}
