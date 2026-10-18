package app.tcg.app.data.backup

import app.tcg.app.data.local.CardEntity
import app.tcg.app.data.local.CardPriceEntity
import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource
import java.math.BigDecimal
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val FORMAT = "tcg-colecao-web-1"
private const val MAX_BYTES = 50 * 1024 * 1024
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private val CARD_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{1,80}")
private val ITEM_ID = Regex("[a-zA-Z0-9-]{8,80}")
private val JSON = Json { isLenient = false }

data class WebMoney(val amount: Double, val currency: Currency)
data class WebQuote(
    val amount: Double,
    val currency: Currency,
    val source: PriceSource,
    val variant: CardVariant,
    val updatedAt: String?,
)
data class WebCard(
    val id: String,
    val name: String,
    val setId: String,
    val setName: String,
    val collectorNumber: String,
    val imageUrl: String?,
    val variants: List<CardVariant>,
    val prices: List<WebQuote>,
    val setTotal: Int?,
)
data class WebItem(
    val id: String,
    val cardId: String,
    val quantity: Int,
    val variant: CardVariant,
    val condition: Condition,
    val pricePaid: WebMoney?,
    val addedAt: Long,
    val revision: Long,
) {
    val configurationKey: String get() = configurationKey(cardId, variant, condition)
}
data class WebEntry(val item: WebItem, val card: WebCard)

class ValidatedWebBackup internal constructor(
    val exportedAt: String,
    val entries: List<WebEntry>,
)

class InvalidBackupException(message: String) : IllegalArgumentException(message)

object WebBackupAdapter {
    fun parse(bytes: ByteArray): ValidatedWebBackup {
        invalid(bytes.size <= MAX_BYTES, "O arquivo excede 50 MiB")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw InvalidBackupException("O arquivo não está em UTF-8 válido")
        }
        val root = try {
            JSON.parseToJsonElement(text).objectWith("format", "exportedAt", "entries")
        } catch (error: InvalidBackupException) {
            throw error
        } catch (_: Exception) {
            throw InvalidBackupException("O arquivo não contém JSON válido")
        }
        invalid(root.getValue("format").string() == FORMAT, "Formato de cópia não reconhecido")
        val exportedAt = root.getValue("exportedAt").string()
        invalid(runCatching { Instant.parse(exportedAt) }.isSuccess, "Data de exportação inválida")
        val entries = root.getValue("entries").array()
        invalid(entries.size <= 10_000, "A cópia tem mais de 10.000 itens")
        val itemIds = HashSet<String>()
        val configurations = HashSet<String>()
        val cards = HashMap<String, WebCard>()
        val parsed = entries.map { raw ->
            val entry = raw.objectWith("item", "card")
            val item = parseItem(entry.getValue("item"))
            val card = parseCard(entry.getValue("card"))
            invalid(item.cardId == card.id, "Referência de carta inválida")
            invalid(itemIds.add(item.id), "A cópia contém IDs de posse repetidos")
            invalid(configurations.add(item.configurationKey), "A cópia contém configurações repetidas")
            invalid(cards.putIfAbsent(card.id, card) in listOf(null, card), "A cópia contém cartas inconsistentes")
            WebEntry(item, card)
        }
        return ValidatedWebBackup(exportedAt, parsed)
    }

    fun encode(entries: List<WebEntry>, exportedAt: String = Instant.now().toString()): ByteArray {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("exportedAt", exportedAt)
            put("entries", buildJsonArray {
                entries.forEach { entry ->
                    add(buildJsonObject {
                        put("item", entry.item.toJson())
                        put("card", entry.card.toJson())
                    })
                }
            })
        }
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        parse(bytes)
        return bytes
    }

    fun fromRoom(
        item: CollectionItemEntity,
        card: CardEntity,
        prices: List<CardPriceEntity>,
    ): WebEntry = WebEntry(
        item = WebItem(
            item.id, item.cardId, item.quantity, item.variant, item.condition,
            item.pricePaidAmount?.let { WebMoney(it, Currency.valueOf(requireNotNull(item.pricePaidCurrency))) },
            item.addedAt, item.revision,
        ),
        card = WebCard(
            card.id, card.name, card.setId, card.setName, card.collectorNumber,
            card.imageUrl, card.variants.toList(),
            prices.mapNotNull { price ->
                price.market?.let { WebQuote(it, price.currency, price.source, price.variant, price.updatedAt) }
            },
            card.setTotal,
        ),
    )

    fun itemSignature(item: WebItem): String = item.toJson().toString()

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun parseItem(raw: JsonElement): WebItem {
    val value = raw.objectWith("id", "cardId", "quantity", "variant", "condition", "pricePaid", "addedAt", "revision", "configurationKey")
    val id = value.getValue("id").string()
    val cardId = value.getValue("cardId").string()
    invalid(ITEM_ID.matches(id), "ID de posse inválido")
    invalid(CARD_ID.matches(cardId), "ID de carta inválido")
    val quantity = value.getValue("quantity").integer(1, 100_000).toInt()
    val variant = value.getValue("variant").enum<CardVariant>()
    val condition = value.getValue("condition").enum<Condition>()
    val paid = value.getValue("pricePaid").takeUnless { it == JsonNull }?.let(::parseMoney)
    val addedAt = value.getValue("addedAt").integer(0, MAX_SAFE_INTEGER)
    val revision = value.getValue("revision").integer(1, MAX_SAFE_INTEGER)
    invalid(value.getValue("configurationKey").string() == configurationKey(cardId, variant, condition), "Configuração inválida")
    return WebItem(id, cardId, quantity, variant, condition, paid, addedAt, revision)
}

