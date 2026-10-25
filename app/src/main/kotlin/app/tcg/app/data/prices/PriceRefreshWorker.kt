package app.tcg.app.data.prices

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PriceRefreshEntryPoint {
    fun priceRefresher(): PriceRefresher
}

class PriceRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            PriceRefreshEntryPoint::class.java,
        )
        try {
            entryPoint.priceRefresher().refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed run is not worth retrying sooner; the next daily run refreshes again.
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "tcg_price_refresh"

        fun enqueuePeriodic(context: Context) {
            // Scheduling is best-effort: launch must survive a process where the scheduler
            // provider has not run (JVM tests, restricted boots).
            val workManager = try {
                WorkManager.getInstance(context)
            } catch (_: IllegalStateException) {
                return
            }
            val request = PeriodicWorkRequestBuilder<PriceRefreshWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
