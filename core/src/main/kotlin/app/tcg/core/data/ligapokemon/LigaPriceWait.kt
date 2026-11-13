package app.tcg.core.data.ligapokemon

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reading a price means loading ligapokemon.com.br through a WebView, and the site answers with a
 * Cloudflare challenge first. A real device took ~40 s to clear it, so a short retry budget fails
 * the user while the challenge is still being solved. The deadline below leaves room above that,
 * and [VERIFYING_AFTER_MS] is when the screen stops claiming to be reading and says what it thinks
 * is happening.
 */
object LigaPriceWait {
    const val TIMEOUT_MS = 75_000L
    const val RETRY_DELAY_MS = 1_500L
    const val VERIFYING_AFTER_MS = 20_000L

    /** A single read is a WebView round trip; a dead renderer must not stall the whole wait. */
    const val READ_TIMEOUT_MS = 2_000L
}

enum class LigaPricePhase { READING, VERIFYING, TIMED_OUT }

/**
 * Polls [read] until it yields a value or [timeoutMs] elapses. Returns null only when the deadline
 * passes, so a caller never reports failure while the page may still deliver a quote. Each read is
 * bounded by [readTimeoutMs] because a WebView that stops answering would otherwise never return.
 */
suspend fun <T : Any> awaitLigaPrice(
    timeoutMs: Long = LigaPriceWait.TIMEOUT_MS,
    retryDelayMs: Long = LigaPriceWait.RETRY_DELAY_MS,
    verifyingAfterMs: Long = LigaPriceWait.VERIFYING_AFTER_MS,
    readTimeoutMs: Long = LigaPriceWait.READ_TIMEOUT_MS,
    onPhase: (LigaPricePhase) -> Unit = {},
    read: suspend () -> T?,
): T? {
    require(timeoutMs > 0 && retryDelayMs > 0 && readTimeoutMs > 0) {
        "awaitLigaPrice needs positive budgets to terminate"
    }
    var waited = 0L
    var phase = LigaPricePhase.READING
    onPhase(phase)
    while (true) {
        withTimeoutOrNull(readTimeoutMs) { read() }?.let { return it }
        if (waited >= timeoutMs) {
            onPhase(LigaPricePhase.TIMED_OUT)
            return null
        }
        delay(retryDelayMs)
        waited += retryDelayMs
        val next = if (waited >= verifyingAfterMs) LigaPricePhase.VERIFYING else LigaPricePhase.READING
        if (next != phase) {
            phase = next
            onPhase(phase)
        }
    }
}
