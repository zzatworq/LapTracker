package dev.zz.laptracker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.concurrent.Executor
import kotlin.math.max

private class ModernCallListener(private val onState: (Int) -> Unit) : TelephonyCallback(), TelephonyCallback.CallStateListener { override fun onCallStateChanged(state: Int) { onState(state) } }

class TrackingService : Service() {
    companion object {
        const val ACTION_START = "dev.zz.laptracker.action.START"; const val ACTION_STOP = "dev.zz.laptracker.action.STOP"; const val ACTION_LAP = "dev.zz.laptracker.action.LAP"
        const val PREFS = "laptracker_prefs"; const val PREF_LAP_ON_CALL_START = "lap_on_call_start"; const val PREF_LAP_ON_CALL_END = "lap_on_call_end"
        private const val CHANNEL_ID = "tracking"; private const val NOTIFICATION_ID = 1; private const val MAX_ACCURACY_M = 35f; private const val MIN_STEP_M = 3.0; private const val MAX_SPEED_MS = 70.0; private const val GAP_MS = 120_000L
    }
    private lateinit var thread: HandlerThread; private lateinit var handler: Handler; private lateinit var locationManager: LocationManager; private lateinit var db: Db
    private var wakeLock: PowerManager.WakeLock? = null; private var tracking = false; private var sessionId = 0L; private var startTime = 0L; private var distance = 0.0; private var anchor: Location? = null; private var segment = 0; private var lapCount = 0; private var lapStartTime = 0L; private var lapStartDistance = 0.0
    private val laps = ArrayList<Lap>(); private var lastFix: Location? = null; private var hasFix = false; private var accuracy: Float? = null; private var speed = 0f; private var locationOn = true; private var lastNotify = 0L
    private var callCallback: Any? = null; private var legacyListener: PhoneStateListener? = null; private var callStateKnown = false; private var callState = TelephonyManager.CALL_STATE_IDLE; private var callStartTime = 0L; private var callStartLapId = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { onFix(location) }; override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { locationOn = true; publish() }; override fun onProviderDisabled(provider: String) { locationOn = false; hasFix = false; anchor = null; segment++; publish() }
    }
    override fun onCreate() { super.onCreate(); thread = HandlerThread("tracker-worker"); thread.start(); handler = Handler(thread.looper); locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager; db = Db.get(this); getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tracking", NotificationManager.IMPORTANCE_LOW)) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { when (intent?.action) { ACTION_START -> { try { startForeground(NOTIFICATION_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }; handler.post { startTracking() } }; ACTION_LAP -> handler.post { addLap("MANUAL"); if (!tracking) stopSelf() }; ACTION_STOP -> handler.post { stopTracking() }; else -> handler.post { if (!tracking) stopSelf() } }; return START_NOT_STICKY }
    override fun onDestroy() { handler.post { if (tracking) stopTracking(); thread.quitSafely() }; super.onDestroy() }

    private fun startTracking() { if (tracking) return; db.recoverOpenSessions(); tracking = true; startTime = System.currentTimeMillis(); sessionId = db.startSession(startTime); distance = 0.0; anchor = null; lastFix = null; hasFix = false; accuracy = null; speed = 0f; segment = 0; lapCount = 0; lapStartTime = startTime; lapStartDistance = 0.0; laps.clear(); callStartLapId = 0L; acquireWakeLock(); registerLocation(); registerCalls(); publish() }
    private fun stopTracking() { if (!tracking) { stopSelf(); return }; val now = System.currentTimeMillis(); if (now - lapStartTime > 2000) addLap("FINISH"); tracking = false; try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}; unregisterCalls(); db.finishSession(sessionId, now, distance); releaseWakeLock(); publish(ended = now); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    @SuppressLint("MissingPermission") private fun registerLocation() { locationOn = locationManager.isLocationEnabled; try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, handler.looper) } catch (_: SecurityException) { stopTracking() } catch (_: IllegalArgumentException) { stopTracking() } }
    private fun onFix(loc: Location) { if (!tracking) return; lastFix = loc; accuracy = if (loc.hasAccuracy()) loc.accuracy else null; speed = if (loc.hasSpeed()) loc.speed else 0f; val acc = accuracy ?: Float.MAX_VALUE; hasFix = acc <= MAX_ACCURACY_M; if (hasFix) { val prev = anchor; if (prev == null) { anchor = loc; storePoint(loc) } else { val dt = max(0.001, (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9); val d = prev.distanceTo(loc).toDouble(); if (dt * 1000.0 > GAP_MS || d / dt > MAX_SPEED_MS) { segment++; anchor = loc; storePoint(loc) } else { val stationary = loc.hasSpeed() && loc.speed < 0.4f; val threshold = max(MIN_STEP_M, acc * 0.5); if (!stationary && d >= threshold) { distance += d; anchor = loc; storePoint(loc) } } } }; publish() }
    private fun storePoint(loc: Location) { db.insertPoint(sessionId, loc.time, loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null, if (loc.hasAccuracy()) loc.accuracy else null, if (loc.hasSpeed()) loc.speed else null, segment); db.updateSessionDistance(sessionId, distance) }
    private fun addLap(reason: String, phoneNumber: String? = null): Lap? { if (!tracking) return null; val now = System.currentTimeMillis(); if (reason == "MANUAL" && now - lapStartTime < 1000) return null; lapCount++; val fix = lastFix; val lap = db.insertLap(sessionId, lapCount, now, reason, distance - lapStartDistance, now - lapStartTime, distance, fix?.latitude, fix?.longitude, phoneNumber); laps.add(lap); lapStartTime = now; lapStartDistance = distance; publish(); return lap }

    private fun registerCalls() { val tm = getSystemService(TelephonyManager::class.java) ?: return; callStateKnown = false; try { if (Build.VERSION.SDK_INT >= 31) { val cb = ModernCallListener { state -> onCallState(state) }; callCallback = cb; tm.registerTelephonyCallback(Executor { r -> handler.post(r) }, cb) } else { val l = object : PhoneStateListener() { @Deprecated("Deprecated in Java") override fun onCallStateChanged(state: Int, phoneNumber: String?) { onCallState(state) } }; legacyListener = l; @Suppress("DEPRECATION") tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE) } } catch (_: SecurityException) {} catch (_: Exception) {} }
    private fun unregisterCalls() { val tm = getSystemService(TelephonyManager::class.java) ?: return; try { if (Build.VERSION.SDK_INT >= 31) { val cb = callCallback; if (cb is TelephonyCallback) tm.unregisterTelephonyCallback(cb) } else { legacyListener?.let { @Suppress("DEPRECATION") tm.listen(it, PhoneStateListener.LISTEN_NONE) } } } catch (_: Exception) {}; callCallback = null; legacyListener = null; callStateKnown = false }

    private fun onCallState(state: Int) {
        if (!tracking) return
        val prev = callState; callState = state; if (!callStateKnown) { callStateKnown = true; return }
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (state == TelephonyManager.CALL_STATE_OFFHOOK && prev != TelephonyManager.CALL_STATE_OFFHOOK) {
            callStartTime = System.currentTimeMillis(); if (prefs.getBoolean(PREF_LAP_ON_CALL_START, true)) callStartLapId = addLap("CALL_START")?.id ?: 0L
        } else if (state == TelephonyManager.CALL_STATE_IDLE && prev == TelephonyManager.CALL_STATE_OFFHOOK) {
            val number = lookupEndedCallNumber(callStartTime)
            if (callStartLapId != 0L) db.updateLapPhoneNumber(callStartLapId, number)
            if (prefs.getBoolean(PREF_LAP_ON_CALL_END, true)) addLap("CALL_END", number)
            callStartLapId = 0L; callStartTime = 0L
        }
    }

    @SuppressLint("MissingPermission") private fun lookupEndedCallNumber(start: Long): String? {
        if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        return try {
            val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE)
            val selection = "${CallLog.Calls.DATE} >= ?"
            val c: Cursor = contentResolver.query(CallLog.Calls.CONTENT_URI, projection, selection, arrayOf((start - 60_000L).toString()), "${CallLog.Calls.DATE} DESC") ?: return null
            c.use { if (it.moveToFirst()) it.getString(0)?.takeIf { n -> n.isNotBlank() } else null }
        } catch (_: Exception) { null }
    }

    private fun acquireWakeLock() { val pm = getSystemService(Context.POWER_SERVICE) as PowerManager; val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "laptracker:tracking"); wl.setReferenceCounted(false); wl.acquire(12L * 60L * 60L * 1000L); wakeLock = wl }
    private fun releaseWakeLock() { try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}; wakeLock = null }
    private fun publish(ended: Long = 0L) { TrackerBus.publish(LiveState(tracking, locationOn, hasFix, sessionId, startTime, ended, distance, speed, accuracy, lapCount, distance - lapStartDistance, lapStartTime, ArrayList(laps))); if (tracking) updateNotification() }
    private fun updateNotification() { val now = SystemClock.elapsedRealtime(); if (now - lastNotify < 3000) return; lastNotify = now; val text = Fmt.km(distance) + " · " + Fmt.duration(System.currentTimeMillis() - startTime) + " · laps " + lapCount + (if (locationOn) "" else " · location off"); getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
    private fun buildNotification(text: String): Notification { val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT; val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags); val lapPi = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_LAP), flags); val stopPi = PendingIntent.getService(this, 2, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), flags); val icon = Icon.createWithResource(this, R.drawable.ic_stat); return Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat).setContentTitle("Lap Tracker – recording").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(icon, "Lap", lapPi).build()).addAction(Notification.Action.Builder(icon, "Stop", stopPi).build()).build() }
}
