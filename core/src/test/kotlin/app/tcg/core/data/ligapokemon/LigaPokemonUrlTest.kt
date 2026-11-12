package app.tcg.core.data.ligapokemon

import app.tcg.core.domain.model.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LigaPokemonUrlTest {

    @Test
    fun builds_the_printed_name_and_number_without_an_edition_code() {
        val card = card(
            name = "Meowth ex",
            collectorNumber = "062",
            rarity = "Rara Dupla",
            setTotal = 88,
        )

        assertEquals(
            "https://www.ligapokemon.com.br/?view=cards/card&card=Meowth+ex+%28062%2F088%29&num=062",
            LigaPokemonUrl.buildUrl(card),
        )
    }

    @Test
    fun promo_printings_are_numbered_against_infinity() {
        val card = card(
            name = "Pikachu",
            collectorNumber = "027",
            rarity = "Promo",
            setTotal = 225,
        )

        val url = LigaPokemonUrl.buildUrl(card)

        assertEquals(
            "https://www.ligapokemon.com.br/?view=cards/card&card=Pikachu+%28027%2F%E2%88%9E%29&num=027",
            url,
        )
        assertFalse(url.contains("ed="))
    }

    @Test
    fun a_card_without_a_set_total_keeps_only_its_number() {
        val card = card(name = "Pikachu", collectorNumber = "025", rarity = "Comum", setTotal = null)

        assertEquals(
            "https://www.ligapokemon.com.br/?view=cards/card&card=Pikachu+%28025%29&num=025",
            LigaPokemonUrl.buildUrl(card),
        )
    }

    private fun card(name: String, collectorNumber: String, rarity: String?, setTotal: Int?) = Card(
        id = "sv8-$collectorNumber",
        name = name,
        setId = "sv8",
        setName = "Fagulhas Impetuosas",
        collectorNumber = collectorNumber,
        rarity = rarity,
        hp = 120,
        types = listOf("Incolor"),
        attacks = emptyList(),
        imageUrl = null,
        variants = emptySet(),
        prices = emptyList(),
        setTotal = setTotal,
    )
}
