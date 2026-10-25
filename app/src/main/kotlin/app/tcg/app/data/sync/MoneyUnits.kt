package app.tcg.app.data.sync

import kotlin.math.abs

private const val MINOR_UNITS_PER_MAJOR = 100.0
private const val MAX_SAFE_MINOR_UNITS = 9_007_199_254_740_991L
private const val SCALE_TOLERANCE = 1e-7

/**
 * The one money conversion of the sync contract: major units in, minor units out.
 * Every encoder and decoder uses it, so both platforms emit the same bytes.
 *
 * `java.lang.Math.round` rounds halves away from zero; `kotlin.math.round` is
 * `Math.rint` and rounds ties to even, which breaks the maximum safe price.
 */
internal fun toMinorUnits(amount: Double): Long {
    if (!amount.isFinite()) throw SupabaseSyncException(SyncErrorKind.UNAVAILABLE, "Preço pago inválido")
    if (amount < 0) throw SupabaseSyncException(SyncErrorKind.UNAVAILABLE, "Preço pago não pode ser negativo")
    val scaled = amount * MINOR_UNITS_PER_MAJOR
    val rounded = Math.round(scaled)
    if (abs(scaled - rounded) > SCALE_TOLERANCE) {
        throw SupabaseSyncException(SyncErrorKind.UNAVAILABLE, "Preço pago precisa ter no máximo duas casas decimais")
    }
    if (abs(rounded) > MAX_SAFE_MINOR_UNITS) {
        throw SupabaseSyncException(SyncErrorKind.UNAVAILABLE, "Preço pago excede o limite seguro")
    }
    return rounded
}

/** The inverse of [toMinorUnits]. */
internal fun fromMinorUnits(minor: Long): Double = minor / MINOR_UNITS_PER_MAJOR
