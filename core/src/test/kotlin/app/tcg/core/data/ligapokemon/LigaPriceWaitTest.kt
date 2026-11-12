package app.tcg.core.data.ligapokemon

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LigaPriceWaitTest {

    @Test
    fun returns_the_first_quote_without_waiting() = runTest {
        val quote = awaitLigaPrice(read = { "R\$ 0,49" })

        assertEquals("R\$ 0,49", quote)
        assertEquals(0, currentTime)
    }

    @Test
    fun keeps_polling_until_the_page_produces_a_quote() = runTest {
        var reads = 0

        val quote = awaitLigaPrice(retryDelayMs = 1_500L) {
            reads += 1
            if (reads <= 3) null else "R\$ 0,49"
        }

        assertEquals("R\$ 0,49", quote)
        assertEquals(4, reads)
        assertEquals(4_500, currentTime)
    }

    @Test
    fun reports_verifying_once_the_site_takes_longer_than_the_reading_window() = runTest {
        val phases = mutableListOf<LigaPricePhase>()

        awaitLigaPrice(
            timeoutMs = 10_000L,
            retryDelayMs = 1_000L,
            verifyingAfterMs = 3_000L,
            onPhase = { phases += it },
        ) { null }

        assertEquals(
            listOf(
                LigaPricePhase.READING,
                LigaPricePhase.VERIFYING,
                LigaPricePhase.TIMED_OUT,
            ),
            phases,
        )
    }

    @Test
    fun gives_up_at_the_deadline_and_says_so() = runTest {
        val phases = mutableListOf<LigaPricePhase>()

        val quote = awaitLigaPrice(
            timeoutMs = 6_000L,
            retryDelayMs = 1_500L,
            onPhase = { phases += it },
        ) { null }

        assertNull(quote)
        assertEquals(6_000, currentTime)
        assertTrue(phases.last() == LigaPricePhase.TIMED_OUT)
    }

    @Test
    fun the_default_deadline_covers_a_cloudflare_challenge() {
        assertTrue(
            LigaPriceWait.TIMEOUT_MS >= 60_000L,
            "A challenge took ~40 s on a real device; the deadline must leave room above that.",
        )
        assertTrue(LigaPriceWait.VERIFYING_AFTER_MS < LigaPriceWait.TIMEOUT_MS)
    }
}
