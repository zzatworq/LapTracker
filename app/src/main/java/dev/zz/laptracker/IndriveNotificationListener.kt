package dev.zz.laptracker

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class IndriveNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn:StatusBarNotification){val pkg=sbn.packageName.lowercase();if(!pkg.contains("indrive"))return;val extras=sbn.notification.extras;val title=extras.getCharSequence("android.title")?.toString().orEmpty();val text=extras.getCharSequence("android.text")?.toString().orEmpty();if(title.isBlank()&&text.isBlank())return;startService(Intent(this,TrackingService::class.java).setAction(TrackingService.ACTION_EXTERNAL_NOTIFICATION).putExtra(TrackingService.EXTRA_SOURCE,"inDrive").putExtra(TrackingService.EXTRA_TITLE,title).putExtra(TrackingService.EXTRA_TEXT,text))}
}
