package dev.zz.laptracker

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*

class HistoryActivity:Activity(){
 private lateinit var listView:ListView;private lateinit var emptyView:TextView;private var sessions:List<Session> = emptyList();private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16));setBackgroundColor(Color.BLACK)};root.addView(TextView(this).apply{text="Ride History";textSize=22f;setTypeface(null,Typeface.BOLD);setTextColor(Color.WHITE);setPadding(0,0,0,dp(8))});emptyView=TextView(this).apply{text="No rides detected yet.";setTextColor(Color.LTGRAY);visibility=View.GONE};root.addView(emptyView);listView=ListView(this).apply{setBackgroundColor(Color.BLACK);setOnItemClickListener{_,_,p,_->if(p in sessions.indices)showActions(sessions[p])}};root.addView(listView,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));setContentView(root)}
 override fun onResume(){super.onResume();load()}
 private fun describe(s:Session):String{val end=s.endTime?:System.currentTimeMillis();val live=if(s.endTime==null)" (active)" else "";return Fmt.dateTime(s.startTime)+live+"\n${Fmt.km(s.distanceM)} · ${Fmt.duration(end-s.startTime)} · ${s.lapCount} events"}
 private fun load(){Thread{val db=Db.get(this);if(!TrackerBus.state.running)db.recoverOpenSessions();val list=db.listSessions();runOnUiThread{sessions=list;listView.adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,list.map{describe(it)});emptyView.visibility=if(list.isEmpty())View.VISIBLE else View.GONE}}.start()}
 private fun showActions(s:Session){AlertDialog.Builder(this).setTitle(Fmt.dateTime(s.startTime)).setItems(arrayOf("View ride events","Export GPX","Export events CSV","Delete")){_,w->when(w){0->showEvents(s);1->exportGpx(s);2->exportCsv(s);3->confirmDelete(s)}}.show()}
 private fun showEvents(s:Session){Thread{val events=Db.get(this).getLaps(s.id);runOnUiThread{if(events.isEmpty())toast("No events recorded")else AlertDialog.Builder(this).setTitle("Ride events").setItems(events.map{Fmt.eventLine(it)}.toTypedArray(),null).setPositiveButton("Close",null).show()}}.start()}
 private fun exportGpx(s:Session){Thread{val db=Db.get(this);val gpx=Exporter.buildGpx(s,db.getPoints(s.id),db.getLaps(s.id));val name="laptracker_ride_"+Fmt.fileStamp(s.startTime)+".gpx";val uri=Exporter.saveToDownloads(this,name,"application/gpx+xml",gpx);runOnUiThread{if(uri==null)toast("Export failed")else{toast("Saved to Downloads/LapTracker/$name");share(uri,"application/gpx+xml","Share GPX")}}}.start()}
 private fun exportCsv(s:Session){Thread{val csv=Exporter.buildLapsCsv(Db.get(this).getLaps(s.id));val name="laptracker_ride_events_"+Fmt.fileStamp(s.startTime)+".csv";val uri=Exporter.saveToDownloads(this,name,"text/csv",csv);runOnUiThread{toast(if(uri==null)"Export failed" else "Saved to Downloads/LapTracker/$name")}}.start()}
 private fun confirmDelete(s:Session){if(TrackerBus.state.running&&TrackerBus.state.sessionId==s.id){toast("Wait until this ride ends before deleting it");return};AlertDialog.Builder(this).setTitle("Delete this ride?").setMessage("The route and ride events will be removed permanently.").setPositiveButton("Delete"){_,_->Thread{Db.get(this).deleteSession(s.id);runOnUiThread{load()}}.start()}.setNegativeButton("Cancel",null).show()}
 private fun share(uri:Uri,mime:String,title:String){val send=Intent(Intent.ACTION_SEND);send.type=mime;send.putExtra(Intent.EXTRA_STREAM,uri);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(send,title))}
 private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
