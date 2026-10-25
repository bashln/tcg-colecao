package app.tcg.app.ui.components

import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money

data class PricePaidDraft(
    val amount: String = "",
    val currency: Currency = Currency.USD,
) {
    val isValid: Boolean
        get() = amount.isBlank() || parsedAmount() != null

    fun toMoney(): Money? = parsedAmount()?.let { Money(it, currency) }

    private fun parsedAmount(): Double? = amount.trim()
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }
}
