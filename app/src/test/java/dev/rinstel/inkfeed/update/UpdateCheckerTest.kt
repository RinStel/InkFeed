package dev.rinstel.inkfeed.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun comparesSemanticVersionTags() {
        assertTrue(UpdateChecker.isNewer("0.1.2", "0.1.1"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("0.1.10", "0.1.9"))
        assertFalse(UpdateChecker.isNewer("0.1.1", "0.1.1"))
        assertFalse(UpdateChecker.isNewer("0.1", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.1"))
    }

    @Test
    fun normalizesCommonReleaseTagPrefixes() {
        assertTrue(UpdateChecker.isNewer(UpdateChecker.normalizeVersion("v0.2.0"), "0.1.9"))
        assertTrue(UpdateChecker.isNewer(UpdateChecker.normalizeVersion("release-0.2.0"), "0.1.9"))
    }

    @Test
    fun prefersReleaseApkAsset() {
        val assets = listOf(
            "app-debug.apk" to "https://example.com/debug.apk",
            "app-release.apk" to "https://example.com/release.apk"
        )

        assertEquals(
            "https://example.com/release.apk",
            UpdateChecker.selectDownloadUrl(assets, "https://example.com/release")
        )
    }

    @Test
    fun fallsBackToReleasePageWhenNoApkAssetExists() {
        assertEquals(
            "https://example.com/release",
            UpdateChecker.selectDownloadUrl(emptyList(), "https://example.com/release")
        )
    }
}
