package app.tcg.app.data.preferences

import android.content.SharedPreferences
import app.tcg.core.domain.model.PriceSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPreferences(private val prefs: SharedPreferences) {

    private val _priceSource = MutableStateFlow(readPriceSource())
    val priceSource: StateFlow<PriceSource> = _priceSource.asStateFlow()

    fun setPriceSource(source: PriceSource) {
        prefs.edit().putString(KEY_PRICE_SOURCE, source.name).apply()
        _priceSource.value = source
    }

    private fun readPriceSource(): PriceSource {
        val stored = prefs.getString(KEY_PRICE_SOURCE, null) ?: return PriceSource.LIGAPOKEMON
        return try {
            PriceSource.valueOf(stored)
        } catch (_: Exception) {
            PriceSource.LIGAPOKEMON
        }
    }

    companion object {
        private const val KEY_PRICE_SOURCE = "selected_price_source"
    }
}
