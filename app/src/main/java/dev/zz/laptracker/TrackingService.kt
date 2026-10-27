package dev.zz.laptracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.graphics.drawable.Icon
import android.location.*
import android.os.*
import android.provider.CallLog
import android.telephony.*
import java.util.concurrent.Executor
import kotlin.math.max

private class ModernCallListener(private val onState: (Int) -> Unit) : TelephonyCallback(), TelephonyCallback.CallStateListener { override fun onCallStateChanged(state: Int) = onState(state) }

class TrackingService : Service() {
    companion object {
        const val ACTION_START = "dev.zz.laptracker.action.START"
        const val ACTION_STOP = "dev.zz.laptracker.action.STOP"
        const val ACTION_EXTERNAL_NOTIFICATION = "dev.zz.laptracker.action.EXTERNAL_NOTIFICATION"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val PREFS = "laptracker_prefs"
        const val PREF_AUTO_MONITOR = "auto_monitor"
        const val PREF_PENDING_CALL_NUMBER = "pending_call_number"
        private const val CHANNEL_ID = "tracking"
        private const val CALL_CHANNEL_ID = "calls"
        private const val NOTIFICATION_ID = 1
        private const val CALL_NOTIFICATION_ID = 42
        private const val MAX_ACCURACY_M = 35f
        private const val MIN_STEP_M = 3.0
        private const val MAX_SPEED_MS = 70.0
        private const val RIDE_START_SPEED_MS = 1.5
        private const val RIDE_START_CONFIRM_MS = 8_000L
        private const val RIDE_END_STOPPED_MS = 8 * 60_000L
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var locationManager: LocationManager
    private lateinit var db: Db
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitoring = false
    private var rideActive = false
    private var sessionId = 0L
    private var startTime = 0L
    private var distance = 0.0
    private var anchor: Location? = null
    private var segment = 0
    private var lastFix: Location? = null
    private var hasFix = false
    private var accuracy: Float? = null
    private var speed = 0f
    private var acceleration: Double? = null
    private var previousSpeed: Float? = null
    private var previousSpeedNanos = 0L
    private var locationOn = true
    private var lastNotify = 0L
    private var movingSince = 0L
    private var stationarySince = 0L
    private var lastClipboardNumber: String? = null
    private var lastClipboardAt = 0L
    private var callCallback: Any? = null
    private var legacyListener: PhoneStateListener? = null
    private var callStateKnown = false
    private var callState = TelephonyManager.CALL_STATE_IDLE
    private var callStartTime = 0L
    private var callStartEventId = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { locationOn = true; publish() }
        override fun onProviderDisabled(provider: String) { locationOn = false; hasFix = false; anchor = null; previousSpeed = null; previousSpeedNanos = 0L; segment++; publish() }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("tracker-worker"); thread.start(); handler = Handler(thread.looper)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        db = Db.get(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ride tracking", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CALL_CHANNEL_ID, "Call actions", NotificationManager.IMPORTANCE_HIGH))
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                try { startForeground(NOTIFICATION_ID, buildNotification("Waiting for a ride…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
                catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
                handler.post { startMonitoring() }
            }
            ACTION_STOP -> handler.post { stopMonitoring() }
            ACTION_EXTERNAL_NOTIFICATION -> handler.post {
                val source = intent.getStringExtra(EXTRA_SOURCE) ?: "Notification"
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (rideActive) db.insertEvent(sessionId, "INDRIVE", "$source: ${title.ifBlank { text }}", null)
                publish()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.post { if (rideActive) finishRide(System.currentTimeMillis()); if (monitoring) stopMonitoringInternal(); thread.quitSafely() }
        super.onDestroy()
    }

    private fun startMonitoring() {
        if (monitoring) return
        db.recoverOpenSessions(); monitoring = true
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_AUTO_MONITOR, true).apply()
        movingSince = 0L; stationarySince = 0L; acquireWakeLock(); registerLocation(); registerCalls(); registerClipboardWatcher(); publish()
    }
    private fun stopMonitoring() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_AUTO_MONITOR, false).apply()
        if (rideActive) finishRide(System.currentTimeMillis())
        stopMonitoringInternal(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun stopMonitoringInternal() { if (!monitoring) return; monitoring=false; try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}; unregisterCalls(); releaseWakeLock(); publish() }

    @SuppressLint("MissingPermission")
    private fun registerLocation() {
        locationOn = locationManager.isLocationEnabled
        try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, handler.looper) } catch (_: Exception) { locationOn=false }
    }

