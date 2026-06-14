package dev.rinstel.inkfeed

import org.junit.Assert.assertEquals
import org.junit.Test

class PagePositionsTest {
    @Test
    fun mergesTinyTrailingRemainderWithoutMakingContentUnreachable() {
        assertEquals(
            listOf(0, 825),
            calculatePagePositions(
                anchors = listOf(810),
                pageHeight = 800,
                maxScroll = 825,
                minimumLastPage = 160
            )
        )
    }

    @Test
    fun keepsSubstantialLastPage() {
        assertEquals(
            listOf(0, 800, 1200),
            calculatePagePositions(
                anchors = listOf(800),
                pageHeight = 800,
                maxScroll = 1200,
                minimumLastPage = 160
            )
        )
    }

    @Test
    fun neverSkipsContentToReachALaterAnchor() {
        assertEquals(
            listOf(0, 800, 1000),
            calculatePagePositions(
                anchors = listOf(1000),
                pageHeight = 800,
                maxScroll = 1000,
                minimumLastPage = 160
            )
        )
    }
}
