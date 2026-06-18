package dev.rinstel.inkfeed.core.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import dev.rinstel.inkfeed.core.model.Article
import dev.rinstel.inkfeed.core.model.ArticleAsset
import dev.rinstel.inkfeed.core.model.FeedItem
import dev.rinstel.inkfeed.core.model.Source

class InkFeedDatabase(context: Context) :
    SQLiteOpenHelper(context, "inkfeed.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sources (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                feed_url TEXT NOT NULL UNIQUE,
                site_url TEXT,
                group_name TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                last_sync_at INTEGER,
                last_sync_result TEXT
            )"""
        )
        db.execSQL(
            """CREATE TABLE articles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                guid TEXT,
                author TEXT,
                published_at INTEGER,
                summary TEXT,
                content_text TEXT,
                content_html TEXT,
                content_fetched INTEGER NOT NULL DEFAULT 0,
                reading_minutes INTEGER NOT NULL DEFAULT 1,
                is_read INTEGER NOT NULL DEFAULT 0,
                is_starred INTEGER NOT NULL DEFAULT 0,
                cached_at INTEGER,
                synced_at INTEGER,
                added_to_daily INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE UNIQUE INDEX article_guid_source ON articles(source_id, guid) WHERE guid IS NOT NULL")
        db.execSQL("CREATE UNIQUE INDEX article_url ON articles(url)")
        db.execSQL("CREATE INDEX article_date ON articles(published_at DESC)")
        db.execSQL(
            """CREATE TABLE article_assets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
                original_url TEXT NOT NULL,
                local_path TEXT NOT NULL,
                epub_path TEXT NOT NULL,
                mime_type TEXT,
                width INTEGER,
                height INTEGER
            )"""
        )
        db.execSQL(
            "CREATE UNIQUE INDEX article_asset_path ON article_assets(article_id, epub_path)"
        )
        db.execSQL("CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE article_tags (article_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY(article_id, tag_id))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE article_assets ADD COLUMN epub_path TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE articles ADD COLUMN synced_at INTEGER")
            db.execSQL("UPDATE articles SET synced_at = cached_at WHERE synced_at IS NULL")
        }
        if (oldVersion < 4) {
            db.execSQL(
                "ALTER TABLE articles ADD COLUMN content_fetched INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                """UPDATE articles SET content_fetched =
                   CASE WHEN TRIM(COALESCE(content_html, '')) <> '' THEN 1 ELSE 0 END"""
            )
        }
        if (oldVersion < 5) {
            db.execSQL(
                """DELETE FROM article_assets
                   WHERE id NOT IN (
                       SELECT MIN(id) FROM article_assets GROUP BY article_id, epub_path
                   )"""
            )
            db.execSQL(
                "CREATE UNIQUE INDEX article_asset_path ON article_assets(article_id, epub_path)"
            )
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun addSource(title: String, feedUrl: String, siteUrl: String? = null, group: String? = null): Long {
        val values = ContentValues().apply {
            put("title", title)
            put("feed_url", feedUrl)
            put("site_url", siteUrl)
            put("group_name", group)
        }
        return writableDatabase.insertWithOnConflict(
            "sources", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun sources(enabledOnly: Boolean = false): List<Source> {
        val where = if (enabledOnly) " WHERE enabled = 1" else ""
        return readableDatabase.rawQuery(
            "SELECT * FROM sources$where ORDER BY title COLLATE NOCASE", null
        ).use { cursor -> cursor.map(::sourceFrom) }
    }

    fun source(id: Long): Source? = readableDatabase.rawQuery(
        "SELECT * FROM sources WHERE id = ?", arrayOf(id.toString())
    ).use { cursor -> if (cursor.moveToFirst()) sourceFrom(cursor) else null }

    fun setSourceEnabled(id: Long, enabled: Boolean) {
        writableDatabase.update(
            "sources",
            ContentValues().apply { put("enabled", if (enabled) 1 else 0) },
            "id = ?",
            arrayOf(id.toString())
        )
    }

    fun deleteSource(id: Long): List<String> {
        val paths = readableDatabase.rawQuery(
            """SELECT aa.local_path FROM article_assets aa
               JOIN articles a ON a.id = aa.article_id
               WHERE a.source_id = ?""",
            arrayOf(id.toString())
        ).use { cursor -> cursor.map { it.string("local_path") } }
        writableDatabase.delete("sources", "id = ?", arrayOf(id.toString()))
        return paths
    }

    fun updateSourceMetadata(id: Long, title: String?, siteUrl: String?) {
        val values = ContentValues()
        if (!title.isNullOrBlank()) values.put("title", title)
        if (!siteUrl.isNullOrBlank()) values.put("site_url", siteUrl)
        if (values.size() > 0) {
            writableDatabase.update("sources", values, "id = ?", arrayOf(id.toString()))
        }
    }

    fun recordSync(id: Long, result: String) {
        writableDatabase.update(
            "sources",
            ContentValues().apply {
                put("last_sync_at", System.currentTimeMillis())
                put("last_sync_result", result)
            },
            "id = ?",
            arrayOf(id.toString())
        )
    }

    fun insertArticle(sourceId: Long, item: FeedItem, contentText: String, contentHtml: String): Long {
        val existingId = touchExistingArticle(sourceId, item)
        if (existingId != null) {
            return -1
        }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("source_id", sourceId)
            put("title", item.title)
            put("url", item.url)
            put("guid", item.guid)
            put("author", item.author)
            put("published_at", item.publishedAt)
            put("summary", item.summary)
            put("content_text", contentText)
            put("content_html", contentHtml)
            put("content_fetched", 0)
            put("reading_minutes", maxOf(1, contentText.length / 500))
            put("cached_at", now)
            put("synced_at", now)
        }
        return writableDatabase.insertWithOnConflict(
            "articles", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun touchExistingArticle(sourceId: Long, item: FeedItem): Long? {
        val existingId = findArticleId(sourceId, item) ?: return null
        writableDatabase.update(
            "articles",
            ContentValues().apply {
                item.summary?.let { put("summary", it) }
                item.publishedAt?.let { put("published_at", it) }
                if (!item.author.isNullOrBlank()) put("author", item.author)
            },
            "id = ?",
            arrayOf(existingId.toString())
        )
        return existingId
    }

    fun articleNeedsContent(articleId: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT content_fetched FROM articles WHERE id = ?",
            arrayOf(articleId.toString())
        ).use { cursor ->
            cursor.moveToFirst() && shouldFetchArticleContent(cursor.getInt(0) == 1)
        }

    private fun findArticleId(sourceId: Long, item: FeedItem): Long? {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        item.guid?.takeIf { it.isNotBlank() }?.let {
            clauses += "(source_id = ? AND guid = ?)"
            args += sourceId.toString()
            args += it
        }
        clauses += "url = ?"
        args += item.url
        if (item.publishedAt != null) {
            clauses += "(source_id = ? AND title = ? AND published_at = ?)"
            args += sourceId.toString()
            args += item.title
            args += item.publishedAt.toString()
        }
        return readableDatabase.rawQuery(
            "SELECT id FROM articles WHERE ${clauses.joinToString(" OR ")} LIMIT 1",
            args.toTypedArray()
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    fun articles(
        starredOnly: Boolean = false,
        sourceId: Long? = null,
        syncedAfter: Long? = null,
        limit: Int = 100,
        orderByRecentSync: Boolean = false
    ): List<Article> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (starredOnly) clauses += "a.is_starred = 1"
        sourceId?.let {
            clauses += "a.source_id = ?"
            args += it.toString()
        }
        syncedAfter?.let {
            clauses += "COALESCE(a.synced_at, a.cached_at, 0) >= ?"
            args += it.toString()
        }
        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        args += limit.toString()
        val orderBy = if (orderByRecentSync) {
            "ORDER BY COALESCE(a.synced_at, a.cached_at, 0) DESC, a.id DESC"
        } else {
            "ORDER BY COALESCE(a.published_at, a.cached_at) DESC"
        }
        return readableDatabase.rawQuery(
            """SELECT a.*, s.title AS source_title FROM articles a
               JOIN sources s ON s.id = a.source_id
               $where $orderBy LIMIT ?""",
            args.toTypedArray()
        ).use { cursor -> cursor.map(::articleFrom) }
    }

    fun todayArticles(dayStartMillis: Long, limit: Int = 100): List<Article> {
        val sourceIds = sources()
            .filter { (it.lastSyncAt ?: 0L) >= dayStartMillis }
            .map(Source::id)
            .toSet()
        val rows = sourceIds.asSequence()
            .flatMap { sourceId -> articles(sourceId = sourceId, limit = 500).asSequence() }
            .distinctBy(Article::id)
            .filter { article ->
                (article.syncedAt ?: article.cachedAt ?: 0L) >= dayStartMillis ||
                    (article.publishedAt ?: 0L) >= dayStartMillis
            }
            .sortedWith(
                compareByDescending<Article> { it.syncedAt ?: it.cachedAt ?: 0L }
                    .thenByDescending { it.publishedAt ?: it.cachedAt ?: 0L }
                    .thenByDescending { it.id }
            )
            .take(limit)
            .toList()
        Log.d("InkFeed", "todayArticles(query): dayStart=$dayStartMillis sources=$sourceIds rows=${rows.size}")
        return rows
    }

    fun expiredArticleAssets(cutoff: Long): List<String> =
        readableDatabase.rawQuery(
            """SELECT aa.local_path FROM article_assets aa
               JOIN articles a ON a.id = aa.article_id
               WHERE a.is_starred = 0 AND COALESCE(a.cached_at, 0) < ?""",
            arrayOf(cutoff.toString())
        ).use { cursor ->
            cursor.map { it.string("local_path") }
        }

    fun deleteExpiredArticles(cutoff: Long): Int =
        writableDatabase.delete(
            "articles",
            "is_starred = 0 AND COALESCE(cached_at, 0) < ?",
            arrayOf(cutoff.toString())
        )

    fun setStarred(articleId: Long, starred: Boolean) {
        writableDatabase.update(
            "articles",
            ContentValues().apply { put("is_starred", if (starred) 1 else 0) },
            "id = ?",
            arrayOf(articleId.toString())
        )
    }

    fun setRead(articleId: Long, read: Boolean) {
        writableDatabase.update(
            "articles",
            ContentValues().apply { put("is_read", if (read) 1 else 0) },
            "id = ?",
            arrayOf(articleId.toString())
        )
    }

    fun updateArticleContent(articleId: Long, contentText: String, contentHtml: String) {
        writableDatabase.update(
            "articles",
            ContentValues().apply {
                put("content_text", contentText)
                put("content_html", contentHtml)
                put("content_fetched", 1)
                put("reading_minutes", maxOf(1, contentText.length / 500))
            },
            "id = ?",
            arrayOf(articleId.toString())
        )
    }

    fun addAsset(
        articleId: Long,
        originalUrl: String,
        localPath: String,
        epubPath: String,
        width: Int,
        height: Int
    ) {
        writableDatabase.insertWithOnConflict(
            "article_assets",
            null,
            ContentValues().apply {
                put("article_id", articleId)
                put("original_url", originalUrl)
                put("local_path", localPath)
                put("epub_path", epubPath)
                put("mime_type", "image/jpeg")
                put("width", width)
                put("height", height)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun assets(articleIds: List<Long>): List<ArticleAsset> {
        if (articleIds.isEmpty()) return emptyList()
        val placeholders = articleIds.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT * FROM article_assets WHERE article_id IN ($placeholders) ORDER BY id",
            articleIds.map(Long::toString).toTypedArray()
        ).use { cursor ->
            cursor.map { c ->
                ArticleAsset(
                    id = c.long("id"),
                    articleId = c.long("article_id"),
                    originalUrl = c.string("original_url"),
                    localPath = c.string("local_path"),
                    epubPath = c.string("epub_path"),
                    mimeType = c.nullableString("mime_type") ?: "image/jpeg",
                    width = c.int("width"),
                    height = c.int("height")
                )
            }
        }
    }

    fun markPackaged(ids: List<Long>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "UPDATE articles SET added_to_daily = 1 WHERE id IN ($placeholders)",
            ids.toTypedArray()
        )
    }

    private fun sourceFrom(c: Cursor) = Source(
        id = c.long("id"),
        title = c.string("title"),
        feedUrl = c.string("feed_url"),
        siteUrl = c.nullableString("site_url"),
        groupName = c.nullableString("group_name"),
        enabled = c.int("enabled") == 1,
        lastSyncAt = c.nullableLong("last_sync_at"),
        lastSyncResult = c.nullableString("last_sync_result")
    )

    private fun articleFrom(c: Cursor) = Article(
        id = c.long("id"),
        sourceId = c.long("source_id"),
        sourceTitle = c.string("source_title"),
        title = c.string("title"),
        url = c.string("url"),
        guid = c.nullableString("guid"),
        author = c.nullableString("author"),
        publishedAt = c.nullableLong("published_at"),
        summary = c.nullableString("summary"),
        contentText = c.nullableString("content_text"),
        contentHtml = c.nullableString("content_html"),
        readingMinutes = c.int("reading_minutes"),
        isRead = c.int("is_read") == 1,
        isStarred = c.int("is_starred") == 1,
        cachedAt = c.nullableLong("cached_at"),
        syncedAt = c.nullableLong("synced_at"),
        addedToDailyPackage = c.int("added_to_daily") == 1
    )

    private fun <T> Cursor.map(transform: (Cursor) -> T): List<T> = buildList {
        while (moveToNext()) add(transform(this@map))
    }

    private fun Cursor.index(name: String) = getColumnIndexOrThrow(name)
    private fun Cursor.string(name: String) = getString(index(name))
    private fun Cursor.nullableString(name: String) =
        index(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String) = getLong(index(name))
    private fun Cursor.nullableLong(name: String) =
        index(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(name: String) = getInt(index(name))
}

internal fun shouldFetchArticleContent(contentFetched: Boolean): Boolean = !contentFetched
