package app.tcg.core.scan

import app.tcg.core.domain.model.OcrLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcrParserTest {
    private fun lines(vararg t: String) = t.mapIndexed { i, s -> OcrLine(s, i * 100f, 40f) }

    private fun line(text: String, top: Float, left: Float, height: Float = 22f) =
        OcrLine(text, top, height, left, 300f)

    // Read off the two 30th-anniversary Pikachu photos. The yellow stamp prints a second
    // "NN/TT" pair right of the collector number, and OCR can return it as its own line.
    @Test fun promo_stamp_number_does_not_override_the_collector_number() {
        val photo = listOf(
            line("BÁSICO", 180f, 220f),
            line("Pikachu", 175f, 330f, 54f),
            line("PS 80", 180f, 900f, 40f),
            line("Soneca", 880f, 420f, 40f),
            line("05/30", 1270f, 620f),
            line("027/128", 1270f, 380f),
        )

        val parsed = OcrParser.parse(photo)

        assertEquals("027", parsed.collectorNumber)
        assertEquals(128, parsed.setTotal)
    }

    @Test fun promo_stamp_number_is_ignored_when_it_reads_first() {
        val photo = listOf(
            line("Pikachu", 180f, 340f, 54f),
            line("Armazenar", 860f, 400f, 40f),
            line("26/30", 1265f, 640f),
            line("048/128", 1265f, 360f),
        )

        val parsed = OcrParser.parse(photo)

        assertEquals("048", parsed.collectorNumber)
        assertEquals(128, parsed.setTotal)
    }

    // Straight from the device: a PT-BR Finizen (me05-021) whose "BÁSICO" stage label came
    // back as "nASICo". The label sat above the name and inside the size band, so it won the
    // name slot, the TCGdex name query returned nothing, and ten unrelated "42" cards were
    // offered instead.
    @Test fun mixed_case_stage_label_does_not_beat_the_card_name() {
        val photo = listOf(
            line("nASICo", 150f, 200f, 34f),
            line("Finizen", 186f, 260f, 52f),
            line("Barbatana Drenante", 520f, 210f, 34f),
            line("Cure 20 pontos de dano deste Pokémon", 600f, 210f, 28f),
            line("raquea 42", 1210f, 320f, 26f),
            line("20", 170f, 900f, 40f),
        )

        assertEquals("Finizen", OcrParser.parse(photo).name)
    }

    @Test fun letter_swapped_stage_label_does_not_beat_the_card_name() {
        val photo = listOf(
            line("nASICo", 150f, 200f, 34f),
            line("Finizen", 186f, 260f, 52f),
        )

        assertEquals("Finizen", OcrParser.parse(photo).name)
    }

    @Test fun uppercase_stage_label_does_not_beat_the_card_name() {
        val photo = listOf(
            line("BASICO", 150f, 200f, 34f),
            line("Finizen", 186f, 260f, 52f),
        )

        assertEquals("Finizen", OcrParser.parse(photo).name)
    }

    @Test fun title_case_stage_label_does_not_beat_the_card_name() {
        val photo = listOf(
            line("Básico", 150f, 200f, 34f),
            line("Finizen", 186f, 260f, 52f),
        )

        assertEquals("Finizen", OcrParser.parse(photo).name)
    }

    @Test fun digit_swapped_stage_label_does_not_beat_the_card_name() {
        val photo = listOf(
            line("B4SICO", 150f, 200f, 34f),
            line("Finizen", 186f, 260f, 52f),
        )

        assertEquals("Finizen", OcrParser.parse(photo).name)
    }

    @Test fun digit_swapped_stage_two_label_does_not_beat_the_card_name() {
        val photo = listOf(
            line("E5TAGIO1", 150f, 200f, 34f),
            line("Finizen", 186f, 260f, 52f),
        )

        assertEquals("Finizen", OcrParser.parse(photo).name)
    }

    // The label check tolerates one edit from a short label, so real names must stay outside
    // that radius or they would be dropped as labels instead of read as the name.
    @Test fun real_card_names_are_not_stage_labels() {
        listOf("Finizen", "Pikachu", "Furret", "Charizard", "Bulbasaur").forEach { name ->
            assertEquals(name, OcrParser.parse(lines(name, "136/189")).name, "'$name' is a card name")
        }
    }

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

    @Test fun removes_merged_stage_label_from_card_name_line() {
        assertEquals(
            "Meowth da Equipe Rocket",
            OcrParser.parse(lines("CASS Meowth da Equipe Rocket")).name,
        )
        assertEquals(
            "Meowth da Equipe Rocket",
            OcrParser.parse(lines("GASMeowth da Equipe Rocket")).name,
        )
        assertEquals(
            "MEOWTH da Equipe Rocket",
            OcrParser.parse(lines("MEOWTH da Equipe Rocket")).name,
        )
        assertEquals(
            "Meowth da Equipe Rocket",
            OcrParser.parse(lines("gasMeowth da Equipe Rocket")).name,
        )
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

    // Device case: Vulpix 019/132. The OCR read the name and the attack, and the parser returned
    // the attack ("Estouro"). One of the two filters that can drop a name line is pinned here.
    @Test fun a_letter_only_line_in_the_right_third_is_not_an_hp_value() {
        val lines = listOf(
            line("Vulpix", 30f, 420f, 31f),
            line("Estouro", 505f, 120f, 36f),
        )

        assertEquals("Vulpix", OcrParser.parse(lines).name)
    }

    @Test fun a_bare_hp_label_above_the_name_is_not_the_name() {
        val lines = listOf(
            line("HP", 25f, 460f, 20f),
            line("Vulpix", 35f, 110f, 31f),
        )

        assertEquals("Vulpix", OcrParser.parse(lines).name)
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

    // Device case: basic energy card. The energy symbol between the words is read as a lone "O",
    // which leaves "Energia O Básica"; the catalog has "Energia Básica".
    @Test fun the_energy_symbol_read_as_a_lone_letter_is_not_part_of_the_name() {
        val lines = listOf(
            line("Energia O Básica", 30f, 200f, 40f),
            line("Energia Básica", 80f, 200f, 30f),
        )

        assertEquals("Energia Básica", OcrParser.parse(lines).name)
    }
}
