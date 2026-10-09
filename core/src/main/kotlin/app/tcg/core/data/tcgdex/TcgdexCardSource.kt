package app.tcg.core.data.tcgdex

import app.tcg.core.domain.model.Card
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

interface TcgdexCardSource {
    suspend fun searchByName(name: String, language: String = "pt"): List<Card>
    suspend fun searchByNumber(setId: String?, collectorNumber: String, language: String = "pt"): List<Card>
    suspend fun getCard(id: String, language: String = "pt"): Card?
}

class KtorTcgdexCardSource(private val httpClient: HttpClient) : TcgdexCardSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchByName(name: String, language: String): List<Card> =
        search("$language/cards") { parameter("name", name) }

    override suspend fun searchByNumber(setId: String?, collectorNumber: String, language: String): List<Card> =
        search("$language/cards") {
            setId?.let { parameter("set", it) }
            parameter("localId", collectorNumber)
        }

    override suspend fun getCard(id: String, language: String): Card? {
        val response = httpClient.get("$BASE_URL/$language/cards/$id")
        if (response.status == HttpStatusCode.NotFound) return null
        return json.decodeFromString(TcgdexCardDto.serializer(), response.bodyAsText()).toCard()
    }

    private suspend fun search(path: String, params: HttpRequestBuilder.() -> Unit): List<Card> {
        val body = httpClient.get("$BASE_URL/$path") { params() }.bodyAsText()
        // The list endpoint answers with an array; tolerate a bare object so a single-card
        // response is still usable.
        val dtos = when (val element = json.parseToJsonElement(body)) {
            is JsonArray -> element.map { json.decodeFromJsonElement(TcgdexCardDto.serializer(), it) }
            is JsonObject -> listOf(json.decodeFromJsonElement(TcgdexCardDto.serializer(), element))
            else -> emptyList()
        }
        return dtos.map { it.toCard() }
    }

    private companion object {
        const val BASE_URL = "https://api.tcgdex.net/v2"
    }
}
