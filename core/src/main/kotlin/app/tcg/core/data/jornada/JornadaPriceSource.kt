package app.tcg.core.data.jornada

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

interface JornadaQuoteSource {
    suspend fun quotes(cards: List<JornadaCardRequest>, maxAgeHours: Int): JornadaResponse
}

/**
 * Calls the Supabase function that holds the Jornada Games key. The client only
 * needs the publishable Supabase key, so the Jornada key never reaches the app.
 */
class JornadaPriceSource(
    private val httpClient: HttpClient,
    baseUrl: String,
    private val apiKey: String,
) : JornadaQuoteSource {
    private val endpoint = "${baseUrl.trimEnd('/')}/functions/v1/jornada-prices"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun quotes(
        cards: List<JornadaCardRequest>,
        maxAgeHours: Int,
    ): JornadaResponse {
        require(cards.isNotEmpty()) { "Uma consulta de preço precisa de pelo menos uma carta" }
        require(apiKey.isNotBlank()) { "A fonte de preço da Jornada não está configurada" }
        val payload = json.encodeToString(JornadaRequest.serializer(), JornadaRequest(cards, maxAgeHours))
        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header("apikey", apiKey)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (response.status.value !in 200..299) throw JornadaPriceException(response.status.value)
        return json.decodeFromString(JornadaResponse.serializer(), response.bodyAsText())
    }
}

class JornadaPriceException(val status: Int) :
    Exception("A consulta de preço da Jornada falhou com status $status")

fun Card.toJornadaRequest(): JornadaCardRequest = JornadaCardRequest(
    cardId = id,
    setId = setId,
    collectorNumber = collectorNumber,
    name = name,
    variants = variants.map(CardVariant::name).sorted(),
)
