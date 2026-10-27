package dev.zz.laptracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Fmt {
 fun km(meters:Double):String=String.format(Locale.US,"%.2f km",meters/1000.0)
 fun duration(ms:Long):String{val t=(ms/1000).coerceAtLeast(0);val h=t/3600;val m=(t%3600)/60;val s=t%60;return if(h>0)String.format(Locale.US,"%d:%02d:%02d",h,m,s)else String.format(Locale.US,"%02d:%02d",m,s)}
 fun speedKmh(ms:Double):String=String.format(Locale.US,"%.1f km/h",ms*3.6)
 fun dateTime(ms:Long):String=SimpleDateFormat("EEE d MMM yyyy, HH:mm",Locale.getDefault()).format(Date(ms))
 fun fileStamp(ms:Long):String=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date(ms))
 fun iso(ms:Long):String{val f=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);f.timeZone=TimeZone.getTimeZone("UTC");return f.format(Date(ms))}
 fun reasonLabel(reason:String):String=when(reason){"CALL_START"->"Call started";"CALL_END"->"Call ended";"INDRIVE"->"inDrive notification";else->reason}
 fun eventLine(e:Lap):String=buildString{append(reasonLabel(e.reason));if(!e.phoneNumber.isNullOrBlank())append(" · ").append(e.phoneNumber);if(!e.detail.isNullOrBlank())append("\n").append(e.detail);append("\n").append(dateTime(e.time))}
}
