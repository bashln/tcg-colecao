package app.tcg.core.scan

import app.tcg.core.domain.model.OcrLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcrParserTest {
    private fun lines(vararg t: String) = t.mapIndexed { i, s -> OcrLine(s, i * 100f, 40f) }

    @Test fun extracts_collector_number_with_slash() {
        val r = OcrParser.parse(lines("Furret", "HP 110", "136/189"))
        assertEquals("136", r.collectorNumber)
    }

    @Test fun parses_printed_set_total_from_slash_form() {
        assertEquals(189, OcrParser.parse(lines("Furret", "HP 110", "136/189")).setTotal)
        assertEquals(165, OcrParser.parse(lines("Bulbasaur", "001/165")).setTotal)
        assertEquals(196, OcrParser.parse(lines("Oddish", "o0/196")).setTotal)
    }

    @Test fun set_total_is_null_without_slash_form() {
        assertNull(OcrParser.parse(lines("Noctowl", "SWSH027")).setTotal)
        assertNull(OcrParser.parse(lines("Furret", "HP 110", "136")).setTotal)
        assertNull(OcrParser.parse(emptyList()).setTotal)
    }

    @Test fun extracts_collector_number_with_letter_o_as_zero() {
        assertEquals("019", OcrParser.parse(lines("Charizard V", "D o19/189")).collectorNumber)
        assertEquals("001", OcrParser.parse(lines("Bulbasaur", "GMEW o01/165O")).collectorNumber)
        assertEquals("028", OcrParser.parse(lines("Darmanitan", "1 Do28/189")).collectorNumber)
        assertEquals("024", OcrParser.parse(lines("Arbok ex", "GNEWP O24/165")).collectorNumber)
        assertEquals("001", OcrParser.parse(lines("Butterfree V", "D oo1/189")).collectorNumber)
    }

    @Test fun extracts_collector_number_with_trailing_symbols_or_digits() {
        assertEquals("029", OcrParser.parse(lines("Nidoran", "GMEW P 029/165O")).collectorNumber)
        assertEquals("44", OcrParser.parse(lines("Voltorb", "llust. Atsuko Nishida 44/1460")).collectorNumber)
    }

    @Test fun extracts_isolated_collector_number_in_card_footer() {
        val cardLines = listOf(
            OcrLine("Furret", 30f, 40f),
            OcrLine("HP 110", 30f, 40f, left = 450f),
            OcrLine("Pancada", 500f, 30f),
            OcrLine("Fraqueza x2", 710f, 20f),
            OcrLine("Resistência -30", 710f, 20f),
            OcrLine("136", 780f, 20f),
            OcrLine("©2020 Pokémon", 808f, 10f),
        )
        assertEquals("136", OcrParser.parse(cardLines).collectorNumber)
    }

    @Test fun trims_leading_zeros_from_number() {
        assertEquals("006", OcrParser.parse(lines("Charizard ex", "006/131")).collectorNumber)
        assertEquals("6", OcrParser.parse(lines("Charizard", "06/131")).collectorNumber)
    }

    @Test fun extracts_promo_code_without_slash() {
        val r = OcrParser.parse(lines("Noctowl", "SWSH027"))
        assertNull(r.collectorNumber)
        assertEquals("SWSH027", r.promoCode)
    }

    @Test fun ignores_hp_without_space_as_promo() {
        val r = OcrParser.parse(lines("Furret", "HP110"))
        assertNull(r.promoCode)
        assertEquals(110, r.hp)
    }

    @Test fun picks_topmost_large_text_as_name_and_drops_noise() {
        val r = OcrParser.parse(lines("BASIC", "Furret", "HP 110", "Pancada de Cauda"))
        assertEquals("Furret", r.name)
    }

    @Test fun extracts_hp() {
        assertEquals(110, OcrParser.parse(lines("Furret", "HP 110")).hp)
    }

    @Test fun returns_nulls_on_empty_input() {
        val r = OcrParser.parse(emptyList())
        assertNull(r.name); assertNull(r.collectorNumber); assertNull(r.promoCode); assertNull(r.hp)
    }

    @Test fun keeps_raw_text() {
        assertTrue(OcrParser.parse(lines("Furret", "136/189")).rawText.contains("136/189"))
    }

    @Test fun keeps_accented_name_intact() {
        assertEquals("Escuridão", OcrParser.parse(lines("Escuridão", "136/189")).name)
    }

    @Test fun ignores_purely_numeric_line_for_name() {
        val r = OcrParser.parse(
            listOf(
                OcrLine("Furret", 0f, 40f),
                OcrLine("136/189", 100f, 40f),
                OcrLine("40", 200f, 80f),
            ),
        )
        assertEquals("Furret", r.name)
    }

    @Test fun removes_stage_prefix_from_name() {
        assertEquals("Butterfree", OcrParser.parse(lines("GASICO Butterfree", "HP 190")).name)
        assertEquals("Steenee", OcrParser.parse(lines("ESTÁGIOL Steenee", "HP 90")).name)
        assertEquals("Vikavolt V", OcrParser.parse(lines("ASICOVikavolt V", "HP 200")).name)
    }

    @Test fun restores_space_before_suffix() {
        assertEquals("Charizard V", OcrParser.parse(lines("CharizardV", "HP 220")).name)
        assertEquals("Mew V", OcrParser.parse(lines("MewV", "HP 180")).name)
        assertEquals("Arbok ex", OcrParser.parse(lines("Arbokex", "HP 270")).name)
    }

    @Test fun recovers_gx_suffix_from_x() {
        assertEquals("Decidueye GX", OcrParser.parse(lines("DecidueyeX", "HP 240")).name)
        assertEquals("Lapras GX", OcrParser.parse(lines("Lapras X", "HP 190")).name)
    }

    @Test fun strips_trailing_hp_from_name() {
        assertEquals("Darmanitan de Galar", OcrParser.parse(lines("Darmanitan de Galar140")).name)
    }

    @Test fun applies_suffix_from_card_rule() {
        assertEquals("Centiskorch V", OcrParser.parse(lines("Centiskorch", "Regra V")).name)
        assertEquals("Charizard ex", OcrParser.parse(lines("Charizard e", "Regra dos Pokémon ex")).name)
        assertEquals("Venusaur EX", OcrParser.parse(lines("Venusaur", "Regra do Pokémon-EX")).name)
    }

    @Test fun ignores_hp_even_with_letters_like_ps_or_pl() {
        val bounsweet = listOf(
            OcrLine("Bounsweet", 34f, 31f, left = 113f),
            OcrLine("Ps60", 38f, 36f, left = 461f),
        )
        assertEquals("Bounsweet", OcrParser.parse(bounsweet).name)

        val torkoal = listOf(
            OcrLine("Torkoal", 32f, 31f, left = 110f),
            OcrLine("Pl10", 39f, 36f, left = 447f),
        )
        assertEquals("Torkoal", OcrParser.parse(torkoal).name)
    }

    @Test fun ignores_attack_and_ability_text_in_lower_part_of_card() {
        val bulbasaur = listOf(
            OcrLine("Bulbasaur", 35f, 28f),
            OcrLine("Semente Sanguessuga 20", 505f, 43f),
        )
        assertEquals("Bulbasaur", OcrParser.parse(bulbasaur).name)

        val surskit = listOf(
            OcrLine("Surskit", 35f, 28f),
            OcrLine("Ataque Rápido", 508f, 34f),
        )
        assertEquals("Surskit", OcrParser.parse(surskit).name)
    }

    @Test fun picks_name_by_largest_font_when_card_does_not_fill_frame() {
        // Real field photo: the card sits lower in the frame, so the name is far below 25% of the
        // image bottom and the old header band dropped it. Heights mirror the name being the
        // largest text on the card.
        val frogadier = listOf(
            OcrLine("grant a", 40f, 20f),
            OcrLine("mands", 70f, 20f),
            OcrLine("~/Projects/ TCG|", 100f, 25f),
            OcrLine("ESTÁGIO ,", 360f, 30f),
            OcrLine("Frogadier", 400f, 55f),
            OcrLine("Evoluide Eroakie", 470f, 30f),
            OcrLine("Ilust. Nelno", 520f, 25f),
            OcrLine("N0657 Fokémon Sapo Bolha Altura. 0,6 m Peso, 10,9 kg", 560f, 30f),
            OcrLine("Invoca-jitsu", 640f, 30f),
            OcrLine("Aqua Gume", 690f, 30f),
            OcrLine("100", 740f, 40f),
            OcrLine("Recuo", 780f, 25f),
            OcrLine("50", 800f, 30f),
            OcrLine("CH", 820f, 20f),
            OcrLine("S", 840f, 20f),
            OcrLine("O Pokémon Ativo do seu oponente agora está Confuso.", 860f, 20f),
            OcrLine("021/086", 960f, 25f),
        )
        val r = OcrParser.parse(frogadier)
        assertTrue(r.name?.contains("Frogadier") == true, "name should contain Frogadier but was '${r.name}'")
        assertEquals("021", r.collectorNumber)
        assertEquals(86, r.setTotal)
    }

    @Test fun fixes_beedrill_letter_l_uppercase_i() {
        assertEquals("Beedrill", OcrParser.parse(lines("BeedrilI")).name)
    }

    @Test fun zubat_card_reads_name_and_number() {
        val r = OcrParser.parse(
            listOf(
                OcrLine("BÁSICO", 0f, 20f),
                OcrLine("Zubat", 100f, 60f),
                OcrLine("N 0041 Pokdmon, Morsego Altura 0,8 m Peso 75 kg", 200f, 20f),
                OcrLine("lust. Mekoyw", 300f, 20f),
                OcrLine("Supersônico", 400f, 20f),
                OcrLine("O Pokémon Ativo do seu oponente agora está Confuso.", 500f, 20f),
                OcrLine("Fraquesa Ox2", 600f, 20f),
                OcrLine("Resistência (C-30", 700f, 20f),
                OcrLine("Rim 049/086", 800f, 20f),
                OcrLine("Emite", 900f, 20f),
                OcrLine("40", 1000f, 80f),
                OcrLine("Recuo", 1100f, 20f),
            ),
        )
        assertEquals("Zubat", r.name)
        assertEquals("049", r.collectorNumber)
    }

    @Test fun removes_stage_prefix_variations_with_ocr_noise() {
        assertEquals("Celebi V", OcrParser.parse(lines("eASICO Celebi V", "HP 180")).name)
        assertEquals("Victini V", OcrParser.parse(lines("eAsICo) Victini V", "HP 190")).name)
        assertEquals("Banette", OcrParser.parse(lines("eSTAGIOD Banette", "HP 100")).name)
        assertEquals("Banette", OcrParser.parse(lines("D Banette", "HP 100")).name)
    }

    @Test fun recovers_vmax_suffix_from_rule_or_stage() {
        val kyurem = listOf(
            OcrLine("VMAX", 30f, 25f),
            OcrLine("KyuremVx", 30f, 50f),
            OcrLine("Evolui de Kyurem V", 80f, 15f),
            OcrLine("Regra VMAX Quando seu Pokémon VMAX é Nocauteado", 750f, 20f),
        )
        assertEquals("Kyurem VMAX", OcrParser.parse(kyurem).name)
    }

    @Test fun recovers_gx_suffix_from_tag_team_or_attack() {
        val celebiVenusaur = listOf(
            OcrLine("BÁSICO", 40f, 15f),
            OcrLine("Celebi e Venusaur", 35f, 40f),
            OcrLine("ALIADOS", 90f, 17f),
            OcrLine("Perene GX", 570f, 25f),
            OcrLine("Quando seus ALIADOS são Nocauteados, seu oponente pega 3 cartas de Prêmio.", 750f, 15f),
        )
        assertEquals("Celebi e Venusaur GX", OcrParser.parse(celebiVenusaur).name)
    }

    @Test fun recovers_ex_suffix_from_ek_typo() {
        val banette = listOf(
            OcrLine("Banetteek", 30f, 40f),
            OcrLine("Regra dos Pokémon ex", 740f, 15f),
        )
        assertEquals("Banette ex", OcrParser.parse(banette).name)
    }

    @Test fun recovers_collector_number_when_one_fuses_with_slash() {
        val lines = listOf(
            OcrLine("Oddish", 35f, 30f),
            OcrLine("o0/196", 776f, 20f),
        )
        assertEquals("001", OcrParser.parse(lines).collectorNumber)
    }

    @Test fun recovers_fused_collector_number_without_slash_in_footer() {
        val lines = listOf(
            OcrLine("Aspirador Perdido", 60f, 40f),
            OcrLine("217196 *", 776f, 22f),
            OcrLine("©2022 Pokémon", 808f, 10f),
        )
        assertEquals("217", OcrParser.parse(lines).collectorNumber)
    }

    @Test fun ignores_mid_card_attack_damage_for_footer_number() {
        val lines = listOf(
            OcrLine("Oddish", 35f, 30f),
            OcrLine("Aríete", 533f, 24f),
            OcrLine("30", 534f, 24f, left = 520f),
            OcrLine("Fraqueza x2", 713f, 15f),
            OcrLine("Recuo", 716f, 10f),
            OcrLine("©2022 Pokémon", 808f, 10f),
        )
        assertNull(OcrParser.parse(lines).collectorNumber)
    }
}

