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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Cross-platform wire contract: the Android transport must emit byte-identical canonical
 * JSON and SHA-256 signatures to the shared vectors in `testdata/sync-wire-vectors.json`,
 * which the web transport verifies against the same file. Divergence means the server's
 * op-ID/hash mismatch detection would treat the two platforms' retries as different content.
 */
class CrossPlatformSignatureTest {

    private fun vectorsFile(): File {
        val base = File("").absoluteFile
        val candidates = listOf(
            File(base, "testdata/sync-wire-vectors.json"),
            File(base.parentFile, "testdata/sync-wire-vectors.json"),
            File(base, "../testdata/sync-wire-vectors.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("shared wire vector file not found from ${base.absolutePath}")
    }

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun transport(engine: MockEngine) = SupabaseSyncTransport(
        baseUrl = "https://example.supabase.co",
        apiKey = "publishable-key",
        httpClient = client(engine),
        sessionProvider = {
            AuthSession("account-a", "user@example.com", "token", "refresh", 9_999L)
        },
    )

    private fun money(amount: Double): Money? =
        if (amount == 0.0) null else Money(amount = amount, currency = Currency.USD)

    @Test
    fun `android emits the exact shared signature for each operation kind`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(vectorsFile().readText()).jsonObject
        val cases = root.getValue("cases").jsonArray
        val priceVector = root.getValue("priceVector").jsonObject

        for (element in cases) {
            val testCase = element.jsonObject
            val name = testCase.getValue("name").jsonPrimitive.content
            val expectedSignature = testCase.getValue("signature").jsonPrimitive.content
            val operation = testCase.getValue("operation").jsonObject
            val kind = operation.getValue("kind").jsonPrimitive.content
            // Only the create case declares a major-unit price; the replace fixture is
            // deliberately pricePaid=null, so feeding a price there would corrupt it.
            val priceMajor = testCase["priceMajor"]?.let { it.jsonPrimitive.content.toDouble() }

            var requestBody: String? = null
            val engine = MockEngine { request ->
                requestBody = (request.body as io.ktor.http.content.TextContent).text
                respond(
                    content = """{"status":"ok","results":[],"highWater":0}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

            val op = toOperation(kind, operation, priceMajor)
            transport(engine).push("account-a", listOf(op))

            val sent = json.parseToJsonElement(checkNotNull(requestBody))
                .jsonObject.getValue("p_operations").jsonArray.first().jsonObject
            assertEquals("kind mismatch for $name", kind, sent.getValue("kind").jsonPrimitive.content)
            assertEquals("signature mismatch for $name", expectedSignature, sent.getValue("signature").jsonPrimitive.content)
        }
    }

    @Test
    fun `android writes price as integer minor units on the wire`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(vectorsFile().readText()).jsonObject
        val minor = root.getValue("priceVector").jsonObject.getValue("minor").jsonPrimitive.content.toLong()
        val createCase = root.getValue("cases").jsonArray.first().jsonObject
        val createOperation = createCase.getValue("operation").jsonObject
        val createPriceMajor = createCase.getValue("priceMajor").jsonPrimitive.content.toDouble()

        var requestBody: String? = null
        val engine = MockEngine { request ->
            requestBody = (request.body as io.ktor.http.content.TextContent).text
            respond(
                content = """{"status":"ok","results":[],"highWater":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        transport(engine).push("account-a", listOf(toOperation("create", createOperation, createPriceMajor)))

        val sent = json.parseToJsonElement(checkNotNull(requestBody))
            .jsonObject.getValue("p_operations").jsonArray.first().jsonObject
        val price = sent.getValue("item").jsonObject.getValue("pricePaid")
        assertNotNull(price)
        assertEquals(minor, price.jsonObject.getValue("amount").jsonPrimitive.content.toLong())
        assertEquals("USD", price.jsonObject.getValue("currency").jsonPrimitive.content)
    }

    private fun toOperation(kind: String, operation: JsonObject, priceMajor: Double?): SyncOperation {
        val opId = operation.getValue("opId").jsonPrimitive.content
        val deviceId = "device-0001"
        return when (kind) {
            "delete" -> SyncOperation.Delete(
                opId = opId,
                deviceId = deviceId,
                itemId = operation.getValue("itemId").jsonPrimitive.content,
                baseRevision = operation.getValue("baseRevision").jsonPrimitive.content.toLong(),
            )
            "create", "replace" -> {
                val itemJson = operation.getValue("item").jsonObject
                // The fixture stores pricePaid in minor units (wire form); the transport
                // receives major units and converts, so feed the case's major value when
                // it declares one, otherwise keep the fixture's null price.
                val item = SyncItem(
                    id = itemJson.getValue("id").jsonPrimitive.content,
                    cardId = itemJson.getValue("cardId").jsonPrimitive.content,
                    quantity = itemJson.getValue("quantity").jsonPrimitive.content.toInt(),
                    variant = enumValueOf<CardVariant>(itemJson.getValue("variant").jsonPrimitive.content),
                    condition = enumValueOf<Condition>(itemJson.getValue("condition").jsonPrimitive.content),
                    pricePaid = priceMajor?.let { money(it) },
                    addedAt = itemJson.getValue("addedAt").jsonPrimitive.content.toLong(),
                )
                if (kind == "create") SyncOperation.Create(opId = opId, deviceId = deviceId, item = item)
                else SyncOperation.Replace(
                    opId = opId,
                    deviceId = deviceId,
                    baseRevision = operation.getValue("baseRevision").jsonPrimitive.content.toLong(),
                    item = item,
                )
            }
            else -> error("unknown operation kind $kind")
        }
    }
}
