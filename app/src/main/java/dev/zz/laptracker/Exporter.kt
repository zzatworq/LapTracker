package dev.zz.laptracker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.util.Locale

object Exporter {

    private fun coord(d: Double): String = String.format(Locale.US, "%.7f", d)
    private fun num(d: Double): String = String.format(Locale.US, "%.2f", d)

    fun buildGpx(session: Session, points: List<TrackPoint>, laps: List<Lap>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Lap Tracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>Lap Tracker ").append(Fmt.iso(session.startTime))
            .append("</name><time>").append(Fmt.iso(session.startTime)).append("</time></metadata>\n")

        for (lap in laps) {
            val lat = lap.lat
            val lon = lap.lon
            if (lat != null && lon != null) {
                sb.append("  <wpt lat=\"").append(coord(lat)).append("\" lon=\"").append(coord(lon)).append("\">\n")
                sb.append("    <time>").append(Fmt.iso(lap.time)).append("</time>\n")
                sb.append("    <name>Lap ").append(lap.number).append(" - ")
                    .append(Fmt.reasonLabel(lap.reason)).append("</name>\n")
                sb.append("    <desc>").append(Fmt.km(lap.lapDistanceM)).append(" in ")
                    .append(Fmt.duration(lap.lapDurationMs)).append(", total ")
                    .append(Fmt.km(lap.totalDistanceM)).append("</desc>\n")
                sb.append("  </wpt>\n")
            }
        }

        sb.append("  <trk>\n    <name>Track ").append(Fmt.iso(session.startTime)).append("</name>\n")
        var currentSegment = Int.MIN_VALUE
        var open = false
        for (p in points) {
            if (!open || p.segment != currentSegment) {
                if (open) sb.append("    </trkseg>\n")
                sb.append("    <trkseg>\n")
                open = true
                currentSegment = p.segment
            }
            sb.append("      <trkpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\">")
            val alt = p.altitude
            if (alt != null) sb.append("<ele>").append(num(alt)).append("</ele>")
            sb.append("<time>").append(Fmt.iso(p.time)).append("</time></trkpt>\n")
        }
        if (open) sb.append("    </trkseg>\n")
        sb.append("  </trk>\n</gpx>\n")
        return sb.toString()
    }

    fun buildLapsCsv(laps: List<Lap>): String {
        val sb = StringBuilder()
        sb.append("lap,reason,time_utc,lap_distance_m,lap_duration_s,total_distance_m,lat,lon\n")
        for (l in laps) {
            sb.append(l.number).append(',')
                .append(l.reason).append(',')
                .append(Fmt.iso(l.time)).append(',')
                .append(num(l.lapDistanceM)).append(',')
                .append(l.lapDurationMs / 1000).append(',')
                .append(num(l.totalDistanceM)).append(',')
            val lat = l.lat
            val lon = l.lon
            if (lat != null && lon != null) sb.append(coord(lat)).append(',').append(coord(lon))
            else sb.append(',')
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Saves text into Downloads/LapTracker (no storage permission needed on Android 10+). */
    fun saveToDownloads(context: Context, name: String, mime: String, content: String): Uri? {
        return try {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LapTracker")
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val out = resolver.openOutputStream(uri) ?: return null
            out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            uri
        } catch (e: Exception) {
            null
        }
    }
}
