# Investment statistics design

## Problem

`CollectionStats.compute()` combines purchase amounts without grouping their currencies, then subtracts them from the collection market value. The entry editor accepts USD and EUR, while Home and Profile calculate TCGplayer values in USD. Profit also uses the whole collection value even when some rows have no recorded purchase cost or market quote. Keep all saved purchase amounts visible, and make an unavailable comparison explicit.

## Usage

Home and Profile keep using the existing shared calculation:

```kotlin
val stats = CollectionStats.compute(entries, PriceSource.TCGPLAYER)
```

Profile receives one optional investment summary and renders the values by currency:

```kotlin
stats.investment?.let { investment ->
    InvestmentCard(investment)
}
```

The card handles the closed profit result:

```kotlin
when (val profit = investment.profit) {
    is ProfitResult.Available -> showMoney(profit.amount)
    is ProfitResult.Unavailable -> showReason(profit.reason)
}
```

## Shape

`core.domain.stats.CollectionStats` owns grouping and comparison policy. Replace `invested: Money?` and `profit: Money?` with:

```kotlin
data class InvestmentSummary(
    val investedByCurrency: List<Money>,
    val profit: ProfitResult,
)

sealed interface ProfitResult {
    data class Available(val amount: Money) : ProfitResult
    data class Unavailable(val reason: ProfitUnavailableReason) : ProfitResult
}

enum class ProfitUnavailableReason {
    INCOMPLETE_PURCHASE_RECORDS,
    MIXED_PURCHASE_CURRENCIES,
    PURCHASE_CURRENCY_DIFFERS_FROM_MARKET,
    MISSING_MARKET_QUOTES,
}
```

`CollectionStats.investment` is null only when no entry has a recorded purchase amount. Otherwise, the grouped list has one total per saved currency, in deterministic order. Profit is available only if every collection row has a purchase amount, all purchase amounts use the selected market currency, and every row has a matching non-null market quote for its variant and source. The first failing rule in the enum order is the explanation shown to the user. Do not infer or cache a currency conversion rate.

The current data contract stores one purchase amount on each configuration row. Existing web architecture documents that changing quantity does not recalculate that amount, and current aggregation sums it once per row. Preserve that arithmetic. This audit does not reinterpret the amount as a per-copy price.

Do not call a quote "current" based on presence alone. Profit uses the saved market quote the same way the existing portfolio total does. Quote age remains an explicit limitation until the app defines a freshness rule.

## Synthesis decision

Candidate C is the base because it requires complete purchase and quote coverage before showing collection-level profit. The cross-judge preferred C over B's labeled subset because the existing Profile UI calls the figure collection profit. Graft Candidate A's separate reasons for mixed purchase currencies and a single purchase currency that differs from the selected market source. All candidates and the judge inherited the session model, so model-family diversity was reduced.

## Tradeoffs accepted

- Keep every recorded purchase sum visible by currency, while withholding profit when any collection row makes a whole-collection comparison incomplete.
- Preserve the existing per-row pricePaid arithmetic until the product defines different quantity semantics.
- Use a single prioritized reason in the UI instead of showing multiple warnings at once.
- Keep cached quote age out of this change because no validated freshness threshold exists.

## Alternatives considered

- Candidate B compares only rows with a recorded purchase amount. It loses the current whole-collection meaning of Profile's profit label, even when it labels the subset.
- Keep one `Money?` value and return null for mixed currencies. This hides recorded amounts and makes the reason for unavailable profit ambiguous.
- Convert EUR purchases to USD in the UI. The app has no exchange-rate source or date policy.

## Implementation reconciliation

Implementation follows this sketch. The exact localized text and row layout stay in `ProfileScreen`; the calculation and unavailable reason remain in `core`.

## Open questions and risks

- A collection's total market value still sums missing quotes as zero. This separate issue can understate total value and needs a visible coverage signal.
- Present cached quotes may be old. The result is based on saved quotes, not a guaranteed live market value.

## Next implementation step

Replace the flat investment fields in the core aggregate and migrate Profile to render grouped currency totals plus the typed profit result.
