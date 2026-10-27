package dev.zz.laptracker

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

data class LiveState(val running:Boolean=false,val locationOn:Boolean=true,val hasFix:Boolean=false,val sessionId:Long=0L,val startTime:Long=0L,val endTime:Long=0L,val distanceM:Double=0.0,val speedMs:Float=0f,val accelerationMs2:Double?=null,val accuracyM:Float?=null,val lapCount:Int=0,val currentLapDistanceM:Double=0.0,val currentLapStartTime:Long=0L,val laps:List<Lap> = emptyList())
object TrackerBus{ @Volatile var state:LiveState=LiveState();private set;private val listeners=CopyOnWriteArrayList<()->Unit>();private val main=Handler(Looper.getMainLooper());fun publish(s:LiveState){state=s;main.post{for(l in listeners)l()}};fun addListener(l:()->Unit){listeners.add(l)};fun removeListener(l:()->Unit){listeners.remove(l)} }
