package dev.rinstel.inkfeed.article.cache

import dev.rinstel.inkfeed.core.database.InkFeedDatabase
import java.io.File
import java.util.concurrent.TimeUnit

data class CleanupResult(val articleCount: Int, val assetCount: Int)

class CacheCleaner(private val database: InkFeedDatabase) {
    fun clean(cacheDays: Int): CleanupResult {
        val cutoff = System.currentTimeMillis() -
            TimeUnit.DAYS.toMillis(cacheDays.coerceAtLeast(1).toLong())
        val assets = database.expiredArticleAssets(cutoff)
        assets.forEach { path ->
            runCatching {
                val file = File(path)
                file.delete()
                file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
            }
        }
        return CleanupResult(
            articleCount = database.deleteExpiredArticles(cutoff),
            assetCount = assets.size
        )
    }
}