    private fun onFix(loc: Location) {
        if (!monitoring) return
        lastFix=loc
        val acc=if(loc.hasAccuracy())loc.accuracy else Float.MAX_VALUE
        if(acc>MAX_ACCURACY_M){accuracy=if(loc.hasAccuracy())loc.accuracy else null;publish();return}
        accuracy=if(loc.hasAccuracy())loc.accuracy else null
        val sp=if(loc.hasSpeed())loc.speed else 0f; speed=sp
        val nowN=loc.elapsedRealtimeNanos
        if(previousSpeed!=null&&previousSpeedNanos>0){val dt=(nowN-previousSpeedNanos)/1e9;if(dt>0.25&&dt<=5.0)acceleration=(sp-previousSpeed!!)/dt}
        previousSpeed=sp;previousSpeedNanos=nowN;hasFix=true
        val prev=anchor
        if(prev==null){anchor=loc;movingSince=if(sp>=RIDE_START_SPEED_MS)System.currentTimeMillis() else 0L}
        else {
            val dt=max(0.001,(loc.elapsedRealtimeNanos-prev.elapsedRealtimeNanos)/1e9);val d=prev.distanceTo(loc).toDouble()
            if(dt>120.0||d/dt>MAX_SPEED_MS){segment++;anchor=loc;previousSpeed=null;previousSpeedNanos=0L;acceleration=null}
            else {
                val moving=sp>=RIDE_START_SPEED_MS||d/dt>=RIDE_START_SPEED_MS
                if(moving){stationarySince=0L;if(movingSince==0L)movingSince=System.currentTimeMillis();if(!rideActive&&System.currentTimeMillis()-movingSince>=RIDE_START_CONFIRM_MS)startRide(loc);if(rideActive){val threshold=max(MIN_STEP_M,acc*0.5);if(d>=threshold)distance+=d};anchor=loc}
                else {movingSince=0L;if(rideActive){if(stationarySince==0L)stationarySince=System.currentTimeMillis();if(System.currentTimeMillis()-stationarySince>=RIDE_END_STOPPED_MS)finishRide(System.currentTimeMillis())};anchor=loc}
            }
        }
        if(rideActive)storePoint(loc);publish()
    }

    private fun startRide(firstFix: Location) {
        if(rideActive)return
        rideActive=true;startTime=System.currentTimeMillis()-RIDE_START_CONFIRM_MS;sessionId=db.startSession(startTime);distance=0.0;segment=0;stationarySince=0L
        db.insertPoint(sessionId,firstFix.time,firstFix.latitude,firstFix.longitude,if(firstFix.hasAltitude())firstFix.altitude else null,if(firstFix.hasAccuracy())firstFix.accuracy else null,if(firstFix.hasSpeed())firstFix.speed else null,segment)
        publish()
    }
    private fun storePoint(loc:Location){db.insertPoint(sessionId,loc.time,loc.latitude,loc.longitude,if(loc.hasAltitude())loc.altitude else null,if(loc.hasAccuracy())loc.accuracy else null,if(loc.hasSpeed())loc.speed else null,segment);db.updateSessionDistance(sessionId,distance)}
    private fun finishRide(end:Long){if(!rideActive)return;db.finishSession(sessionId,end,distance);rideActive=false;sessionId=0L;startTime=0L;distance=0.0;anchor=lastFix;movingSince=0L;stationarySince=0L;publish(ended=end)}

