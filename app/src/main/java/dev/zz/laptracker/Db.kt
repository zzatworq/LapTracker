package dev.zz.laptracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Session(
    val id: Long,
    val startTime: Long,
    val endTime: Long?,
    val distanceM: Double,
    val pointCount: Int,
    val lapCount: Int
)

data class Lap(
    val id: Long,
    val sessionId: Long,
    val number: Int,
    val time: Long,
    val reason: String,
    val lapDistanceM: Double,
    val lapDurationMs: Long,
    val totalDistanceM: Double,
    val lat: Double?,
    val lon: Double?
)

data class TrackPoint(
    val time: Long,
    val lat: Double,
    val lon: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val speed: Float?,
    val segment: Int
)

class Db private constructor(context: Context) :
    SQLiteOpenHelper(context, "laptracker.db", null, 1) {

    companion object {
        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sessions(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "start_time INTEGER NOT NULL, " +
                "end_time INTEGER, " +
                "distance_m REAL NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE points(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "session_id INTEGER NOT NULL, " +
                "time INTEGER NOT NULL, " +
                "lat REAL NOT NULL, " +
                "lon REAL NOT NULL, " +
                "altitude REAL, " +
                "accuracy REAL, " +
                "speed REAL, " +
                "segment INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE laps(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "session_id INTEGER NOT NULL, " +
                "number INTEGER NOT NULL, " +
                "time INTEGER NOT NULL, " +
                "reason TEXT NOT NULL, " +
                "lap_distance_m REAL NOT NULL, " +
                "lap_duration_ms INTEGER NOT NULL, " +
                "total_distance_m REAL NOT NULL, " +
                "lat REAL, " +
                "lon REAL)"
        )
        db.execSQL("CREATE INDEX idx_points_session ON points(session_id, time)")
        db.execSQL("CREATE INDEX idx_laps_session ON laps(session_id, number)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nothing to migrate yet.
    }

    fun startSession(startTime: Long): Long {
        val v = ContentValues()
        v.put("start_time", startTime)
        v.put("distance_m", 0.0)
        return writableDatabase.insert("sessions", null, v)
    }

    fun updateSessionDistance(sessionId: Long, distanceM: Double) {
        val v = ContentValues()
        v.put("distance_m", distanceM)
        writableDatabase.update("sessions", v, "id = ?", arrayOf(sessionId.toString()))
    }

    fun finishSession(sessionId: Long, endTime: Long, distanceM: Double) {
        val v = ContentValues()
        v.put("end_time", endTime)
        v.put("distance_m", distanceM)
        writableDatabase.update("sessions", v, "id = ?", arrayOf(sessionId.toString()))
    }

    /** Closes sessions left open by a crash or a killed process. */
    fun recoverOpenSessions() {
        writableDatabase.execSQL(
            "UPDATE sessions SET end_time = COALESCE(" +
                "(SELECT MAX(time) FROM points WHERE points.session_id = sessions.id), start_time) " +
                "WHERE end_time IS NULL"
        )
    }

    fun insertPoint(
        sessionId: Long,
        time: Long,
        lat: Double,
        lon: Double,
        altitude: Double?,
        accuracy: Float?,
        speed: Float?,
        segment: Int
    ) {
        val v = ContentValues()
        v.put("session_id", sessionId)
        v.put("time", time)
        v.put("lat", lat)
        v.put("lon", lon)
        v.put("altitude", altitude)
        v.put("accuracy", accuracy)
        v.put("speed", speed)
        v.put("segment", segment)
        writableDatabase.insert("points", null, v)
    }

    fun insertLap(
        sessionId: Long,
        number: Int,
        time: Long,
        reason: String,
        lapDistanceM: Double,
        lapDurationMs: Long,
        totalDistanceM: Double,
        lat: Double?,
        lon: Double?
    ): Lap {
        val v = ContentValues()
        v.put("session_id", sessionId)
        v.put("number", number)
        v.put("time", time)
        v.put("reason", reason)
        v.put("lap_distance_m", lapDistanceM)
        v.put("lap_duration_ms", lapDurationMs)
        v.put("total_distance_m", totalDistanceM)
        v.put("lat", lat)
        v.put("lon", lon)
        val id = writableDatabase.insert("laps", null, v)
        return Lap(id, sessionId, number, time, reason, lapDistanceM, lapDurationMs, totalDistanceM, lat, lon)
    }

    fun listSessions(): List<Session> {
        val out = ArrayList<Session>()
        readableDatabase.rawQuery(
            "SELECT s.id, s.start_time, s.end_time, s.distance_m, " +
                "(SELECT COUNT(*) FROM points p WHERE p.session_id = s.id), " +
                "(SELECT COUNT(*) FROM laps l WHERE l.session_id = s.id) " +
                "FROM sessions s ORDER BY s.start_time DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Session(
                        c.getLong(0),
                        c.getLong(1),
                        if (c.isNull(2)) null else c.getLong(2),
                        c.getDouble(3),
                        c.getInt(4),
                        c.getInt(5)
                    )
                )
            }
        }
        return out
    }

    fun getLaps(sessionId: Long): List<Lap> {
        val out = ArrayList<Lap>()
        readableDatabase.rawQuery(
            "SELECT id, session_id, number, time, reason, lap_distance_m, lap_duration_ms, " +
                "total_distance_m, lat, lon FROM laps WHERE session_id = ? ORDER BY number",
            arrayOf(sessionId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Lap(
                        c.getLong(0),
                        c.getLong(1),
                        c.getInt(2),
                        c.getLong(3),
                        c.getString(4),
                        c.getDouble(5),
                        c.getLong(6),
                        c.getDouble(7),
                        if (c.isNull(8)) null else c.getDouble(8),
                        if (c.isNull(9)) null else c.getDouble(9)
                    )
                )
            }
        }
        return out
    }

    fun getPoints(sessionId: Long): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        readableDatabase.rawQuery(
            "SELECT time, lat, lon, altitude, accuracy, speed, segment " +
                "FROM points WHERE session_id = ? ORDER BY time, id",
            arrayOf(sessionId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TrackPoint(
                        c.getLong(0),
                        c.getDouble(1),
                        c.getDouble(2),
                        if (c.isNull(3)) null else c.getDouble(3),
                        if (c.isNull(4)) null else c.getFloat(4),
                        if (c.isNull(5)) null else c.getFloat(5),
                        c.getInt(6)
                    )
                )
            }
        }
        return out
    }

    fun deleteSession(sessionId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val arg = arrayOf(sessionId.toString())
            db.delete("points", "session_id = ?", arg)
            db.delete("laps", "session_id = ?", arg)
            db.delete("sessions", "id = ?", arg)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
