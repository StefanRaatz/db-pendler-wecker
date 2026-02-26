package de.gingerbeard3d.dbpendler.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import de.gingerbeard3d.dbpendler.MainActivity
import de.gingerbeard3d.dbpendler.R
import de.gingerbeard3d.dbpendler.alarm.AlarmHelper
import de.gingerbeard3d.dbpendler.api.Connection
import de.gingerbeard3d.dbpendler.api.DBApiClient
import de.gingerbeard3d.dbpendler.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class PendlerWidgetProvider : AppWidgetProvider() {
    
    companion object {
        const val ACTION_REFRESH = "de.gingerbeard3d.dbpendler.REFRESH_WIDGET"
        const val ACTION_SWAP = "de.gingerbeard3d.dbpendler.SWAP_STATIONS"
        const val ACTION_SET_ALARM = "de.gingerbeard3d.dbpendler.SET_ALARM"
        
        const val EXTRA_CONNECTION_INDEX = "connection_index"
        const val EXTRA_MINUTES_BEFORE = "minutes_before"
        
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        
        // Cache for connections
        private var cachedConnections: List<Connection> = emptyList()
        private var lastUpdate: Long = 0
        
        fun refreshAllWidgets(context: Context) {
            val intent = Intent(context, PendlerWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }
    }
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        when (intent.action) {
            ACTION_REFRESH -> {
                refreshWidgets(context)
            }
            ACTION_SWAP -> {
                handleSwap(context)
            }
            ACTION_SET_ALARM -> {
                val connectionIndex = intent.getIntExtra(EXTRA_CONNECTION_INDEX, -1)
                val minutesBefore = intent.getIntExtra(EXTRA_MINUTES_BEFORE, 10)
                handleSetAlarm(context, connectionIndex, minutesBefore)
            }
        }
    }
    
    override fun onEnabled(context: Context) {
        // Called when the first widget is created
    }
    
    override fun onDisabled(context: Context) {
        // Called when the last widget is disabled
    }
    
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId)
    }
    
    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        
        // Set up click listeners
        setupClickListeners(context, views, appWidgetId)
        
        // Load data and update UI
        scope.launch {
            try {
                loadAndDisplayData(context, views, appWidgetManager, appWidgetId)
            } catch (e: Exception) {
                showError(context, views, appWidgetManager, appWidgetId, e.message ?: "Fehler")
            }
        }
    }
    
    private fun setupClickListeners(context: Context, views: RemoteViews, appWidgetId: Int) {
        // Open app when header is clicked
        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_header, openAppPendingIntent)
        views.setOnClickPendingIntent(R.id.text_from_station, openAppPendingIntent)
        views.setOnClickPendingIntent(R.id.text_to_station, openAppPendingIntent)
        
        // Swap button
        val swapIntent = Intent(context, PendlerWidgetProvider::class.java).apply {
            action = ACTION_SWAP
        }
        val swapPendingIntent = PendingIntent.getBroadcast(
            context, 1, swapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_swap, swapPendingIntent)
        
        // Refresh button
        val refreshIntent = Intent(context, PendlerWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context, 2, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_refresh, refreshPendingIntent)
        
        // Alarm buttons for each connection (will be set up when connections are loaded)
    }
    
    private suspend fun loadAndDisplayData(
        context: Context,
        views: RemoteViews,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val prefsManager = PreferencesManager(context)
        val (fromStation, toStation) = prefsManager.getStations()
        
        if (fromStation == null || toStation == null) {
            // Show setup prompt
            views.setTextViewText(R.id.text_from_station, "Tippen zum Einrichten")
            views.setTextViewText(R.id.text_to_station, "Bahnhöfe auswählen →")
            views.setViewVisibility(R.id.connections_container, View.GONE)
            views.setViewVisibility(R.id.text_no_connections, View.VISIBLE)
            views.setTextViewText(R.id.text_no_connections, "Bitte Bahnhöfe in der App einstellen")
            appWidgetManager.updateAppWidget(appWidgetId, views)
            return
        }
        
        // Display station names
        views.setTextViewText(R.id.text_from_station, fromStation.name)
        views.setTextViewText(R.id.text_to_station, toStation.name)
        
        // Show loading state
        views.setViewVisibility(R.id.progress_loading, View.VISIBLE)
        views.setViewVisibility(R.id.connections_container, View.GONE)
        appWidgetManager.updateAppWidget(appWidgetId, views)
        
        // Fetch connections
        val apiClient = DBApiClient()
        val result = apiClient.getConnections(fromStation.id, toStation.id)
        
        views.setViewVisibility(R.id.progress_loading, View.GONE)
        
        result.fold(
            onSuccess = { connections ->
                cachedConnections = connections
                lastUpdate = System.currentTimeMillis()
                
                if (connections.isEmpty()) {
                    views.setViewVisibility(R.id.connections_container, View.GONE)
                    views.setViewVisibility(R.id.text_no_connections, View.VISIBLE)
                    views.setTextViewText(R.id.text_no_connections, "Keine Verbindungen gefunden")
                } else {
                    views.setViewVisibility(R.id.connections_container, View.VISIBLE)
                    views.setViewVisibility(R.id.text_no_connections, View.GONE)
                    displayConnections(context, views, connections.take(3))
                }
            },
            onFailure = { error ->
                views.setViewVisibility(R.id.connections_container, View.GONE)
                views.setViewVisibility(R.id.text_no_connections, View.VISIBLE)
                views.setTextViewText(R.id.text_no_connections, "Fehler: ${error.message}")
            }
        )
        
        // Update time
        val updateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        views.setTextViewText(R.id.text_last_update, "Aktualisiert: $updateTime")
        
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
    
    private fun displayConnections(context: Context, views: RemoteViews, connections: List<Connection>) {
        // Connection 1
        if (connections.isNotEmpty()) {
            val conn = connections[0]
            views.setViewVisibility(R.id.connection_1, View.VISIBLE)
            views.setTextViewText(R.id.conn1_train, "${conn.trainIcon} ${conn.trainName}")
            views.setTextViewText(R.id.conn1_time, "Ab: ${conn.departureTime}  An: ${conn.arrivalTime}")
            views.setTextViewText(R.id.conn1_platform, if (conn.platform.isNotEmpty()) "Gl. ${conn.platform}" else "")
            
            // Alarm buttons
            setupAlarmButton(context, views, R.id.conn1_alarm_10, 0, 10)
            setupAlarmButton(context, views, R.id.conn1_alarm_15, 0, 15)
        } else {
            views.setViewVisibility(R.id.connection_1, View.GONE)
        }
        
        // Connection 2
        if (connections.size > 1) {
            val conn = connections[1]
            views.setViewVisibility(R.id.connection_2, View.VISIBLE)
            views.setTextViewText(R.id.conn2_train, "${conn.trainIcon} ${conn.trainName}")
            views.setTextViewText(R.id.conn2_time, "Ab: ${conn.departureTime}  An: ${conn.arrivalTime}")
            views.setTextViewText(R.id.conn2_platform, if (conn.platform.isNotEmpty()) "Gl. ${conn.platform}" else "")
            
            setupAlarmButton(context, views, R.id.conn2_alarm_10, 1, 10)
            setupAlarmButton(context, views, R.id.conn2_alarm_15, 1, 15)
        } else {
            views.setViewVisibility(R.id.connection_2, View.GONE)
        }
        
        // Connection 3
        if (connections.size > 2) {
            val conn = connections[2]
            views.setViewVisibility(R.id.connection_3, View.VISIBLE)
            views.setTextViewText(R.id.conn3_train, "${conn.trainIcon} ${conn.trainName}")
            views.setTextViewText(R.id.conn3_time, "Ab: ${conn.departureTime}  An: ${conn.arrivalTime}")
            views.setTextViewText(R.id.conn3_platform, if (conn.platform.isNotEmpty()) "Gl. ${conn.platform}" else "")
            
            setupAlarmButton(context, views, R.id.conn3_alarm_10, 2, 10)
            setupAlarmButton(context, views, R.id.conn3_alarm_15, 2, 15)
        } else {
            views.setViewVisibility(R.id.connection_3, View.GONE)
        }
    }
    
    private fun setupAlarmButton(
        context: Context,
        views: RemoteViews,
        buttonId: Int,
        connectionIndex: Int,
        minutesBefore: Int
    ) {
        val intent = Intent(context, PendlerWidgetProvider::class.java).apply {
            action = ACTION_SET_ALARM
            putExtra(EXTRA_CONNECTION_INDEX, connectionIndex)
            putExtra(EXTRA_MINUTES_BEFORE, minutesBefore)
        }
        val requestCode = buttonId + connectionIndex * 100 + minutesBefore
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(buttonId, pendingIntent)
    }
    
    private fun showError(
        context: Context,
        views: RemoteViews,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        message: String
    ) {
        views.setViewVisibility(R.id.progress_loading, View.GONE)
        views.setViewVisibility(R.id.connections_container, View.GONE)
        views.setViewVisibility(R.id.text_no_connections, View.VISIBLE)
        views.setTextViewText(R.id.text_no_connections, "Fehler: $message")
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
    
    private fun refreshWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, PendlerWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }
    
    private fun handleSwap(context: Context) {
        scope.launch {
            val prefsManager = PreferencesManager(context)
            prefsManager.swapStations()
            refreshWidgets(context)
        }
    }
    
    private fun handleSetAlarm(context: Context, connectionIndex: Int, minutesBefore: Int) {
        if (connectionIndex < 0 || connectionIndex >= cachedConnections.size) {
            return
        }
        
        val connection = cachedConnections[connectionIndex]
        val alarmHelper = AlarmHelper(context)
        
        alarmHelper.setAlarm(
            departureTime = connection.departureDateTime,
            minutesBefore = minutesBefore,
            trainName = connection.trainName,
            fromStation = connection.departureStation,
            toStation = connection.arrivalStation
        )
    }
}
