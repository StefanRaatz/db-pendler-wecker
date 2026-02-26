package de.gingerbeard3d.dbpendler.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import de.gingerbeard3d.dbpendler.service.AlarmService

/**
 * BroadcastReceiver that triggers when an alarm fires.
 * Starts the AlarmService to play sound/vibration reliably in background.
 */
class AlarmReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        // Acquire wake lock to ensure device stays awake
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "DBPendler:AlarmWakeLock"
        )
        wakeLock.acquire(60_000) // 60 seconds max
        
        try {
            val alarmId = intent.getStringExtra("alarm_id") ?: ""
            val trainName = intent.getStringExtra("train_name") ?: "Zug"
            val departureTime = intent.getStringExtra("departure_time") ?: ""
            val fromStation = intent.getStringExtra("from_station") ?: ""
            val toStation = intent.getStringExtra("to_station") ?: ""
            val minutesBefore = intent.getIntExtra("minutes_before", 10)
            
            // Start the foreground service to handle the alarm
            AlarmService.startAlarm(
                context = context,
                alarmId = alarmId,
                trainName = trainName,
                departureTime = departureTime,
                fromStation = fromStation,
                toStation = toStation,
                minutesBefore = minutesBefore
            )
        } finally {
            // Wake lock will be released by service or after timeout
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
}
