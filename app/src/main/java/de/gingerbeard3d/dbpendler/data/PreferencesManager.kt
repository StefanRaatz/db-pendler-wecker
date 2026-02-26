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
        
        // Widget ID tracking
        private val ACTIVE_WIDGET_IDS = stringPreferencesKey("active_widget_ids")
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
