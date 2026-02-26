package de.gingerbeard3d.dbpendler.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import de.gingerbeard3d.dbpendler.data.PreferencesManager
import de.gingerbeard3d.dbpendler.data.SavedAlarm
import de.gingerbeard3d.dbpendler.receiver.AlarmReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class AlarmHelper(private val context: Context) {
    
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefsManager = PreferencesManager(context)
    
    /**
     * Set an alarm for a specific train departure
     * @param departureTime The departure time of the train
     * @param minutesBefore Minutes before departure to trigger alarm (e.g., 10 or 15)
     * @param trainName Name of the train (for notification)
     * @param fromStation Departure station
     * @param toStation Arrival station
     * @return The alarm ID if successful, null otherwise
     */
    fun setAlarm(
        departureTime: LocalDateTime,
        minutesBefore: Int,
        trainName: String,
        fromStation: String,
        toStation: String
    ): String? {
        // Calculate alarm time
        val alarmTime = departureTime.minusMinutes(minutesBefore.toLong())
        val alarmMillis = alarmTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        
        // Check if alarm time is in the future
        if (alarmMillis <= System.currentTimeMillis()) {
            Toast.makeText(context, "⚠️ Abfahrt liegt in der Vergangenheit!", Toast.LENGTH_SHORT).show()
            return null
        }
        
        // Check for exact alarm permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(context, "Bitte erlaube exakte Alarme in den Einstellungen", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return null
            }
        }
        
        // Generate unique alarm ID
        val alarmId = UUID.randomUUID().toString()
        
        // Create intent for alarm receiver
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "de.gingerbeard3d.dbpendler.ALARM_TRIGGERED"
            putExtra("alarm_id", alarmId)
            putExtra("train_name", trainName)
            putExtra("departure_time", departureTime.toString())
            putExtra("from_station", fromStation)
            putExtra("to_station", toStation)
            putExtra("minutes_before", minutesBefore)
        }
        
        // Create unique request code based on alarm ID
        val requestCode = alarmId.hashCode()
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Set exact alarm
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    alarmMillis,
                    pendingIntent
                )
            }
            
            val alarmTimeStr = alarmTime.format(DateTimeFormatter.ofPattern("HH:mm"))
            val departureTimeStr = departureTime.format(DateTimeFormatter.ofPattern("HH:mm"))
            
            // Save alarm to preferences
            val savedAlarm = SavedAlarm(
                id = alarmId,
                trainName = trainName,
                departureTime = departureTimeStr,
                alarmTime = alarmTimeStr,
                alarmTimeMillis = alarmMillis,
                fromStation = fromStation,
                toStation = toStation,
                minutesBefore = minutesBefore
            )
            
            CoroutineScope(Dispatchers.IO).launch {
                prefsManager.addAlarm(savedAlarm)
            }
            
            Toast.makeText(
                context, 
                "⏰ Wecker gestellt für $alarmTimeStr\n($minutesBefore Min vor $trainName)", 
                Toast.LENGTH_LONG
            ).show()
            
            return alarmId
        } catch (e: SecurityException) {
            Toast.makeText(context, "Fehler: Keine Berechtigung für Wecker", Toast.LENGTH_LONG).show()
            return null
        }
    }
    
    /**
     * Cancel an alarm by ID
     */
    fun cancelAlarm(alarmId: String) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val requestCode = alarmId.hashCode()
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.cancel(pendingIntent)
        
        // Remove from saved alarms
        CoroutineScope(Dispatchers.IO).launch {
            prefsManager.removeAlarm(alarmId)
        }
        
        Toast.makeText(context, "⏰ Wecker gelöscht", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Cancel an alarm by departure time (legacy)
     */
    fun cancelAlarm(departureTime: LocalDateTime) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val requestCode = departureTime.hashCode()
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.cancel(pendingIntent)
        Toast.makeText(context, "⏰ Wecker abgebrochen", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Check if exact alarms are allowed
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }
    
    /**
     * Open settings to enable exact alarms
     */
    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }
}
