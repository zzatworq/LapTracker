package dev.zz.laptracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import android.widget.Toast

class CallActionReceiver : BroadcastReceiver() {
    companion object { const val ACTION_CALL="dev.zz.laptracker.action.CALL_NUMBER"; const val ACTION_END_CALL="dev.zz.laptracker.action.END_CALL"; const val EXTRA_NUMBER="number" }
    override fun onReceive(context:Context,intent:Intent){when(intent.action){ACTION_CALL->{val number=intent.getStringExtra(EXTRA_NUMBER)?.trim()?:return;if(context.checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED){Toast.makeText(context,"Allow phone-call permission first",Toast.LENGTH_LONG).show();return};context.getSharedPreferences(TrackingService.PREFS,Context.MODE_PRIVATE).edit().putString(TrackingService.PREF_PENDING_CALL_NUMBER,number).apply();try{context.startActivity(Intent(Intent.ACTION_CALL,Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));(context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(42)}catch(_:Exception){Toast.makeText(context,"The phone did not allow the call",Toast.LENGTH_LONG).show()}};ACTION_END_CALL->{try{val telecom=context.getSystemService(TelecomManager::class.java);@Suppress("DEPRECATION") val ended=telecom?.endCall()?:false;if(!ended)Toast.makeText(context,"Android did not allow this app to end the call",Toast.LENGTH_LONG).show()}catch(_:SecurityException){Toast.makeText(context,"Android restricts ending calls from this app",Toast.LENGTH_LONG).show()}catch(_:Exception){}}}}
}
