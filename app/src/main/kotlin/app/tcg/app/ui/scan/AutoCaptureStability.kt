package app.tcg.app.ui.scan

internal class AutoCaptureStability(
    private val stableForMillis: Long = 2_000,
) {
    private var identity: String? = null
    private var sinceMillis: Long = 0
    private var emitted = false

    @Synchronized
    fun observe(candidate: String?, nowMillis: Long): String? {
        if (candidate == null) {
            reset()
            return null
        }
        val previousIdentity = identity
        if (previousIdentity == null || !sameLikelyIdentity(previousIdentity, candidate) || nowMillis < sinceMillis) {
            identity = candidate
            sinceMillis = nowMillis
            emitted = false
            return null
        }
        if (!emitted && nowMillis - sinceMillis >= stableForMillis) {
            emitted = true
            return candidate
        }
        return null
    }

    @Synchronized
    fun reset() {
        identity = null
        sinceMillis = 0
        emitted = false
    }

    private fun sameLikelyIdentity(first: String, next: String): Boolean {
        val left = first.filter(Char::isLetter).lowercase()
        val right = next.filter(Char::isLetter).lowercase()
        if (left == right) return true
        if (minOf(left.length, right.length) < MIN_IDENTITY_LENGTH) {
            // Short names (Mew, Onix) carry little redundancy, so a single OCR slip must not
            // restart the window; require at most one edit and a comparable length.
            return maxOf(left.length, right.length) - minOf(left.length, right.length) <= 1 &&
                isWithinOneEdit(left, right)
        }

        var longest = 0
        var previous = IntArray(right.length + 1)
        for (leftChar in left) {
            val current = IntArray(right.length + 1)
            for (index in right.indices) {
                if (leftChar == right[index]) {
                    current[index + 1] = previous[index] + 1
                    longest = maxOf(longest, current[index + 1])
                } else {
                    current[index + 1] = maxOf(previous[index + 1], current[index])
                }
            }
            previous = current
        }
        return longest >= MIN_IDENTITY_LENGTH && longest.toFloat() / minOf(left.length, right.length) >= MIN_SHARED_IDENTITY_RATIO
    }

    /** True when [first] and [second] differ by at most one insert, delete, or substitution. */
    private fun isWithinOneEdit(first: String, second: String): Boolean {
        if (first.length > second.length) return isWithinOneEdit(second, first)
        if (second.length - first.length > 1) return false

        var index = 0
        while (index < first.length && first[index] == second[index]) index++
        if (index == first.length) return true

        if (first.length == second.length) {
            return first.substring(index + 1) == second.substring(index + 1)
        }
        return first.substring(index) == second.substring(index + 1)
    }

    private companion object {
        const val MIN_IDENTITY_LENGTH = 6
        const val MIN_SHARED_IDENTITY_RATIO = 0.6f
    }
}
