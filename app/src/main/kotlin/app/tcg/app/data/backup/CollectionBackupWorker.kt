package app.tcg.app.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupWorkerEntryPoint {
    fun backupManager(): LocalBackupManager
}

class CollectionBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            BackupWorkerEntryPoint::class.java,
        )
        return when (entryPoint.backupManager().runBackup()) {
            is BackupExecutionResult.Success, is BackupExecutionResult.NoOp -> Result.success()
            is BackupExecutionResult.Failure -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "tcg_collection_backup"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<CollectionBackupWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
