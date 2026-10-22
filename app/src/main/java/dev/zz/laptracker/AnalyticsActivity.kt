package dev.zz.laptracker

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Locale

class AnalyticsActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(Color.BLACK) }
        root.addView(TextView(this).apply { text = "Ride & Fuel Analysis"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(8)) })
        root.addView(TextView(this).apply { text = "Honda CD70 model · estimates are calibrated by your riding data."; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, dp(12)) })
        val list = ListView(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)); setContentView(root)
        Thread {
            val db = Db.get(this); val sessions = db.listSessions()
            val rows = sessions.map { s ->
                val end = s.endTime ?: System.currentTimeMillis(); val a = FuelModel.analyse(db.getPoints(s.id), s.distanceM, end - s.startTime)
                "${Fmt.dateTime(s.startTime)}\n${Fmt.km(s.distanceM)} · Avg ${one(a.averageSpeedKmh)} km/h · Max ${one(a.maxSpeedKmh)} km/h\nAcceleration max ${two(a.maxAcceleration)} m/s² · braking ${two(a.maxBraking)} m/s²\nHard acceleration ${a.hardAccelerations} · hard braking ${a.hardBrakings}\nEstimated CD70: ${one(a.estimatedKmPerLitre)} km/L · ${two(a.estimatedFuelLitres)} L fuel · ${a.confidence} confidence"
            }
            runOnUiThread { list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows.ifEmpty { listOf("No rides yet. Record a ride first.") }) }
        }.start()
    }
    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun two(v: Double) = String.format(Locale.US, "%.2f", v)
}
