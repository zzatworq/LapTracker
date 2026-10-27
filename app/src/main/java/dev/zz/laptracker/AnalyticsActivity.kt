package dev.zz.laptracker

import android.app.*
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import java.util.Locale

class AnalyticsActivity:Activity(){
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16));setBackgroundColor(Color.BLACK)};val head=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL};head.addView(TextView(this).apply{text="Ride & Fuel Analysis";textSize=22f;setTypeface(null,Typeface.BOLD);setTextColor(Color.WHITE)},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));head.addView(Button(this).apply{text="Add Fuel";setOnClickListener{addFuel()}});root.addView(head);root.addView(TextView(this).apply{text="Honda CD70 · GPS acceleration · self-calibrating fuel estimate";textSize=13f;setTextColor(Color.LTGRAY);setPadding(0,0,0,dp(12))});val list=ListView(this).apply{setBackgroundColor(Color.BLACK)};root.addView(list,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));setContentView(root);Thread{render(list)}.start()}
 private fun render(list:ListView){val db=Db.get(this);val calibrated=db.calibratedKmPerLitre();val sessions=db.listSessions();val rows=ArrayList<String>();rows.add(if(calibrated!=null)"Measured calibration: ${one(calibrated)} km/L" else "CD70 baseline: 55.0 km/L · Add fuel entries after full refills to calibrate");rows.add("Fuel entries: ${db.getFuelEntries().size}");for(s in sessions){val end=s.endTime?:System.currentTimeMillis();val a=FuelModel.analyse(db.getPoints(s.id),s.distanceM,end-s.startTime,calibrated);rows.add("${Fmt.dateTime(s.startTime)}\n${Fmt.km(s.distanceM)} · Avg ${one(a.averageSpeedKmh)} km/h · Max ${one(a.maxSpeedKmh)} km/h\nMax acceleration +${two(a.maxAcceleration)} m/s² · max braking -${two(a.maxBraking)} m/s²\nAcceleration events ${a.accelerationEvents} · hard accel ${a.hardAccelerations} · hard braking ${a.hardBrakings}\nEstimated fuel ${two(a.estimatedFuelLitres)} L · ${one(a.estimatedKmPerLitre)} km/L · ${a.confidence} confidence")};runOnUiThread{list.adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,rows.ifEmpty{listOf("No rides yet. Record a ride first.")})}}
 private fun addFuel(){val input=EditText(this).apply{hint="Litres added";inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL};AlertDialog.Builder(this).setTitle("Record fuel refill").setMessage("For best calibration, record a near-full refill each time.").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save"){_,_->val litres=input.text.toString().toDoubleOrNull();if(litres==null||litres<=0){Toast.makeText(this,"Enter a valid number of litres",Toast.LENGTH_LONG).show()}else{Db.get(this).addFuelEntry(litres);recreate()}}.show()}
 private fun one(v:Double)=String.format(Locale.US,"%.1f",v);private fun two(v:Double)=String.format(Locale.US,"%.2f",v)
}