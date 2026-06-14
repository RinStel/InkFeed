package dev.rinstel.inkfeed.article.cache

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.rinstel.inkfeed.core.database.InkFeedDatabase
import dev.rinstel.inkfeed.core.util.AppSettings
import java.util.concurrent.TimeUnit

class CacheCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val database = InkFeedDatabase(applicationContext)
        return try {
            CacheCleaner(database).clean(AppSettings(applicationContext).cacheDays)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            database.close()
        }
    }

    companion object {
        private const val WORK_NAME = "inkfeed-cache-cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