private fun parseCard(raw: JsonElement): WebCard {
    val value = raw.objectWith("id", "name", "setId", "setName", "collectorNumber", "imageUrl", "variants", "prices", "setTotal")
    val id = value.getValue("id").string()
    invalid(CARD_ID.matches(id), "ID de carta inválido")
    val imageUrl = value.getValue("imageUrl").takeUnless { it == JsonNull }?.string()
    if (imageUrl != null) invalid(runCatching { URI(imageUrl).let { it.scheme != null && it.host != null } }.getOrDefault(false), "URL de imagem inválida")
    val variants = value.getValue("variants").array().map { it.enum<CardVariant>() }
    val prices = value.getValue("prices").array().map(::parseQuote)
    invalid(prices.map { it.source to it.variant }.toSet().size == prices.size, "Cotações com chaves repetidas")
    val total = value.getValue("setTotal").takeUnless { it == JsonNull }?.integer(0, Int.MAX_VALUE.toLong())?.toInt()
    return WebCard(
        id, value.getValue("name").string(), value.getValue("setId").string(),
        value.getValue("setName").string(), value.getValue("collectorNumber").string(),
        imageUrl, variants, prices, total,
    )
}

private fun parseQuote(raw: JsonElement): WebQuote {
    val value = raw.objectWith("amount", "currency", "source", "variant", "updatedAt")
    return WebQuote(
        value.getValue("amount").nonnegativeFinite(), value.getValue("currency").enum(),
        value.getValue("source").enum(), value.getValue("variant").enum(),
        value.getValue("updatedAt").takeUnless { it == JsonNull }?.string(),
    )
}

private fun parseMoney(raw: JsonElement): WebMoney {
    val value = raw.objectWith("amount", "currency")
    return WebMoney(value.getValue("amount").nonnegativeFinite(), value.getValue("currency").enum())
}

private fun WebItem.toJson() = buildJsonObject {
    put("id", id)
    put("cardId", cardId)
    put("quantity", quantity)
    put("variant", variant.name)
    put("condition", condition.name)
    put("pricePaid", pricePaid?.toJson() ?: JsonNull)
    put("addedAt", addedAt)
    put("revision", revision)
    put("configurationKey", configurationKey)
}

private fun WebCard.toJson() = buildJsonObject {
    put("id", id)
    put("name", name)
    put("setId", setId)
    put("setName", setName)
    put("collectorNumber", collectorNumber)
    put("imageUrl", imageUrl?.let(::JsonPrimitive) ?: JsonNull)
    put("variants", buildJsonArray { variants.forEach { add(JsonPrimitive(it.name)) } })
    put("prices", buildJsonArray { prices.forEach { add(it.toJson()) } })
    put("setTotal", setTotal?.let(::JsonPrimitive) ?: JsonNull)
}

private fun WebQuote.toJson() = buildJsonObject {
    put("amount", amount)
    put("currency", currency.name)
    put("source", source.name)
    put("variant", variant.name)
    put("updatedAt", updatedAt?.let(::JsonPrimitive) ?: JsonNull)
}

private fun WebMoney.toJson() = buildJsonObject {
    put("amount", amount)
    put("currency", currency.name)
}

private fun JsonElement.objectWith(vararg keys: String): JsonObject {
    val value = this as? JsonObject ?: throw InvalidBackupException("Objeto JSON esperado")
    invalid(value.keys == keys.toSet(), "Campos inesperados na cópia")
    return value
}

private fun JsonElement.array(): JsonArray = this as? JsonArray ?: throw InvalidBackupException("Lista JSON esperada")

private fun JsonElement.string(): String {
    val value = this as? JsonPrimitive ?: throw InvalidBackupException("Texto JSON esperado")
    invalid(value.isString, "Texto JSON esperado")
    return value.content
}

private inline fun <reified T : Enum<T>> JsonElement.enum(): T =
    enumValues<T>().firstOrNull { it.name == string() } ?: throw InvalidBackupException("Valor não reconhecido")

private fun JsonElement.integer(min: Long, max: Long): Long {
    val value = this as? JsonPrimitive ?: throw InvalidBackupException("Número inteiro esperado")
    invalid(!value.isString, "Número inteiro esperado")
    val number = value.content.toBigDecimalOrNull() ?: throw InvalidBackupException("Número inválido")
    val exact = try { number.longValueExact() } catch (_: ArithmeticException) { throw InvalidBackupException("Número inteiro fora do limite") }
    invalid(exact in min..max, "Número inteiro fora do limite")
    return exact
}

private fun JsonElement.nonnegativeFinite(): Double {
    val value = this as? JsonPrimitive ?: throw InvalidBackupException("Número esperado")
    invalid(!value.isString, "Número esperado")
    val number = value.content.toDoubleOrNull() ?: throw InvalidBackupException("Número inválido")
    invalid(number.isFinite() && number >= 0, "Número fora do limite")
    return number
}

private fun configurationKey(cardId: String, variant: CardVariant, condition: Condition) =
    "$cardId\u001f${variant.name}\u001f${condition.name}"

private fun invalid(condition: Boolean, message: String) {
    if (!condition) throw InvalidBackupException(message)
}
