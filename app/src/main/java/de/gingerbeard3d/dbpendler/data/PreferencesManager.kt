package de.gingerbeard3d.dbpendler.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDateTime

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pendler_settings")

class PreferencesManager(private val context: Context) {
    
    companion object {
        // Station keys
        private val FROM_STATION_ID = stringPreferencesKey("from_station_id")
        private val FROM_STATION_NAME = stringPreferencesKey("from_station_name")
        private val TO_STATION_ID = stringPreferencesKey("to_station_id")
        private val TO_STATION_NAME = stringPreferencesKey("to_station_name")
        
        // Alarm settings
        private val DEFAULT_ALARM_MINUTES = intPreferencesKey("default_alarm_minutes")
        private val SAVED_ALARMS = stringPreferencesKey("saved_alarms")
        
        // Widget ID tracking
        private val ACTIVE_WIDGET_IDS = stringPreferencesKey("active_widget_ids")
        
        // Theme setting: "system", "light", "dark"
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        
        private val json = Json { ignoreUnknownKeys = true }
        
        // Theme mode constants
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
    
    // Flow for from station
    val fromStationFlow: Flow<StationData?> = context.dataStore.data.map { prefs ->
        val id = prefs[FROM_STATION_ID]
        val name = prefs[FROM_STATION_NAME]
        if (id != null && name != null) StationData(id, name) else null
    }
    
    // Flow for to station
    val toStationFlow: Flow<StationData?> = context.dataStore.data.map { prefs ->
        val id = prefs[TO_STATION_ID]
        val name = prefs[TO_STATION_NAME]
        if (id != null && name != null) StationData(id, name) else null
    }
    
    // Flow for default alarm minutes
    val defaultAlarmMinutesFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[DEFAULT_ALARM_MINUTES] ?: 10
    }
    
    // Flow for saved alarms
    val savedAlarmsFlow: Flow<List<SavedAlarm>> = context.dataStore.data.map { prefs ->
        val alarmsJson = prefs[SAVED_ALARMS] ?: "[]"
        try {
            json.decodeFromString<List<SavedAlarm>>(alarmsJson)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // Flow for theme mode
    val themeModeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE] ?: THEME_SYSTEM
    }
    
    // Get theme mode synchronously
    suspend fun getThemeMode(): String {
        val prefs = context.dataStore.data.first()
        return prefs[THEME_MODE] ?: THEME_SYSTEM
    }
    
