package dev.zz.laptracker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.Typeface

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var distanceView: TextView
    private lateinit var elapsedView: TextView
    private lateinit var speedView: TextView
    private lateinit var avgView: TextView
    private lateinit var currentLapView: TextView
    private lateinit var startBtn: Button
    private lateinit var lapBtn: Button
    private lateinit var adapter: ArrayAdapter<String>
    private val ui = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable { override fun run() { render(); ui.postDelayed(this, 1000) } }
    private var shownSessionId = -1L
    private var shownLapCount = -1
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(TrackingService.PREFS, Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(Color.WHITE) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply { text = "Lap Tracker"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK) }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply { text = "History"; setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) } })
        root.addView(header)
        statusView = TextView(this).apply { textSize = 14f; setTextColor(Color.DKGRAY) }; root.addView(statusView)
        distanceView = TextView(this).apply { textSize = 56f; setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }; root.addView(distanceView)
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        elapsedView = addStat(stats, "Time"); speedView = addStat(stats, "Speed"); avgView = addStat(stats, "Average"); root.addView(stats)
        currentLapView = TextView(this).apply { textSize = 14f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }; root.addView(currentLapView)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(this).apply { textSize = 18f; setOnClickListener { onStartStopClicked() } }
        lapBtn = Button(this).apply { text = "Lap"; textSize = 18f; setOnClickListener { startService(Intent(this@MainActivity, TrackingService::class.java).setAction(TrackingService.ACTION_LAP)) } }
        buttons.addView(startBtn, LinearLayout.LayoutParams(0, dp(64), 1f)); buttons.addView(lapBtn, LinearLayout.LayoutParams(0, dp(64), 1f)); root.addView(buttons)
        root.addView(callSwitch("Mark a lap when a call starts", prefs, TrackingService.PREF_LAP_ON_CALL_START))
        root.addView(callSwitch("Mark a lap when a call ends", prefs, TrackingService.PREF_LAP_ON_CALL_END))
        root.addView(TextView(this).apply { text = "Laps"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK); setPadding(0, dp(12), 0, dp(4)) })
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        root.addView(ListView(this).apply { adapter = this@MainActivity.adapter }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun addStat(row: LinearLayout, label: String): TextView {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val value = TextView(this).apply { textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK); gravity = Gravity.CENTER }
        val caption = TextView(this).apply { text = label; textSize = 12f; setTextColor(Color.GRAY); gravity = Gravity.CENTER }
        box.addView(value); box.addView(caption); row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); return value
    }
    private fun callSwitch(label: String, prefs: android.content.SharedPreferences, key: String): Switch = Switch(this).apply { text = label; isChecked = prefs.getBoolean(key, true); setPadding(0, dp(8), 0, dp(8)); setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() } }
    override fun onStart() { super.onStart(); TrackerBus.addListener(listener); ui.post(tick) }
    override fun onStop() { super.onStop(); TrackerBus.removeListener(listener); ui.removeCallbacks(tick) }

    private fun render() {
        val s = TrackerBus.state; val now = System.currentTimeMillis()
        val elapsed = when { s.startTime == 0L -> 0L; s.running -> now - s.startTime; else -> s.endTime - s.startTime }
        distanceView.text = Fmt.km(s.distanceM); elapsedView.text = Fmt.duration(elapsed); speedView.text = if (s.running && s.hasFix) Fmt.speedKmh(s.speedMs.toDouble()) else "–"
        avgView.text = if (elapsed > 0 && s.distanceM > 0) Fmt.speedKmh(s.distanceM / (elapsed / 1000.0)) else "–"
        startBtn.text = if (s.running) "Stop" else "Start"; lapBtn.isEnabled = s.running
        statusView.text = when { !s.running && s.sessionId != 0L -> "Stopped – session saved to History"; !s.running -> "Ready. Press Start to begin recording."; !s.locationOn -> "Location is off – paused. Turn it on to continue."; !s.hasFix -> s.accuracyM?.let { "Waiting for a better GPS fix (±${it.toInt()} m)…" } ?: "Waiting for a GPS fix…"; else -> s.accuracyM?.let { "Recording · GPS ±${it.toInt()} m" } ?: "Recording" }
        currentLapView.text = if (s.running) "Lap ${s.lapCount + 1}: ${Fmt.km(s.currentLapDistanceM)} · ${Fmt.duration(now - s.currentLapStartTime)}" else ""
        if (s.sessionId != shownSessionId || s.laps.size != shownLapCount) { shownSessionId = s.sessionId; shownLapCount = s.laps.size; adapter.clear(); adapter.addAll(s.laps.asReversed().map { Fmt.lapLine(it) }); adapter.notifyDataSetChanged() }
    }

    private fun granted(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun onStartStopClicked() {
        if (TrackerBus.state.running) { startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP)); return }
        val needed = ArrayList<String>()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) { needed.add(Manifest.permission.ACCESS_FINE_LOCATION); needed.add(Manifest.permission.ACCESS_COARSE_LOCATION) }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (!granted(Manifest.permission.READ_PHONE_STATE)) needed.add(Manifest.permission.READ_PHONE_STATE)
        if (needed.isEmpty()) beginTracking() else requestPermissions(needed.toTypedArray(), 1)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) beginTracking() else Toast.makeText(this, "Precise location permission is required to record your route", Toast.LENGTH_LONG).show() }
    private fun beginTracking() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isLocationEnabled) Toast.makeText(this, "Location is off – recording will begin once you turn it on", Toast.LENGTH_LONG).show()
        if (!granted(Manifest.permission.READ_PHONE_STATE)) Toast.makeText(this, "Phone permission not granted – call laps are disabled", Toast.LENGTH_LONG).show()
        try { startForegroundService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_START)) } catch (e: Exception) { Toast.makeText(this, "Could not start tracking: ${e.message}", Toast.LENGTH_LONG).show() }
    }
}
