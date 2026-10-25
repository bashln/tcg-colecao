package app.tcg.app

import android.app.Application
import app.tcg.app.data.prices.PriceRefreshWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TcgApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        PriceRefreshWorker.enqueuePeriodic(this)
    }
}
