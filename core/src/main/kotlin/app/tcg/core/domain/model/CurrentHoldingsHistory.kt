package app.tcg.core.domain.model

import java.time.LocalDate

data class QuoteCoverage(
    val pricedUnits: Int,
    val missingUnits: Int,
    val olderThanSevenDaysUnits: Int,
) {
    val totalUnits: Int get() = pricedUnits + missingUnits
}

data class HoldingsEstimate(
    val value: Money,
    val coverage: QuoteCoverage,
)

enum class HistoryContinuity { STARTS_SEGMENT, CONTINUES_SEGMENT }

data class RecordedHoldingsEstimate(
    val utcDay: LocalDate,
    val estimate: HoldingsEstimate,
    val continuity: HistoryContinuity,
)

data class CurrentHoldingsHistory(
    val source: PriceSource,
    val asOfUtcDay: LocalDate,
    val totalUnits: Int,
    val recorded: List<RecordedHoldingsEstimate>,
    val latest: HoldingsEstimate?,
)