    private fun registerCalls(){val tm=getSystemService(TelephonyManager::class.java)?:return;callStateKnown=false;try{if(Build.VERSION.SDK_INT>=31){val cb=ModernCallListener{state->onCallState(state)};callCallback=cb;tm.registerTelephonyCallback(Executor{r->handler.post(r)},cb)}else{val l=object:PhoneStateListener(){@Deprecated("Deprecated in Java")override fun onCallStateChanged(state:Int,phoneNumber:String?){onCallState(state)}};legacyListener=l;@Suppress("DEPRECATION")tm.listen(l,PhoneStateListener.LISTEN_CALL_STATE)}}catch(_:Exception){}}
    private fun unregisterCalls(){val tm=getSystemService(TelephonyManager::class.java)?:return;try{if(Build.VERSION.SDK_INT>=31){val cb=callCallback;if(cb is TelephonyCallback)tm.unregisterTelephonyCallback(cb)}else{legacyListener?.let{@Suppress("DEPRECATION")tm.listen(it,PhoneStateListener.LISTEN_NONE)}}}catch(_:Exception){};callCallback=null;legacyListener=null;callStateKnown=false}
    private fun onCallState(state:Int){val prev=callState;callState=state;if(!callStateKnown){callStateKnown=true;return};if(state==TelephonyManager.CALL_STATE_OFFHOOK&&prev!=TelephonyManager.CALL_STATE_OFFHOOK){callStartTime=System.currentTimeMillis();if(rideActive)callStartEventId=db.insertEvent(sessionId,"CALL_START","Call started",pendingCallNumber()).id}else if(state==TelephonyManager.CALL_STATE_IDLE&&prev==TelephonyManager.CALL_STATE_OFFHOOK){val number=lookupEndedCallNumber(callStartTime)?:pendingCallNumber();if(rideActive&&callStartEventId!=0L)db.updateEventPhoneNumber(callStartEventId,number);if(rideActive)db.insertEvent(sessionId,"CALL_END","Call ended",number);getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove(PREF_PENDING_CALL_NUMBER).apply();callStartEventId=0L;callStartTime=0L};publish()}
    private fun pendingCallNumber():String?=getSharedPreferences(PREFS,MODE_PRIVATE).getString(PREF_PENDING_CALL_NUMBER,null)
    @SuppressLint("MissingPermission") private fun lookupEndedCallNumber(start:Long):String?{if(checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED)return null;return try{val c:Cursor=contentResolver.query(CallLog.Calls.CONTENT_URI,arrayOf(CallLog.Calls.NUMBER,CallLog.Calls.DATE),"${CallLog.Calls.DATE} >= ?",arrayOf((start-60000L).toString()),"${CallLog.Calls.DATE} DESC")?:return null;c.use{if(it.moveToFirst())it.getString(0)?.takeIf{n->n.isNotBlank()}else null}}catch(_:Exception){null}}

    private fun registerClipboardWatcher(){val cm=getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager;cm.addPrimaryClipChangedListener{handler.post{try{if(!cm.hasPrimaryClip())return@post;val text=cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty();val number=Regex("(?<!\\d)(03\\d{9})(?!\\d)").find(text)?.value?:return@post;val now=System.currentTimeMillis();if(number==lastClipboardNumber&&now-lastClipboardAt<30000L)return@post;lastClipboardNumber=number;lastClipboardAt=now;postCallNotification(number)}catch(_:SecurityException){}catch(_:Exception){}}}}
    private fun postCallNotification(number:String){val flags=PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT;val call=PendingIntent.getBroadcast(this,number.hashCode(),Intent(this,CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_CALL).putExtra(CallActionReceiver.EXTRA_NUMBER,number),flags);val n=Notification.Builder(this,CALL_CHANNEL_ID).setSmallIcon(R.drawable.ic_stat).setContentTitle("Phone number detected").setContentText(number).setAutoCancel(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(Icon.createWithResource(this,R.drawable.ic_stat),"Call",call).build()).build();getSystemService(NotificationManager::class.java).notify(CALL_NOTIFICATION_ID,n)}

    private fun acquireWakeLock(){val pm=getSystemService(Context.POWER_SERVICE) as PowerManager;val wl=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"laptracker:tracking");wl.setReferenceCounted(false);wl.acquire(12L*60L*60L*1000L);wakeLock=wl}
    private fun releaseWakeLock(){try{wakeLock?.let{if(it.isHeld)it.release()}}catch(_:Exception){};wakeLock=null}
    private fun publish(ended:Long=0L){TrackerBus.publish(LiveState(monitoring,locationOn,hasFix,sessionId,startTime,ended,distance,speed,acceleration,accuracy,0,0.0,0L,emptyList()));if(monitoring)updateNotification()}
    private fun updateNotification(){val now=SystemClock.elapsedRealtime();if(now-lastNotify<3000)return;lastNotify=now;val text=if(rideActive)"Ride: ${Fmt.km(distance)} · ${Fmt.duration(System.currentTimeMillis()-startTime)}" else "Waiting for movement · rides detected automatically";getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,buildNotification(text))}
    private fun buildNotification(text:String):Notification{val flags=PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT;val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),flags);val stop=PendingIntent.getService(this,2,Intent(this,TrackingService::class.java).setAction(ACTION_STOP),flags);return Notification.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.ic_stat).setContentTitle("Lap Tracker – auto tracking").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(Icon.createWithResource(this,R.drawable.ic_stat),"Turn off",stop).build()).build()}
}
