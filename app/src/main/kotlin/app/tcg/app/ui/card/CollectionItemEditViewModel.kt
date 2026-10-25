package app.tcg.app.ui.card

import androidx.lifecycle.ViewModel
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.CollectionItemChanges
import app.tcg.core.domain.model.CollectionUpdate
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface CollectionItemEditState {
    data object Loading : CollectionItemEditState
    data object Missing : CollectionItemEditState
    data object FailedToLoad : CollectionItemEditState
    data class Ready(
        val original: CollectionItem,
        val card: Card?,
        val quantity: Int,
        val variant: CardVariant,
        val condition: Condition,
        val pricePaidAmount: String,
        val pricePaidCurrency: Currency,
        val saving: Boolean = false,
        val removing: Boolean = false,
        val error: CollectionItemEditError? = null,
    ) : CollectionItemEditState {
        val pricePaidIsValid: Boolean
            get() = pricePaidAmount.isBlank() || parsedPrice() != null

        private fun parsedPrice(): Double? = pricePaidAmount.trim()
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }

        fun changes() = CollectionItemChanges(
            quantity = quantity,
            variant = variant,
            condition = condition,
            pricePaid = parsedPrice()?.let { Money(it, pricePaidCurrency) },
        )
    }
}

enum class CollectionItemEditError {
    DUPLICATE_CONFIGURATION,
    CHANGED_SINCE_LOADED,
    SAVE_FAILED,
    REMOVE_FAILED,
}

class CollectionItemEditViewModel(
    private val itemId: String,
    private val collection: CollectionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<CollectionItemEditState>(CollectionItemEditState.Loading)
    val state: StateFlow<CollectionItemEditState> = _state.asStateFlow()

    suspend fun load() {
        _state.value = CollectionItemEditState.Loading
        _state.value = try {
            val details = collection.getById(itemId)
            if (details == null) {
                CollectionItemEditState.Missing
            } else {
                CollectionItemEditState.Ready(
                    original = details.item,
                    card = details.card,
                    quantity = details.item.quantity,
                    variant = details.item.variant,
                    condition = details.item.condition,
                    pricePaidAmount = details.item.pricePaid?.amount?.toString().orEmpty(),
                    pricePaidCurrency = details.item.pricePaid?.currency ?: Currency.USD,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CollectionItemEditState.FailedToLoad
        }
    }

    fun selectVariant(variant: CardVariant) = updateReady { copy(variant = variant, error = null) }

    fun selectCondition(condition: Condition) = updateReady { copy(condition = condition, error = null) }

    fun setQuantity(quantity: Int) = updateReady { copy(quantity = quantity.coerceAtLeast(1), error = null) }

    fun setPricePaidAmount(amount: String) = updateReady { copy(pricePaidAmount = amount, error = null) }

    fun selectPricePaidCurrency(currency: Currency) = updateReady { copy(pricePaidCurrency = currency, error = null) }

    suspend fun save(): Boolean {
        val ready = _state.value as? CollectionItemEditState.Ready ?: return false
        if (ready.saving || !ready.pricePaidIsValid) return false
        _state.value = ready.copy(saving = true, error = null)
        val result = try {
            collection.update(ready.original, ready.changes())
        } catch (e: CancellationException) {
            _state.value = ready.copy(saving = false)
            throw e
        } catch (_: Exception) {
            _state.value = ready.copy(saving = false, error = CollectionItemEditError.SAVE_FAILED)
            return false
        }

        return when (result) {
            is CollectionUpdate.Updated -> true
            CollectionUpdate.NotFound -> {
                _state.value = CollectionItemEditState.Missing
                false
            }
            CollectionUpdate.ChangedSinceLoaded -> {
                _state.value = ready.copy(saving = false, error = CollectionItemEditError.CHANGED_SINCE_LOADED)
                false
            }
            CollectionUpdate.DuplicateConfiguration -> {
                _state.value = ready.copy(saving = false, error = CollectionItemEditError.DUPLICATE_CONFIGURATION)
                false
            }
        }
    }

    suspend fun remove(): Boolean {
        val ready = _state.value as? CollectionItemEditState.Ready ?: return false
        if (ready.removing) return false
        _state.value = ready.copy(removing = true, error = null)
        return try {
            collection.remove(itemId)
            true
        } catch (e: CancellationException) {
            _state.value = ready.copy(removing = false)
            throw e
        } catch (_: Exception) {
            _state.value = ready.copy(removing = false, error = CollectionItemEditError.REMOVE_FAILED)
            false
        }
    }

    private inline fun updateReady(transform: CollectionItemEditState.Ready.() -> CollectionItemEditState.Ready) {
        val ready = _state.value as? CollectionItemEditState.Ready ?: return
        _state.value = ready.transform()
    }
}