    // Save theme mode
    suspend fun saveThemeMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[THEME_MODE] = mode
        }
    }
    
    // Save from station
    suspend fun saveFromStation(id: String, name: String) {
        context.dataStore.edit { prefs ->
            prefs[FROM_STATION_ID] = id
            prefs[FROM_STATION_NAME] = name
        }
    }
    
    // Save to station
    suspend fun saveToStation(id: String, name: String) {
        context.dataStore.edit { prefs ->
            prefs[TO_STATION_ID] = id
            prefs[TO_STATION_NAME] = name
        }
    }
    
    // Swap stations
    suspend fun swapStations() {
        val currentFromId = context.dataStore.data.first()[FROM_STATION_ID]
        val currentFromName = context.dataStore.data.first()[FROM_STATION_NAME]
        val currentToId = context.dataStore.data.first()[TO_STATION_ID]
        val currentToName = context.dataStore.data.first()[TO_STATION_NAME]
        
        context.dataStore.edit { prefs ->
            if (currentToId != null && currentToName != null) {
                prefs[FROM_STATION_ID] = currentToId
                prefs[FROM_STATION_NAME] = currentToName
            } else {
                prefs.remove(FROM_STATION_ID)
                prefs.remove(FROM_STATION_NAME)
            }
            
            if (currentFromId != null && currentFromName != null) {
                prefs[TO_STATION_ID] = currentFromId
                prefs[TO_STATION_NAME] = currentFromName
            } else {
                prefs.remove(TO_STATION_ID)
                prefs.remove(TO_STATION_NAME)
            }
        }
    }
    
    // Save default alarm minutes
    suspend fun saveDefaultAlarmMinutes(minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[DEFAULT_ALARM_MINUTES] = minutes
        }
    }
    
    // Add alarm to saved list
    suspend fun addAlarm(alarm: SavedAlarm) {
        context.dataStore.edit { prefs ->
            val currentJson = prefs[SAVED_ALARMS] ?: "[]"
            val alarms = try {
                json.decodeFromString<MutableList<SavedAlarm>>(currentJson)
            } catch (e: Exception) {
                mutableListOf()
            }
            
            // Remove expired alarms and add new one
            val now = System.currentTimeMillis()
            alarms.removeAll { it.alarmTimeMillis < now }
            alarms.add(alarm)
            
            // Sort by alarm time
            alarms.sortBy { it.alarmTimeMillis }
            
            prefs[SAVED_ALARMS] = json.encodeToString(alarms)
        }
    }
    
    // Remove alarm from saved list
    suspend fun removeAlarm(alarmId: String) {
        context.dataStore.edit { prefs ->
            val currentJson = prefs[SAVED_ALARMS] ?: "[]"
            val alarms = try {
                json.decodeFromString<MutableList<SavedAlarm>>(currentJson)
            } catch (e: Exception) {
                mutableListOf()
            }
            
            alarms.removeAll { it.id == alarmId }
            prefs[SAVED_ALARMS] = json.encodeToString(alarms)
        }
    }
    
    // Update existing alarm
    suspend fun updateAlarm(updatedAlarm: SavedAlarm) {
        context.dataStore.edit { prefs ->
            val currentJson = prefs[SAVED_ALARMS] ?: "[]"
            val alarms = try {
                json.decodeFromString<MutableList<SavedAlarm>>(currentJson)
            } catch (e: Exception) {
                mutableListOf()
            }
            
            // Find and replace the alarm with matching id
            val index = alarms.indexOfFirst { it.id == updatedAlarm.id }
            if (index >= 0) {
                alarms[index] = updatedAlarm
            }
            
            // Sort by alarm time
            alarms.sortBy { it.alarmTimeMillis }
            
            prefs[SAVED_ALARMS] = json.encodeToString(alarms)
        }
    }
    
    // Get single alarm by ID
    suspend fun getAlarmById(alarmId: String): SavedAlarm? {
        val alarms = getSavedAlarms()
        return alarms.find { it.id == alarmId }
    }
    
    // Get all saved alarms (sync)
    suspend fun getSavedAlarms(): List<SavedAlarm> {
        val prefs = context.dataStore.data.first()
        val alarmsJson = prefs[SAVED_ALARMS] ?: "[]"
        return try {
            val alarms = json.decodeFromString<MutableList<SavedAlarm>>(alarmsJson)
            // Filter out expired alarms
            val now = System.currentTimeMillis()
            alarms.filter { it.alarmTimeMillis >= now }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // Clear expired alarms
    suspend fun cleanupExpiredAlarms() {
        context.dataStore.edit { prefs ->
            val currentJson = prefs[SAVED_ALARMS] ?: "[]"
            val alarms = try {
                json.decodeFromString<MutableList<SavedAlarm>>(currentJson)
            } catch (e: Exception) {
                mutableListOf()
            }
            
            val now = System.currentTimeMillis()
            alarms.removeAll { it.alarmTimeMillis < now }
            prefs[SAVED_ALARMS] = json.encodeToString(alarms)
        }
    }
    
    // Get current stations synchronously (for widget updates)
    suspend fun getStations(): Pair<StationData?, StationData?> {
        val prefs = context.dataStore.data.first()
        val fromId = prefs[FROM_STATION_ID]
        val fromName = prefs[FROM_STATION_NAME]
        val toId = prefs[TO_STATION_ID]
        val toName = prefs[TO_STATION_NAME]
        
        val from = if (fromId != null && fromName != null) StationData(fromId, fromName) else null
        val to = if (toId != null && toName != null) StationData(toId, toName) else null
        
        return Pair(from, to)
    }
    
    // Register widget ID
    suspend fun registerWidget(widgetId: Int) {
        context.dataStore.edit { prefs ->
            val current = prefs[ACTIVE_WIDGET_IDS] ?: ""
            val ids = current.split(",").filter { it.isNotEmpty() }.toMutableSet()
            ids.add(widgetId.toString())
            prefs[ACTIVE_WIDGET_IDS] = ids.joinToString(",")
        }
    }
    
    // Unregister widget ID
    suspend fun unregisterWidget(widgetId: Int) {
        context.dataStore.edit { prefs ->
            val current = prefs[ACTIVE_WIDGET_IDS] ?: ""
            val ids = current.split(",").filter { it.isNotEmpty() && it != widgetId.toString() }
            prefs[ACTIVE_WIDGET_IDS] = ids.joinToString(",")
        }
    }
    
    // Get all widget IDs
    suspend fun getWidgetIds(): List<Int> {
        val prefs = context.dataStore.data.first()
        val ids = prefs[ACTIVE_WIDGET_IDS] ?: ""
        return ids.split(",").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
    }
}

data class StationData(
    val id: String,
    val name: String
)

@Serializable
data class SavedAlarm(
    val id: String,
    val trainName: String,
    val departureTime: String,
    val alarmTime: String,
    val alarmTimeMillis: Long,
    val fromStation: String,
    val toStation: String,
    val minutesBefore: Int,
    val volume: Float = 0.8f,  // 0.0 to 1.0
    val soundUri: String = ""  // Empty = default alarm
)
