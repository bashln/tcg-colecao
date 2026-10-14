package app.tcg.app.data.repository

import app.tcg.app.data.local.CurrentHoldingsPriceRow
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.HistoryContinuity
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentHoldingsHistoryTest {
    private val day1 = LocalDate.parse("2026-01-01")
    private val day2 = LocalDate.parse("2026-01-10")

    @Test
    fun prices_only_known_holdings_and_keeps_missing_units_in_coverage() {
        val history = projectCurrentHoldingsHistory(
            listOf(
                row("a", CardVariant.NORMAL, 2, day1, 3.0),
                row("a", CardVariant.NORMAL, 2, day2, 4.0),
                row("a", CardVariant.REVERSE_HOLOFOIL, 1, null, null),
                row("b", CardVariant.NORMAL, 3, null, null),
            ),
            PriceSource.TCGPLAYER,
            day2,
        )

        assertEquals(6, history.totalUnits)
        assertEquals(listOf(Money(6.0, Currency.USD), Money(8.0, Currency.USD)),
            history.recorded.map { it.estimate.value })
        assertEquals(2, history.latest?.coverage?.pricedUnits)
        assertEquals(4, history.latest?.coverage?.missingUnits)
        assertEquals(0, history.latest?.coverage?.olderThanSevenDaysUnits)
    }

    @Test
    fun carries_old_capture_forward_without_using_a_future_quote_backward() {
        val history = projectCurrentHoldingsHistory(
            listOf(
                row("a", CardVariant.NORMAL, 2, day1, 0.0),
                row("b", CardVariant.NORMAL, 1, day2, 5.0),
                row("b", CardVariant.NORMAL, 1, day2.plusDays(1), 9.0),
            ),
            PriceSource.CARDMARKET,
            day2,
        )

        assertEquals(listOf(Money(0.0, Currency.EUR), Money(5.0, Currency.EUR)),
            history.recorded.map { it.estimate.value })
        assertEquals(listOf(HistoryContinuity.STARTS_SEGMENT, HistoryContinuity.STARTS_SEGMENT),
            history.recorded.map { it.continuity })
        assertEquals(2, history.latest?.coverage?.olderThanSevenDaysUnits)
        assertEquals(0, history.latest?.coverage?.missingUnits)
    }

    @Test
    fun same_coverage_continues_segment_and_no_quotes_has_no_estimate() {
        val priced = projectCurrentHoldingsHistory(
            listOf(row("a", CardVariant.NORMAL, 1, day1, 2.0), row("a", CardVariant.NORMAL, 1, day2, 3.0)),
            PriceSource.TCGPLAYER,
            day2,
        )
        val unpriced = projectCurrentHoldingsHistory(
            listOf(row("a", CardVariant.NORMAL, 1, null, null)),
            PriceSource.TCGPLAYER,
            day2,
        )

        assertEquals(HistoryContinuity.CONTINUES_SEGMENT, priced.recorded.last().continuity)
        assertEquals(emptyList<Any>(), unpriced.recorded)
        assertNull(unpriced.latest)
    }

    @Test
    fun current_quantity_is_applied_to_every_recorded_date() {
        val currentQuantity = 4
        val history = projectCurrentHoldingsHistory(
            listOf(
                row("a", CardVariant.NORMAL, currentQuantity, day1, 2.0),
                row("a", CardVariant.NORMAL, currentQuantity, day2, 3.0),
            ),
            PriceSource.TCGPLAYER,
            day2,
        )

        assertEquals(currentQuantity, history.totalUnits)
        assertEquals(listOf(Money(8.0, Currency.USD), Money(12.0, Currency.USD)),
            history.recorded.map { it.estimate.value })
    }

    private fun row(
        cardId: String,
        variant: CardVariant,
        units: Int,
        day: LocalDate?,
        amount: Double?,
    ) = CurrentHoldingsPriceRow(cardId, variant, units, day, amount)
}
