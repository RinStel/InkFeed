package dev.rinstel.inkfeed.feed.sync

import dev.rinstel.inkfeed.core.database.shouldFetchArticleContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSemanticsTest {
    @Test
    fun incompleteDuplicateShouldRetryPageFetch() {
        assertTrue(shouldFetchArticleContent(contentFetched = false))
    }

    @Test
    fun completeDuplicateShouldSkipPageFetch() {
        assertFalse(shouldFetchArticleContent(contentFetched = true))
    }
}
