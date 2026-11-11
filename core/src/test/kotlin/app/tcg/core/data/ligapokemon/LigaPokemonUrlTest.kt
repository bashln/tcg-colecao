package app.tcg.core.data.ligapokemon

import app.tcg.core.domain.model.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LigaPokemonUrlTest {

    @Test
    fun generates_correct_ligapokemon_url_with_set_total() {
        val card = Card(
            id = "sv8-062",
            name = "Meowth ex",
            setId = "sv8",
            setName = "Fagulhas Impetuosas",
            collectorNumber = "062",
            rarity = "Double Rare",
            hp = 120,
            types = listOf("Colorless"),
            attacks = emptyList(),
            imageUrl = "https://assets.tcgdex.net/pt/sv/sv08/062",
            variants = emptySet(),
            prices = emptyList(),
            setTotal = 88,
        )

        val url = LigaPokemonUrl.buildUrl(card)
        // URL should contain card name, collector number, set total, and ed=POR
        assertTrue("URL contains Meowth+ex", url.contains("Meowth+ex"))
        assertTrue("URL contains collector number 062", url.contains("num=062"))
        assertTrue("URL contains ed=POR", url.contains("ed=POR"))
        assertTrue("URL contains set total 088", url.contains("088"))
    }

    @Test
    fun generates_correct_ligapokemon_url_without_set_total() {
        val card = Card(
            id = "sv8-062",
            name = "Pikachu",
            setId = "sv8",
            setName = "Fagulhas Impetuosas",
            collectorNumber = "025",
            rarity = "Common",
            hp = 60,
            types = listOf("Lightning"),
            attacks = emptyList(),
            imageUrl = null,
            variants = emptySet(),
            prices = emptyList(),
            setTotal = null,
        )

        val url = LigaPokemonUrl.buildUrl(card)
        assertTrue("URL contains Pikachu", url.contains("Pikachu"))
        assertTrue("URL contains num=025", url.contains("num=025"))
        assertTrue("URL contains ed=POR", url.contains("ed=POR"))
    }
}
