package app.tcg.app.data.sync

import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
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
import org.junit.Assert.assertEquals
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
        userId: String? = "00000000-0000-4000-8000-0000000000aa",
    ) = SupabaseSyncTransport(
        baseUrl = "https://example.supabase.co",
        apiKey = "publishable-key",
        httpClient = client(engine),
        userIdProvider = { userId },
    )

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
    fun `push maps conflict, already-applied and rejection distinctly`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"status":"ok","results":[
                  {"opId":"op-1","status":"conflict","reason":"stale","revision":null},
                  {"opId":"op-1","status":"already-applied","reason":null,"revision":4},
                  {"opId":"op-1","status":"rejected","reason":"invalid-payload","revision":null}
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
        assertTrue("server rejection surfaces as conflict", outcomes[2] is PushOutcome.Conflict)
        assertEquals("invalid-payload", (outcomes[2] as PushOutcome.Conflict).reason)
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
}
