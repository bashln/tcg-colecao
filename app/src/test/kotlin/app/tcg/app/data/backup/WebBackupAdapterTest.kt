package app.tcg.app.data.backup

import app.tcg.app.data.local.CardEntity
import app.tcg.app.data.local.CardPriceEntity
import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebBackupAdapterTest {
    private val validJson = """
        {
          "format": "tcg-colecao-web-1",
          "exportedAt": "2026-10-10T21:00:00.000Z",
          "entries": [
            {
              "item": {
                "id": "00000000-0000-4000-8000-000000000001",
                "cardId": "base1-4",
                "quantity": 2,
                "variant": "NORMAL",
                "condition": "NEAR_MINT",
                "pricePaid": { "amount": 8.5, "currency": "USD" },
                "addedAt": 1791667200000,
                "revision": 1,
                "configurationKey": "base1-4\u001fNORMAL\u001fNEAR_MINT"
              },
              "card": {
                "id": "base1-4",
                "name": "Charizard",
                "setId": "base1",
                "setName": "Base Set",
                "collectorNumber": "4",
                "imageUrl": "https://example.com/base1-4.png",
                "variants": ["NORMAL"],
                "prices": [
                  {
                    "amount": 150.0,
                    "currency": "USD",
                    "source": "TCGPLAYER",
                    "variant": "NORMAL",
                    "updatedAt": "2026-10-10T20:00:00Z"
                  }
                ],
                "setTotal": 102
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parse_valid_backup_and_roundtrips() {
        val backup = WebBackupAdapter.parse(validJson.toByteArray(Charsets.UTF_8))
        assertEquals("2026-10-10T21:00:00.000Z", backup.exportedAt)
        assertEquals(1, backup.entries.size)

        val entry = backup.entries.first()
        assertEquals("00000000-0000-4000-8000-000000000001", entry.item.id)
        assertEquals("base1-4", entry.item.cardId)
        assertEquals(2, entry.item.quantity)
        assertEquals(CardVariant.NORMAL, entry.item.variant)
        assertEquals(Condition.NEAR_MINT, entry.item.condition)
        assertNotNull(entry.item.pricePaid)
        assertEquals(8.5, entry.item.pricePaid!!.amount, 0.001)
        assertEquals(Currency.USD, entry.item.pricePaid!!.currency)
        assertEquals(1791667200000L, entry.item.addedAt)
        assertEquals(1L, entry.item.revision)
        assertEquals("base1-4\u001fNORMAL\u001fNEAR_MINT", entry.item.configurationKey)

        assertEquals("base1-4", entry.card.id)
        assertEquals("Charizard", entry.card.name)
        assertEquals("https://example.com/base1-4.png", entry.card.imageUrl)
        assertEquals(1, entry.card.prices.size)
        assertEquals(102, entry.card.setTotal)

        val encoded = WebBackupAdapter.encode(backup.entries, backup.exportedAt)
        val reparsed = WebBackupAdapter.parse(encoded)
        assertEquals(backup.exportedAt, reparsed.exportedAt)
        assertEquals(backup.entries.size, reparsed.entries.size)
        assertEquals(entry.item.id, reparsed.entries.first().item.id)
        assertEquals(entry.card.name, reparsed.entries.first().card.name)
    }

    @Test
    fun fromRoom_maps_all_portable_fields() {
        val item = CollectionItemEntity(
            id = "00000000-0000-4000-8000-000000000001",
            cardId = "base1-4",
            quantity = 2,
            variant = CardVariant.NORMAL,
            condition = Condition.NEAR_MINT,
            pricePaidAmount = 8.5,
            pricePaidCurrency = "USD",
            addedAt = 1791667200000L,
            revision = 1L,
        )
        val card = CardEntity(
            id = "base1-4",
            name = "Charizard",
            setId = "base1",
            setName = "Base Set",
            collectorNumber = "4",
            rarity = "Rare Holo",
            hp = 120,
            imageUrl = "https://example.com/base1-4.png",
            types = listOf("Fire"),
            attacks = emptyList(),
            variants = setOf(CardVariant.NORMAL),
            setTotal = 102,
        )
        val price = CardPriceEntity(
            cardId = "base1-4",
            source = PriceSource.TCGPLAYER,
            variant = CardVariant.NORMAL,
            currency = Currency.USD,
            market = 150.0,
            low = null,
            mid = null,
            high = null,
            trend = null,
            avg1 = null,
            avg7 = null,
            avg30 = null,
            updatedAt = "2026-10-10T20:00:00Z",
        )
        val entry = WebBackupAdapter.fromRoom(item, card, listOf(price))
        assertEquals(item.id, entry.item.id)
        assertEquals(item.cardId, entry.item.cardId)
        assertEquals(card.name, entry.card.name)
        assertEquals(1, entry.card.prices.size)
        assertEquals(150.0, entry.card.prices.first().amount, 0.001)

        val signature = WebBackupAdapter.itemSignature(entry.item)
        assertTrue(signature.contains(item.id))
        assertTrue(signature.contains("base1-4"))
    }

    @Test
    fun rejects_oversized_payload() {
        val oversized = ByteArray(50 * 1024 * 1024 + 1)
        val ex = assertThrows(InvalidBackupException::class.java) {
            WebBackupAdapter.parse(oversized)
        }
        assertEquals("O arquivo excede 50 MiB", ex.message)
    }

    @Test
    fun rejects_non_utf8_bytes() {
        val badBytes = byteArrayOf(0xC0.toByte(), 0xAF.toByte())
        val ex = assertThrows(InvalidBackupException::class.java) {
            WebBackupAdapter.parse(badBytes)
        }
        assertEquals("O arquivo não está em UTF-8 válido", ex.message)
    }

    @Test
    fun rejects_invalid_json() {
        val ex = assertThrows(InvalidBackupException::class.java) {
            WebBackupAdapter.parse("not json".toByteArray())
        }
        assertEquals("O arquivo não contém JSON válido", ex.message)
    }

    @Test
    fun rejects_unrecognized_format() {
        val bad = validJson.replace("\"tcg-colecao-web-1\"", "\"unknown-format\"")
        val ex = assertThrows(InvalidBackupException::class.java) {
            WebBackupAdapter.parse(bad.toByteArray())
        }
        assertEquals("Formato de cópia não reconhecido", ex.message)
    }

    @Test
    fun rejects_invalid_exportedAt() {
        val bad = validJson.replace("\"2026-10-10T21:00:00.000Z\"", "\"not-a-date\"")
        val ex = assertThrows(InvalidBackupException::class.java) {
            WebBackupAdapter.parse(bad.toByteArray())
        }
        assertEquals("Data de exportação inválida", ex.message)
    }

    @Test
    fun rejects_invalid_card_reference() {
        val bad = validJson.replace("\"cardId\": \"base1-4\"", "\"cardId\": \"base1-5\"")
        val ex = assertThrows(InvalidBackupException::class.java) {
            WebBackupAdapter.parse(bad.toByteArray())
        }
        assertEquals("Configuração inválida", ex.message)
    }

    @Test
    fun rejects_mismatched_card_id() {
        val bad = validJson.replace("\"id\": \"base1-4\"", "\"id\": \"base1-5\"")
        val ex = assertThrows(InvalidBackupException::class.java) {
            WebBackupAdapter.parse(bad.toByteArray())
        }
        assertEquals("Referência de carta inválida", ex.message)
    }

    @Test
    fun rejects_duplicate_item_id() {
        val entry = """
            {
              "item": {
                "id": "00000000-0000-4000-8000-000000000001",
                "cardId": "base1-4",
                "quantity": 2,
                "variant": "NORMAL",
                "condition": "NEAR_MINT",
                "pricePaid": null,
                "addedAt": 1791667200000,
                "revision": 1,
                "configurationKey": "base1-4\u001fNORMAL\u001fNEAR_MINT"
              },
              "card": {
                "id": "base1-4",
                "name": "Charizard",
                "setId": "base1",
                "setName": "Base Set",
                "collectorNumber": "4",
                "imageUrl": null,
                "variants": ["NORMAL"],
                "prices": [],
                "setTotal": 102
              }
            }
        """.trimIndent()
        val json = """
            {
              "format": "tcg-colecao-web-1",
              "exportedAt": "2026-10-10T21:00:00.000Z",
              "entries": [$entry, $entry]
            }
        """.trimIndent()
        val ex = assertThrows(InvalidBackupException::class.java) {
            WebBackupAdapter.parse(json.toByteArray())
        }
        assertEquals("A cópia contém IDs de posse repetidos", ex.message)
    }

    @Test
    fun rejects_duplicate_configuration() {
        val entry1 = """
            {
              "item": {
                "id": "00000000-0000-4000-8000-000000000001",
                "cardId": "base1-4",
                "quantity": 2,
                "variant": "NORMAL",
                "condition": "NEAR_MINT",
                "pricePaid": null,
                "addedAt": 1791667200000,
                "revision": 1,
                "configurationKey": "base1-4\u001fNORMAL\u001fNEAR_MINT"
              },
              "card": {
                "id": "base1-4",
                "name": "Charizard",
                "setId": "base1",
                "setName": "Base Set",
                "collectorNumber": "4",
                "imageUrl": null,
                "variants": ["NORMAL"],
                "prices": [],
                "setTotal": 102
              }
            }
        """.trimIndent()
        val entry2 = """
            {
              "item": {
                "id": "00000000-0000-4000-8000-000000000002",
                "cardId": "base1-4",
                "quantity": 1,
                "variant": "NORMAL",
                "condition": "NEAR_MINT",
                "pricePaid": null,
                "addedAt": 1791667200000,
                "revision": 1,
                "configurationKey": "base1-4\u001fNORMAL\u001fNEAR_MINT"
              },
              "card": {
                "id": "base1-4",
                "name": "Charizard",
                "setId": "base1",
                "setName": "Base Set",
                "collectorNumber": "4",
                "imageUrl": null,
                "variants": ["NORMAL"],
                "prices": [],
                "setTotal": 102
              }
            }
        """.trimIndent()
        val json = """
            {
              "format": "tcg-colecao-web-1",
              "exportedAt": "2026-10-10T21:00:00.000Z",
              "entries": [$entry1, $entry2]
            }
        """.trimIndent()
        val ex = assertThrows(InvalidBackupException::class.java) {
            WebBackupAdapter.parse(json.toByteArray())
        }
        assertEquals("A cópia contém configurações repetidas", ex.message)
    }

    @Test
    fun rejects_inconsistent_card_metadata() {
        val entry1 = """
            {
              "item": {
                "id": "00000000-0000-4000-8000-000000000001",
                "cardId": "base1-4",
                "quantity": 2,
                "variant": "NORMAL",
                "condition": "NEAR_MINT",
                "pricePaid": null,
                "addedAt": 1791667200000,
                "revision": 1,
                "configurationKey": "base1-4\u001fNORMAL\u001fNEAR_MINT"
              },
              "card": {
                "id": "base1-4",
                "name": "Charizard",
                "setId": "base1",
                "setName": "Base Set",
                "collectorNumber": "4",
                "imageUrl": null,
                "variants": ["NORMAL"],
                "prices": [],
                "setTotal": 102
              }
            }
        """.trimIndent()
        val entry2 = """
            {
              "item": {
                "id": "00000000-0000-4000-8000-000000000002",
                "cardId": "base1-4",
                "quantity": 1,
                "variant": "HOLOFOIL",
                "condition": "NEAR_MINT",
                "pricePaid": null,
                "addedAt": 1791667200000,
                "revision": 1,
                "configurationKey": "base1-4\u001fHOLOFOIL\u001fNEAR_MINT"
              },
              "card": {
                "id": "base1-4",
                "name": "Charizard Inconsistent",
                "setId": "base1",
                "setName": "Base Set",
                "collectorNumber": "4",
                "imageUrl": null,
                "variants": ["NORMAL"],
                "prices": [],
                "setTotal": 102
              }
            }
        """.trimIndent()
        val json = """
            {
              "format": "tcg-colecao-web-1",
              "exportedAt": "2026-10-10T21:00:00.000Z",
              "entries": [$entry1, $entry2]
            }
        """.trimIndent()
        val ex = assertThrows(InvalidBackupException::class.java) {
            WebBackupAdapter.parse(json.toByteArray())
        }
        assertEquals("A cópia contém cartas inconsistentes", ex.message)
    }

    @Test
    fun rejects_unexpected_fields() {
        val bad = validJson.replace("\"format\": \"tcg-colecao-web-1\",", "\"format\": \"tcg-colecao-web-1\", \"extra\": 123,")
        val ex = assertThrows(InvalidBackupException::class.java) {
            WebBackupAdapter.parse(bad.toByteArray())
        }
        assertEquals("Campos inesperados na cópia", ex.message)
    }

    @Test
    fun rejects_negative_and_out_of_range_numbers() {
        val bad = validJson.replace("\"quantity\": 2", "\"quantity\": -1")
        assertThrows(InvalidBackupException::class.java) {
            WebBackupAdapter.parse(bad.toByteArray())
        }
    }
}
