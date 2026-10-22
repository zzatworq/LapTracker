package dev.zz.laptracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class HistoryActivity : Activity() {
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private var sessions: List<Session> = emptyList()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(Color.BLACK) }
        val title = TextView(this).apply { text = "History"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(8)) }
        root.addView(title)
        emptyView = TextView(this).apply { text = "No recordings yet."; setTextColor(Color.LTGRAY); visibility = View.GONE }
        root.addView(emptyView)
        listView = ListView(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnItemClickListener { _, _, position, _ -> if (position in sessions.indices) showActions(sessions[position]) }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
    override fun onResume() { super.onResume(); load() }

    private fun describe(s: Session): String {
        val end = s.endTime ?: System.currentTimeMillis(); val live = if (s.endTime == null) " (recording)" else ""
        return Fmt.dateTime(s.startTime) + live + "\n" + Fmt.km(s.distanceM) + " · " + Fmt.duration(end - s.startTime) + " · " + s.lapCount + " laps"
    }
    private fun load() {
        Thread {
            val db = Db.get(this); if (!TrackerBus.state.running) db.recoverOpenSessions(); val list = db.listSessions()
            runOnUiThread { sessions = list; listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, list.map { describe(it) }); emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE }
        }.start()
    }
    private fun isRecording(s: Session): Boolean = TrackerBus.state.running && TrackerBus.state.sessionId == s.id
    private fun showActions(s: Session) {
        AlertDialog.Builder(this).setTitle(Fmt.dateTime(s.startTime)).setItems(arrayOf("View all laps", "Export GPX (save to Downloads + share)", "Export laps CSV (save to Downloads)", "Delete")) { _, which -> when (which) { 0 -> showLaps(s); 1 -> exportGpx(s); 2 -> exportCsv(s); 3 -> confirmDelete(s) } }.show()
    }
    private fun showLaps(s: Session) {
        Thread {
            val laps = Db.get(this).getLaps(s.id)
            runOnUiThread {
                if (laps.isEmpty()) toast("No laps in this session") else {
                    val items = laps.map { Fmt.lapLine(it) }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("${Fmt.km(s.distanceM)} · ${laps.size} laps").setItems(items, null).setPositiveButton("Close", null).show()
                }
            }
        }.start()
    }
    private fun exportGpx(s: Session) { Thread { val db = Db.get(this); val gpx = Exporter.buildGpx(s, db.getPoints(s.id), db.getLaps(s.id)); val name = "laptracker_" + Fmt.fileStamp(s.startTime) + ".gpx"; val uri = Exporter.saveToDownloads(this, name, "application/gpx+xml", gpx); runOnUiThread { if (uri == null) toast("Export failed") else { toast("Saved to Downloads/LapTracker/" + name); share(uri, "application/gpx+xml", "Share GPX") } } }.start() }
    private fun exportCsv(s: Session) { Thread { val csv = Exporter.buildLapsCsv(Db.get(this).getLaps(s.id)); val name = "laptracker_laps_" + Fmt.fileStamp(s.startTime) + ".csv"; val uri = Exporter.saveToDownloads(this, name, "text/csv", csv); runOnUiThread { toast(if (uri == null) "Export failed" else "Saved to Downloads/LapTracker/" + name) } }.start() }
    private fun confirmDelete(s: Session) { if (isRecording(s)) { toast("Stop recording before deleting this session"); return }; AlertDialog.Builder(this).setTitle("Delete this recording?").setMessage("The track and laps will be removed permanently.").setPositiveButton("Delete") { _, _ -> Thread { Db.get(this).deleteSession(s.id); runOnUiThread { load() } }.start() }.setNegativeButton("Cancel", null).show() }
    private fun share(uri: Uri, mime: String, chooserTitle: String) { val send = Intent(Intent.ACTION_SEND); send.type = mime; send.putExtra(Intent.EXTRA_STREAM, uri); send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(send, chooserTitle)) }
    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
