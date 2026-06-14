package dev.rinstel.inkfeed.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkFeedDatabaseMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migratesVersion3ContentAndAssetsToVersion5() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DATABASE_NAME), null).use { db ->
            db.execSQL(
                """CREATE TABLE articles (
                    id INTEGER PRIMARY KEY,
                    content_text TEXT,
                    content_html TEXT
                )"""
            )
            db.execSQL(
                """CREATE TABLE article_assets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    article_id INTEGER NOT NULL,
                    original_url TEXT NOT NULL,
                    local_path TEXT NOT NULL,
                    epub_path TEXT NOT NULL,
                    mime_type TEXT,
                    width INTEGER,
                    height INTEGER
                )"""
            )
            db.execSQL(
                "INSERT INTO articles(id, content_text, content_html) VALUES (1, 'text', '<p>text</p>')"
            )
            db.execSQL(
                "INSERT INTO articles(id, content_text, content_html) VALUES (2, 'summary', '')"
            )
            repeat(2) {
                db.execSQL(
                    """INSERT INTO article_assets(
                        article_id, original_url, local_path, epub_path
                    ) VALUES (1, 'https://example.com/image.jpg', '/tmp/image.jpg', 'images/1.jpg')"""
                )
            }
            db.version = 3
        }

        InkFeedDatabase(context).use { helper ->
            val db = helper.writableDatabase
            assertEquals(5, db.version)
            db.rawQuery(
                "SELECT content_fetched FROM articles ORDER BY id",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertTrue(cursor.moveToNext())
                assertEquals(0, cursor.getInt(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM article_assets", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.rawQuery(
                "SELECT COUNT(*) FROM pragma_index_list('article_assets') WHERE name = ?",
                arrayOf("article_asset_path")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "inkfeed.db"
    }
}
