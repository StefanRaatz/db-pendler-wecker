package de.gingerbeard3d.dbpendler.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import de.gingerbeard3d.dbpendler.MainActivity
import de.gingerbeard3d.dbpendler.R
import kotlinx.coroutines.*

/**
 * Foreground Service to play alarm sound reliably even when app is in background
 * or screen is locked.
 */
class AlarmService : Service() {
    
    companion object {
        const val CHANNEL_ID = "pendler_alarm_channel"
        const val NOTIFICATION_ID = 1001
        
        const val ACTION_START_ALARM = "de.gingerbeard3d.dbpendler.START_ALARM"
        const val ACTION_STOP_ALARM = "de.gingerbeard3d.dbpendler.STOP_ALARM"
        
        const val EXTRA_TRAIN_NAME = "train_name"
        const val EXTRA_DEPARTURE_TIME = "departure_time"
        const val EXTRA_FROM_STATION = "from_station"
        const val EXTRA_TO_STATION = "to_station"
        const val EXTRA_MINUTES_BEFORE = "minutes_before"
        const val EXTRA_ALARM_ID = "alarm_id"
        
        fun startAlarm(
            context: Context,
            alarmId: String,
            trainName: String,
            departureTime: String,
            fromStation: String,
            toStation: String,
            minutesBefore: Int
        ) {
            val intent = Intent(context, AlarmService::class.java).apply {
                action = ACTION_START_ALARM
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_TRAIN_NAME, trainName)
                putExtra(EXTRA_DEPARTURE_TIME, departureTime)
                putExtra(EXTRA_FROM_STATION, fromStation)
                putExtra(EXTRA_TO_STATION, toStation)
                putExtra(EXTRA_MINUTES_BEFORE, minutesBefore)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopAlarm(context: Context) {
            val intent = Intent(context, AlarmService::class.java).apply {
                action = ACTION_STOP_ALARM
            }
            context.startService(intent)
        }
    }
    
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stopJob: Job? = null
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ALARM -> {
                val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: ""
                val trainName = intent.getStringExtra(EXTRA_TRAIN_NAME) ?: "Zug"
                val departureTime = intent.getStringExtra(EXTRA_DEPARTURE_TIME) ?: ""
                val fromStation = intent.getStringExtra(EXTRA_FROM_STATION) ?: ""
                val toStation = intent.getStringExtra(EXTRA_TO_STATION) ?: ""
                val minutesBefore = intent.getIntExtra(EXTRA_MINUTES_BEFORE, 10)
                
                // Start foreground with notification
                val notification = createAlarmNotification(
                    trainName, departureTime, fromStation, toStation, minutesBefore
                )
                startForeground(NOTIFICATION_ID, notification)
                
                // Start alarm sound and vibration
                startAlarmSound()
                startVibration()
                
                // Auto-stop after 60 seconds
                stopJob?.cancel()
                stopJob = serviceScope.launch {
                    delay(60_000)
                    stopSelf()
                }
            }
            ACTION_STOP_ALARM -> {
                stopAlarmSound()
                stopVibration()
                stopSelf()
            }
        }
        
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        stopAlarmSound()
        stopVibration()
        stopJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pendler Wecker",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Benachrichtigungen für Zug-Abfahrten"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                
                // Set alarm sound on channel
                val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(alarmSound, audioAttributes)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createAlarmNotification(
        trainName: String,
        departureTime: String,
        fromStation: String,
        toStation: String,
        minutesBefore: Int
    ): Notification {
        // Intent to open app
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Intent to stop alarm
        val stopIntent = Intent(this, AlarmService::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle("🚆 Zeit zum Aufbruch!")
            .setContentText("$trainName fährt in $minutesBefore Minuten")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$trainName fährt in $minutesBefore Minuten\n\n📍 $fromStation → $toStation"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(openPendingIntent, true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Alarm stoppen",
                stopPendingIntent
            )
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
    }
    
    private fun startAlarmSound() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmService, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun stopAlarmSound() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
    }
    
    private fun startVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            
            val pattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Repeat indefinitely (index 0 = start from beginning)
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun stopVibration() {
        vibrator?.cancel()
        vibrator = null
    }
}
