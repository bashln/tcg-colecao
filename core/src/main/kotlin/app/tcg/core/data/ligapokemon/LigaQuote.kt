package app.tcg.core.data.ligapokemon

import app.tcg.core.domain.model.CardVariant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Ligapokemon publishes one medium quote per printing: plain and foil, both in BRL. */
data class LigaQuote(
    val normalMedium: Double?,
    val foilMedium: Double?,
) {
    fun mediumFor(variant: CardVariant): Double? = when (variant) {
        CardVariant.NORMAL -> normalMedium
        CardVariant.HOLOFOIL, CardVariant.REVERSE_HOLOFOIL -> foilMedium
        CardVariant.FIRST_EDITION, CardVariant.PROMO -> foilMedium ?: normalMedium
    }
}

object LigaQuoteParser {
    private const val NORMAL_PRINTING = "0"
    private const val FOIL_PRINTING = "2"

    /**
     * Reads the card entry out of the page's `cards_editions` payload, preferring the entry whose
     * `num` matches [collectorNumber].
     */
    fun parse(editionsJson: String, collectorNumber: String? = null): LigaQuote? {
        val editions = runCatching { Json.parseToJsonElement(editionsJson) }.getOrNull() as? JsonArray
            ?: return null
        val entries = editions.mapNotNull { it as? JsonObject }
        val entry = entries.firstOrNull { collectorNumber != null && it.text("num") == collectorNumber }
            ?: entries.firstOrNull()
            ?: return null
        val prices = entry["price"] as? JsonObject
        val quote = LigaQuote(
            normalMedium = medium(prices?.get(NORMAL_PRINTING)),
            foilMedium = medium(prices?.get(FOIL_PRINTING)),
        )
        return quote.takeUnless { it.normalMedium == null && it.foilMedium == null }
    }

    // A printing with no listings arrives as [], {}, null, or a missing key, and its quote as
    // either a JSON number or a numeric string.
    private fun medium(bucket: JsonElement?): Double? {
        val quote = (bucket as? JsonObject)?.get("m") as? JsonPrimitive ?: return null
        return quote.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
