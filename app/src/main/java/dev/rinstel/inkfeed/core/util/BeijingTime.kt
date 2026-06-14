package dev.rinstel.inkfeed.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object BeijingTime {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun startOfDayMillis(now: Instant = Instant.now()): Long =
        LocalDate.ofInstant(now, zone)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    fun date(now: Instant = Instant.now()): String =
        DateTimeFormatter.ISO_LOCAL_DATE.format(now.atZone(zone))

    fun dateTime(epochMillis: Long): String =
        dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}
