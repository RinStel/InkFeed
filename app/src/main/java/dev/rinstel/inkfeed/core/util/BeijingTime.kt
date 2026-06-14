package dev.rinstel.inkfeed.core.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object BeijingTime {
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    fun startOfDayMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance(zone, Locale.US).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun date(nowMillis: Long = System.currentTimeMillis()): String =
        formatter("yyyy-MM-dd").format(nowMillis)

    fun dateTime(epochMillis: Long): String =
        formatter("yyyy-MM-dd HH:mm").format(epochMillis)

    fun isoInstant(epochMillis: Long): String =
        formatter("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")).format(epochMillis)

    private fun formatter(pattern: String, timeZone: TimeZone = zone): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.US).apply {
            this.timeZone = timeZone
        }
}
