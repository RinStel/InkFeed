package dev.rinstel.inkfeed.article.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageProcessorTest {
    @Test
    fun keepsSmallImagesAtNativeResolution() {
        assertEquals(1, imageSampleSize(1200, 1600))
    }

    @Test
    fun samplesLargeImagesBeforeDecoding() {
        assertEquals(4, imageSampleSize(8000, 6000))
    }
}
