package app.tcg.core.data.ligapokemon

import app.tcg.core.domain.model.Card
import java.net.URLEncoder

object LigaPokemonUrl {
    /**
     * Builds the direct LigaPokemon card search/detail URL for the given [card].
     * Example: https://www.ligapokemon.com.br/?view=cards/card&card=Meowth+ex+(062/088)&ed=POR&num=062
     */
    fun buildUrl(card: Card): String {
        val total = card.setTotal
        val numberSuffix = if (total != null && total > 0) {
            "(${card.collectorNumber}/${total.toString().padStart(3, '0')})"
        } else {
            "(${card.collectorNumber})"
        }
        val cardParam = "${card.name} $numberSuffix".trim()
        val encodedCard = URLEncoder.encode(cardParam, "UTF-8")
        val encodedNum = URLEncoder.encode(card.collectorNumber, "UTF-8")
        return "https://www.ligapokemon.com.br/?view=cards/card&card=$encodedCard&ed=POR&num=$encodedNum"
    }
}
