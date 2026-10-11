package app.tcg.core.domain

import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionItemChanges
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import org.junit.Assert.assertThrows
import org.junit.Test

class CollectionItemEditTest {

    @Test
    fun changes_require_positive_quantity_and_a_finite_nonnegative_paid_price() {
        assertThrows(IllegalArgumentException::class.java) {
            CollectionItemChanges(0, CardVariant.NORMAL, Condition.NEAR_MINT, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CollectionItemChanges(1, CardVariant.NORMAL, Condition.NEAR_MINT, Money(Double.NaN, Currency.USD))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CollectionItemChanges(1, CardVariant.NORMAL, Condition.NEAR_MINT, Money(-1.0, Currency.USD))
        }
    }
}
