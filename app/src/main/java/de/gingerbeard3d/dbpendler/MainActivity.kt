package de.gingerbeard3d.dbpendler

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import de.gingerbeard3d.dbpendler.alarm.AlarmHelper
import de.gingerbeard3d.dbpendler.api.DBApiClient
import de.gingerbeard3d.dbpendler.api.Station
import de.gingerbeard3d.dbpendler.data.PreferencesManager
import de.gingerbeard3d.dbpendler.data.SavedAlarm
import de.gingerbeard3d.dbpendler.databinding.ActivityMainBinding
import de.gingerbeard3d.dbpendler.widget.PendlerWidgetProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PreferencesManager
    private lateinit var apiClient: DBApiClient
    private lateinit var alarmHelper: AlarmHelper
    
    private var fromStationSearchJob: Job? = null
    private var toStationSearchJob: Job? = null
    
    private var fromStations: List<Station> = emptyList()
    private var toStations: List<Station> = emptyList()
    
    private var selectedFromStation: Station? = null
    private var selectedToStation: Station? = null
    
    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefsManager = PreferencesManager(this)
        apiClient = DBApiClient()
        alarmHelper = AlarmHelper(this)
        
        setupUI()
        loadSavedStations()
        observeAlarms()
        requestPermissions()
    }
    
    private fun setupUI() {
        // From station autocomplete
        binding.editFromStation.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.length >= 2) {
                    searchFromStation(query)
                }
            }
        })
        
        binding.editFromStation.setOnItemClickListener { _, _, position, _ ->
            if (position < fromStations.size) {
                selectedFromStation = fromStations[position]
                saveFromStation()
                // Close dropdown and hide keyboard
                binding.editFromStation.dismissDropDown()
                hideKeyboard()
            }
        }
        
        // To station autocomplete
        binding.editToStation.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.length >= 2) {
                    searchToStation(query)
                }
            }
        })
        
        binding.editToStation.setOnItemClickListener { _, _, position, _ ->
            if (position < toStations.size) {
                selectedToStation = toStations[position]
                saveToStation()
                // Close dropdown and hide keyboard
                binding.editToStation.dismissDropDown()
                hideKeyboard()
            }
        }
        
        // Swap button
        binding.btnSwap.setOnClickListener {
            swapStations()
        }
        
        // Refresh connections button
        binding.btnRefresh.setOnClickListener {
            refreshConnections()
        }
        
        // Check alarm permission
        binding.btnCheckAlarmPermission.setOnClickListener {
            if (!alarmHelper.canScheduleExactAlarms()) {
                alarmHelper.openExactAlarmSettings()
            } else {
                Toast.makeText(this, "✅ Exakte Alarme sind bereits erlaubt", Toast.LENGTH_SHORT).show()
            }
        }
        
        updateAlarmPermissionStatus()
    }
    
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }
    
    private fun observeAlarms() {
        lifecycleScope.launch {
            prefsManager.savedAlarmsFlow.collectLatest { alarms ->
                displayAlarms(alarms.filter { it.alarmTimeMillis > System.currentTimeMillis() })
            }
        }
    }
    
    private fun displayAlarms(alarms: List<SavedAlarm>) {
        if (alarms.isEmpty()) {
            binding.cardAlarms.visibility = View.GONE
            return
        }
        
        binding.cardAlarms.visibility = View.VISIBLE
        binding.alarmsContainer.removeAllViews()
        
        for (alarm in alarms) {
            val alarmView = layoutInflater.inflate(R.layout.item_alarm, binding.alarmsContainer, false)
            
            alarmView.findViewById<TextView>(R.id.text_alarm_time).text = alarm.alarmTime
            alarmView.findViewById<TextView>(R.id.text_train_info).text = 
                "🚆 ${alarm.trainName} um ${alarm.departureTime}"
            alarmView.findViewById<TextView>(R.id.text_route).text = 
                "${alarm.fromStation} → ${alarm.toStation}"
            
            alarmView.findViewById<ImageButton>(R.id.btn_delete_alarm).setOnClickListener {
                alarmHelper.cancelAlarm(alarm.id)
                // UI updates via flow observer
            }
            
            binding.alarmsContainer.addView(alarmView)
        }
    }
    
    private fun loadSavedStations() {
        lifecycleScope.launch {
            val (from, to) = prefsManager.getStations()
            
            from?.let {
                binding.editFromStation.setText(it.name)
                selectedFromStation = Station(value = it.name, id = it.id)
            }
            
            to?.let {
                binding.editToStation.setText(it.name)
                selectedToStation = Station(value = it.name, id = it.id)
            }
            
            if (from != null && to != null) {
                refreshConnections()
            }
            
            // Cleanup expired alarms
            prefsManager.cleanupExpiredAlarms()
        }
    }
    
    private fun searchFromStation(query: String) {
        fromStationSearchJob?.cancel()
        fromStationSearchJob = lifecycleScope.launch {
            delay(300) // Debounce
            
            binding.progressFrom.visibility = View.VISIBLE
            
            apiClient.searchStations(query).fold(
                onSuccess = { stations ->
                    fromStations = stations
                    val adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_dropdown_item_1line,
                        stations.map { it.displayName }
                    )
                    binding.editFromStation.setAdapter(adapter)
                    adapter.notifyDataSetChanged()
                    if (binding.editFromStation.hasFocus()) {
                        binding.editFromStation.showDropDown()
                    }
                },
                onFailure = { error ->
                    Toast.makeText(this@MainActivity, "Fehler: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            )
            
            binding.progressFrom.visibility = View.GONE
        }
    }
    
    private fun searchToStation(query: String) {
        toStationSearchJob?.cancel()
        toStationSearchJob = lifecycleScope.launch {
            delay(300) // Debounce
            
            binding.progressTo.visibility = View.VISIBLE
            
            apiClient.searchStations(query).fold(
                onSuccess = { stations ->
                    toStations = stations
                    val adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_dropdown_item_1line,
                        stations.map { it.displayName }
                    )
                    binding.editToStation.setAdapter(adapter)
                    adapter.notifyDataSetChanged()
                    if (binding.editToStation.hasFocus()) {
                        binding.editToStation.showDropDown()
                    }
                },
                onFailure = { error ->
                    Toast.makeText(this@MainActivity, "Fehler: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            )
            
            binding.progressTo.visibility = View.GONE
        }
    }
    
    private fun saveFromStation() {
        selectedFromStation?.let { station ->
            lifecycleScope.launch {
                prefsManager.saveFromStation(station.id, station.displayName)
                Toast.makeText(this@MainActivity, "✅ Startbahnhof gespeichert", Toast.LENGTH_SHORT).show()
                PendlerWidgetProvider.refreshAllWidgets(this@MainActivity)
                refreshConnections()
            }
        }
    }
    
    private fun saveToStation() {
        selectedToStation?.let { station ->
            lifecycleScope.launch {
                prefsManager.saveToStation(station.id, station.displayName)
                Toast.makeText(this@MainActivity, "✅ Zielbahnhof gespeichert", Toast.LENGTH_SHORT).show()
                PendlerWidgetProvider.refreshAllWidgets(this@MainActivity)
                refreshConnections()
            }
        }
    }
    
    private fun swapStations() {
        lifecycleScope.launch {
            prefsManager.swapStations()
            
            // Update UI
            val temp = binding.editFromStation.text.toString()
            binding.editFromStation.setText(binding.editToStation.text.toString())
            binding.editToStation.setText(temp)
            
            // Swap selected stations
            val tempStation = selectedFromStation
            selectedFromStation = selectedToStation
            selectedToStation = tempStation
            
            Toast.makeText(this@MainActivity, "🔄 Bahnhöfe getauscht", Toast.LENGTH_SHORT).show()
            PendlerWidgetProvider.refreshAllWidgets(this@MainActivity)
            refreshConnections()
        }
    }
    
    private fun refreshConnections() {
        if (selectedFromStation == null || selectedToStation == null) {
            binding.textConnectionsStatus.text = "Bitte Start und Ziel auswählen"
            binding.connectionsContainer.visibility = View.GONE
            return
        }
        
        lifecycleScope.launch {
            binding.progressConnections.visibility = View.VISIBLE
            binding.textConnectionsStatus.text = "Verbindungen werden geladen..."
            binding.connectionsContainer.visibility = View.GONE
            
            apiClient.getConnections(
                selectedFromStation!!.id,
                selectedToStation!!.id
            ).fold(
                onSuccess = { connections ->
                    binding.progressConnections.visibility = View.GONE
                    
                    if (connections.isEmpty()) {
                        binding.textConnectionsStatus.text = "Keine Verbindungen gefunden"
                        binding.connectionsContainer.visibility = View.GONE
                    } else {
                        binding.textConnectionsStatus.text = "Nächste Verbindungen:"
                        binding.connectionsContainer.visibility = View.VISIBLE
                        displayConnections(connections.take(5))
                    }
                },
                onFailure = { error ->
                    binding.progressConnections.visibility = View.GONE
                    binding.textConnectionsStatus.text = "Fehler: ${error.message}"
                    binding.connectionsContainer.visibility = View.GONE
                }
            )
        }
    }
    
    private fun displayConnections(connections: List<de.gingerbeard3d.dbpendler.api.Connection>) {
        binding.connectionsContainer.removeAllViews()
        
        for (connection in connections) {
            val connectionView = layoutInflater.inflate(R.layout.item_connection, binding.connectionsContainer, false)
            
            connectionView.findViewById<android.widget.TextView>(R.id.text_train).text = 
                "${connection.trainIcon} ${connection.trainName}"
            connectionView.findViewById<android.widget.TextView>(R.id.text_times).text = 
                "Ab: ${connection.departureTime}  An: ${connection.arrivalTime}"
            connectionView.findViewById<android.widget.TextView>(R.id.text_platform).text = 
                if (connection.platform.isNotEmpty()) "Gleis ${connection.platform}" else ""
            
            // Alarm buttons
            connectionView.findViewById<android.widget.Button>(R.id.btn_alarm_10).setOnClickListener {
                alarmHelper.setAlarm(
                    connection.departureDateTime,
                    10,
                    connection.trainName,
                    connection.departureStation,
                    connection.arrivalStation
                )
            }
            
            connectionView.findViewById<android.widget.Button>(R.id.btn_alarm_15).setOnClickListener {
                alarmHelper.setAlarm(
                    connection.departureDateTime,
                    15,
                    connection.trainName,
                    connection.departureStation,
                    connection.arrivalStation
                )
            }
            
            binding.connectionsContainer.addView(connectionView)
        }
    }
    
    private fun updateAlarmPermissionStatus() {
        if (alarmHelper.canScheduleExactAlarms()) {
            binding.textAlarmPermission.text = "✅ Exakte Alarme erlaubt"
            binding.btnCheckAlarmPermission.visibility = View.GONE
        } else {
            binding.textAlarmPermission.text = "⚠️ Exakte Alarme nicht erlaubt"
            binding.btnCheckAlarmPermission.visibility = View.VISIBLE
        }
    }
    
    private fun requestPermissions() {
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateAlarmPermissionStatus()
        // Refresh alarm list (cleanup expired)
        lifecycleScope.launch {
            prefsManager.cleanupExpiredAlarms()
        }
    }
}
