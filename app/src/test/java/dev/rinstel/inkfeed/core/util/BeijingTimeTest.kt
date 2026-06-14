package dev.rinstel.inkfeed.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BeijingTimeTest {
    @Test
    fun usesBeijingDateBoundary() {
        val instant = 1_781_452_800_000L

        assertEquals("2026-06-15", BeijingTime.date(instant))
        assertEquals("2026-06-15 00:00", BeijingTime.dateTime(instant))
        assertEquals(instant, BeijingTime.startOfDayMillis(instant))
    }

    @Test
    fun computesStartOfDayForLaterInstant() {
        val instant = 1_781_479_800_000L

        assertEquals(
            1_781_452_800_000L,
            BeijingTime.startOfDayMillis(instant)
        )
    }
}
