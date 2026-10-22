package dev.zz.laptracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Fmt {
    fun km(meters: Double): String = String.format(Locale.US, "%.2f km", meters / 1000.0)

    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    fun speedKmh(metersPerSecond: Double): String =
        String.format(Locale.US, "%.1f km/h", metersPerSecond * 3.6)

    fun dateTime(ms: Long): String =
        SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

    fun fileStamp(ms: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ms))

    fun iso(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    fun reasonLabel(reason: String): String = when (reason) {
        "MANUAL" -> "Manual lap"
        "CALL_START" -> "Call started"
        "CALL_END" -> "Call ended"
        "FINISH" -> "Finish"
        else -> reason
    }

    fun lapLine(l: Lap): String =
        "#${l.number} · ${reasonLabel(l.reason)}\n" +
            "${km(l.lapDistanceM)} in ${duration(l.lapDurationMs)} · total ${km(l.totalDistanceM)}"
}
