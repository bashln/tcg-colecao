package app.tcg.app.ui.card

import androidx.lifecycle.ViewModel
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.valuation.Valuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CardDetailUiState(
    val card: Card,
    val variant: CardVariant,
    val condition: Condition = Condition.NEAR_MINT,
    val quantity: Int = 1,
    val tcgplayer: Money? = null,
    val cardmarket: Money? = null,
)

class CardDetailViewModel(
    card: Card,
    private val collection: CollectionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(stateFor(card, defaultVariant(card)))
    val state: StateFlow<CardDetailUiState> = _state.asStateFlow()

    fun selectVariant(variant: CardVariant) {
        _state.update {
            it.copy(
                variant = variant,
                tcgplayer = Valuation.itemValue(it.card, variant, PriceSource.TCGPLAYER),
                cardmarket = Valuation.itemValue(it.card, variant, PriceSource.CARDMARKET),
            )
        }
    }

    fun selectCondition(condition: Condition) {
        _state.update { it.copy(condition = condition) }
    }

    fun setQuantity(quantity: Int) {
        _state.update { it.copy(quantity = quantity.coerceAtLeast(1)) }
    }

    suspend fun save(variant: CardVariant, condition: Condition, quantity: Int) {
        _state.update {
            stateFor(it.card, variant).copy(condition = condition, quantity = quantity)
        }
        collection.add(
            card = _state.value.card,
            variant = variant,
            condition = condition,
            quantity = quantity,
            pricePaid = null,
        )
    }

    private companion object {
        fun stateFor(card: Card, variant: CardVariant) = CardDetailUiState(
            card = card,
            variant = variant,
            tcgplayer = Valuation.itemValue(card, variant, PriceSource.TCGPLAYER),
            cardmarket = Valuation.itemValue(card, variant, PriceSource.CARDMARKET),
        )

        fun defaultVariant(card: Card): CardVariant =
            if (CardVariant.NORMAL in card.variants) CardVariant.NORMAL
            else card.variants.firstOrNull() ?: CardVariant.NORMAL
    }
}
