package app.tcg.core.data.ligapokemon

import app.tcg.core.domain.model.Card
import java.net.URLEncoder

object LigaPokemonUrl {
    /** Promo printings carry no set size, so the site numbers them against infinity. */
    private const val PROMO_RARITY = "Promo"

    /**
     * Builds the card page URL the way the site's own links do. It carries no `ed`: that
     * parameter is the site's edition code, which TCGdex does not expose, and a wrong value
     * sends the request to a marketplace search that finds nothing. The printed name and
     * number are enough for the site to resolve the card.
     */
    fun buildUrl(card: Card): String {
        val total = card.setTotal
        val denominator = when {
            card.rarity == PROMO_RARITY -> "∞"
            total != null && total > 0 -> total.toString().padStart(3, '0')
            else -> null
        }
        val numberSuffix = if (denominator != null) {
            "(${card.collectorNumber}/$denominator)"
        } else {
            "(${card.collectorNumber})"
        }
        val cardParam = "${card.name} $numberSuffix".trim()
        val encodedCard = URLEncoder.encode(cardParam, "UTF-8")
        val encodedNum = URLEncoder.encode(card.collectorNumber, "UTF-8")
        return "https://www.ligapokemon.com.br/?view=cards/card&card=$encodedCard&num=$encodedNum"
    }
}
