package dev.rinstel.inkfeed.core.model

data class Source(
    val id: Long,
    val title: String,
    val feedUrl: String,
    val siteUrl: String?,
    val groupName: String?,
    val enabled: Boolean,
    val lastSyncAt: Long?,
    val lastSyncResult: String?
)

data class Article(
    val id: Long,
    val sourceId: Long,
    val sourceTitle: String,
    val title: String,
    val url: String,
    val guid: String?,
    val author: String?,
    val publishedAt: Long?,
    val summary: String?,
    val contentText: String?,
    val contentHtml: String?,
    val readingMinutes: Int,
    val isRead: Boolean,
    val isStarred: Boolean,
    val cachedAt: Long?,
    val syncedAt: Long?,
    val addedToDailyPackage: Boolean
)

data class FeedItem(
    val title: String,
    val url: String,
    val guid: String?,
    val author: String?,
    val publishedAt: Long?,
    val summary: String?
)

data class ArticleAsset(
    val id: Long,
    val articleId: Long,
    val originalUrl: String,
    val localPath: String,
    val epubPath: String,
    val mimeType: String,
    val width: Int,
    val height: Int
)

enum class ImagePolicy(val label: String) {
    NONE("无图"),
    FIRST("仅首图"),
    ESSENTIAL("正文必要图片"),
    ALL("完整图片")
}
