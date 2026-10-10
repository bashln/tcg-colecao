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
    private var setOfficialCache: Map<String, Int>? = null

    override suspend fun searchByName(name: String, language: String): List<Card> =
        search(language, "cards") { parameter("name", name) }

    override suspend fun searchByNumber(setId: String?, collectorNumber: String, language: String): List<Card> {
        // Printed collector numbers and the stored localId disagree on zero padding across sets,
        // so an empty first query is retried with the number normalized.
        for (number in localIdCandidates(collectorNumber)) {
            val cards = search(language, "cards") {
                setId?.let { parameter("set", it) }
                parameter("localId", number)
            }
            if (cards.isNotEmpty()) return cards
        }
        return emptyList()
    }

    private fun localIdCandidates(collectorNumber: String): List<String> {
        val stripped = collectorNumber.trimStart('0').ifEmpty { "0" }
        val candidates = mutableListOf(collectorNumber, stripped)
        if (!collectorNumber.startsWith("0")) {
            val padded = collectorNumber.padStart(3, '0')
            if (padded != collectorNumber) candidates += padded
        }
        return candidates.distinct()
    }

    override suspend fun getCard(id: String, language: String): Card? {
        val response = httpClient.get("$BASE_URL/$language/cards/$id")
        if (response.status == HttpStatusCode.NotFound) return null
        return json.decodeFromString(TcgdexCardDto.serializer(), response.bodyAsText()).toCard()
    }

    private suspend fun search(
        language: String,
        path: String,
        params: HttpRequestBuilder.() -> Unit,
    ): List<Card> {
        val body = httpClient.get("$BASE_URL/$language/$path") { params() }.bodyAsText()
        // The list endpoint answers with an array; tolerate a bare object so a single-card
        // response is still usable.
        val dtos = when (val element = json.parseToJsonElement(body)) {
            is JsonArray -> element.map { json.decodeFromJsonElement(TcgdexCardDto.serializer(), it) }
            is JsonObject -> listOf(json.decodeFromJsonElement(TcgdexCardDto.serializer(), element))
            else -> emptyList()
        }
        return withSetTotals(dtos.map { it.toCard() }, language)
    }

    // Search results are card briefs and omit the set's card count, so fetch the set catalog
    // once and stamp each brief with its printed set size; otherwise a scan has no set signal.
    private suspend fun withSetTotals(cards: List<Card>, language: String): List<Card> {
        if (cards.isEmpty() || cards.all { it.setTotal != null }) return cards
        val officials = setOfficials(language)
        return cards.map { if (it.setTotal != null) it else it.copy(setTotal = officials[it.setId]) }
    }

    private suspend fun setOfficials(language: String): Map<String, Int> {
        setOfficialCache?.let { return it }
        val body = httpClient.get("$BASE_URL/$language/sets").bodyAsText()
        val sets = when (val element = json.parseToJsonElement(body)) {
            is JsonArray -> element.map { json.decodeFromJsonElement(TcgdexSetDto.serializer(), it) }
            is JsonObject -> listOf(json.decodeFromJsonElement(TcgdexSetDto.serializer(), element))
            else -> emptyList()
        }
        val officials = sets.mapNotNull { set ->
            set.id?.let { id -> set.cardCount?.official?.let { id to it } }
        }.toMap()
        if (officials.isNotEmpty()) setOfficialCache = officials
        return officials
    }

    private companion object {
        const val BASE_URL = "https://api.tcgdex.net/v2"
    }
}
