package app.tcg.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test fun module_runs_tests() {
        assertEquals(2, Placeholder.one() + Placeholder.one())
    }
}
