package dev.rinstel.inkfeed.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class BeijingTimeTest {
    @Test
    fun usesBeijingDateBoundary() {
        val instant = Instant.parse("2026-06-14T16:00:00Z")

        assertEquals("2026-06-15", BeijingTime.date(instant))
        assertEquals("2026-06-15 00:00", BeijingTime.dateTime(instant.toEpochMilli()))
        assertEquals(instant.toEpochMilli(), BeijingTime.startOfDayMillis(instant))
    }

    @Test
    fun computesStartOfDayForLaterInstant() {
        val instant = Instant.parse("2026-06-15T07:30:00Z")

        assertEquals(
            Instant.parse("2026-06-14T16:00:00Z").toEpochMilli(),
            BeijingTime.startOfDayMillis(instant)
        )
    }
}
