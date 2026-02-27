package de.gingerbeard3d.dbpendler

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import de.gingerbeard3d.dbpendler.alarm.AlarmHelper
import de.gingerbeard3d.dbpendler.api.Connection
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
import java.time.LocalDateTime

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
    
    // Time offset for connections navigation (in minutes)
    private var timeOffsetMinutes: Long = 0
    
    // Preview media player for alarm sounds
    private var previewPlayer: MediaPlayer? = null
    
    // Available alarm sounds
    private val alarmSounds = mutableListOf<Pair<String, Uri>>() // Name to URI
    
    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Handle system insets for notch/status bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        prefsManager = PreferencesManager(this)
        apiClient = DBApiClient()
        alarmHelper = AlarmHelper(this)
        
        setupUI()
        loadSavedStations()
        observeAlarms()
        requestPermissions()
        checkBatteryOptimization()
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
            } else if (!isIgnoringBatteryOptimizations()) {
                requestIgnoreBatteryOptimization()
            } else {
                Toast.makeText(this, "✅ Alle Berechtigungen erteilt", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Earlier/Later buttons for time navigation
        binding.btnEarlier.setOnClickListener {
            timeOffsetMinutes -= 30
            refreshConnections()
        }
        
        binding.btnLater.setOnClickListener {
            timeOffsetMinutes += 30
            refreshConnections()
        }
        
        updatePermissionStatus()
        loadAlarmSounds()
    }
    
    private fun loadAlarmSounds() {
        alarmSounds.clear()
        
        // Add default option
        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        alarmSounds.add("Standard-Wecker" to defaultUri)
        
        // Load system alarm sounds
        val ringtoneManager = RingtoneManager(this)
        ringtoneManager.setType(RingtoneManager.TYPE_ALARM)
        val cursor = ringtoneManager.cursor
        
        while (cursor.moveToNext()) {
            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
            val uri = ringtoneManager.getRingtoneUri(cursor.position)
            alarmSounds.add(title to uri)
        }
        
        // Also add notification sounds as fallback
        val notifManager = RingtoneManager(this)
        notifManager.setType(RingtoneManager.TYPE_NOTIFICATION)
        val notifCursor = notifManager.cursor
        
        while (notifCursor.moveToNext()) {
            val title = "🔔 " + notifCursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
            val uri = notifManager.getRingtoneUri(notifCursor.position)
            alarmSounds.add(title to uri)
        }
    }
    
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }
    
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }
    
    private fun requestIgnoreBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to battery optimization settings
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Bitte manuell in Einstellungen → Akku → App deaktivieren", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun checkBatteryOptimization() {
        if (!isIgnoringBatteryOptimizations()) {
            // Show a hint to the user
            Toast.makeText(
                this, 
                "⚠️ Für zuverlässige Alarme: Bitte Akku-Optimierung deaktivieren", 
                Toast.LENGTH_LONG
            ).show()
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
                timeOffsetMinutes = 0 // Reset time offset
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
                timeOffsetMinutes = 0 // Reset time offset
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
            
            // Reset time offset
            timeOffsetMinutes = 0
            
            Toast.makeText(this@MainActivity, "🔄 Bahnhöfe getauscht", Toast.LENGTH_SHORT).show()
            PendlerWidgetProvider.refreshAllWidgets(this@MainActivity)
            refreshConnections()
        }
    }
    
    private fun refreshConnections() {
        if (selectedFromStation == null || selectedToStation == null) {
            binding.textConnectionsStatus.text = "Bitte Start und Ziel auswählen"
            binding.connectionsContainer.visibility = View.GONE
            binding.btnEarlier.visibility = View.GONE
            binding.btnLater.visibility = View.GONE
            return
        }
        
        lifecycleScope.launch {
            binding.progressConnections.visibility = View.VISIBLE
            binding.textConnectionsStatus.text = "Verbindungen werden geladen..."
            binding.connectionsContainer.visibility = View.GONE
            binding.btnEarlier.visibility = View.GONE
            binding.btnLater.visibility = View.GONE
            
            // Calculate departure time with offset
            val departureTime = LocalDateTime.now().plusMinutes(timeOffsetMinutes)
            
            apiClient.getConnections(
                selectedFromStation!!.id,
                selectedToStation!!.id,
                departureTime
            ).fold(
                onSuccess = { connections ->
                    binding.progressConnections.visibility = View.GONE
                    
                    if (connections.isEmpty()) {
                        binding.textConnectionsStatus.text = "Keine Verbindungen gefunden"
                        binding.connectionsContainer.visibility = View.GONE
                        binding.btnEarlier.visibility = if (timeOffsetMinutes > 0) View.VISIBLE else View.GONE
                        binding.btnLater.visibility = View.VISIBLE
                    } else {
                        // Show time info in header
                        val timeInfo = if (timeOffsetMinutes == 0L) {
                            "Nächste Verbindungen:"
                        } else {
                            val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                            "Verbindungen ab ${departureTime.format(formatter)}:"
                        }
                        binding.textConnectionsStatus.text = timeInfo
                        binding.connectionsContainer.visibility = View.VISIBLE
                        binding.btnEarlier.visibility = View.VISIBLE
                        binding.btnLater.visibility = View.VISIBLE
                        displayConnections(connections.take(5))
                    }
                },
                onFailure = { error ->
                    binding.progressConnections.visibility = View.GONE
                    binding.textConnectionsStatus.text = "Fehler: ${error.message}"
                    binding.connectionsContainer.visibility = View.GONE
                    binding.btnEarlier.visibility = View.GONE
                    binding.btnLater.visibility = View.GONE
                }
            )
        }
    }
    
    private fun displayConnections(connections: List<Connection>) {
        binding.connectionsContainer.removeAllViews()
        
        for (connection in connections) {
            val connectionView = layoutInflater.inflate(R.layout.item_connection, binding.connectionsContainer, false)
            
            connectionView.findViewById<android.widget.TextView>(R.id.text_train).text = 
                "${connection.trainIcon} ${connection.trainName}"
            connectionView.findViewById<android.widget.TextView>(R.id.text_times).text = 
                "Ab: ${connection.departureTime}  An: ${connection.arrivalTime}"
            connectionView.findViewById<android.widget.TextView>(R.id.text_platform).text = 
                if (connection.platform.isNotEmpty()) "Gleis ${connection.platform}" else ""
            
            // Alarm buttons - now open settings dialog
            connectionView.findViewById<android.widget.Button>(R.id.btn_alarm_10).setOnClickListener {
                showAlarmSettingsDialog(connection, 10)
            }
            
            connectionView.findViewById<android.widget.Button>(R.id.btn_alarm_15).setOnClickListener {
                showAlarmSettingsDialog(connection, 15)
            }
            
            binding.connectionsContainer.addView(connectionView)
        }
    }
    
    private fun showAlarmSettingsDialog(connection: Connection, defaultMinutes: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_alarm_settings, null)
        
        // Setup train info
        dialogView.findViewById<TextView>(R.id.text_train_info).text = 
            "${connection.trainIcon} ${connection.trainName}\nAb: ${connection.departureTime} • ${connection.departureStation} → ${connection.arrivalStation}"
        
        // Setup minutes selection
        var selectedMinutes = defaultMinutes
        val btnMinutes5 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_minutes_5)
        val btnMinutes10 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_minutes_10)
        val btnMinutes15 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_minutes_15)
        val btnMinutes20 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_minutes_20)
        
        fun updateMinutesSelection() {
            val buttons = listOf(btnMinutes5 to 5, btnMinutes10 to 10, btnMinutes15 to 15, btnMinutes20 to 20)
            buttons.forEach { (btn, mins) ->
                if (mins == selectedMinutes) {
                    btn.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
                    btn.setTextColor(ContextCompat.getColor(this, R.color.white))
                } else {
                    btn.setBackgroundColor(ContextCompat.getColor(this, R.color.white))
                    btn.setTextColor(ContextCompat.getColor(this, R.color.primary))
                }
            }
        }
        
        updateMinutesSelection()
        
        btnMinutes5.setOnClickListener { selectedMinutes = 5; updateMinutesSelection() }
        btnMinutes10.setOnClickListener { selectedMinutes = 10; updateMinutesSelection() }
        btnMinutes15.setOnClickListener { selectedMinutes = 15; updateMinutesSelection() }
        btnMinutes20.setOnClickListener { selectedMinutes = 20; updateMinutesSelection() }
        
        // Setup volume seekbar
        val seekbarVolume = dialogView.findViewById<SeekBar>(R.id.seekbar_volume)
        val textVolumePercent = dialogView.findViewById<TextView>(R.id.text_volume_percent)
        var selectedVolume = 80
        
        seekbarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedVolume = progress
                textVolumePercent.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Setup sound spinner
        val spinnerSound = dialogView.findViewById<Spinner>(R.id.spinner_sound)
        val soundNames = alarmSounds.map { it.first }
        val soundAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, soundNames)
        soundAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSound.adapter = soundAdapter
        
        // Preview button
        dialogView.findViewById<android.widget.Button>(R.id.btn_preview).setOnClickListener {
            stopPreview()
            val selectedIndex = spinnerSound.selectedItemPosition
            if (selectedIndex >= 0 && selectedIndex < alarmSounds.size) {
                val soundUri = alarmSounds[selectedIndex].second
                try {
                    previewPlayer = MediaPlayer().apply {
                        setDataSource(this@MainActivity, soundUri)
                        setVolume(selectedVolume / 100f, selectedVolume / 100f)
                        prepare()
                        start()
                    }
                    // Stop after 3 seconds
                    binding.root.postDelayed({ stopPreview() }, 3000)
                } catch (e: Exception) {
                    Toast.makeText(this, "Vorschau fehlgeschlagen", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        // Cancel button
        dialogView.findViewById<android.widget.Button>(R.id.btn_cancel).setOnClickListener {
            stopPreview()
            dialog.dismiss()
        }
        
        // Set alarm button
        dialogView.findViewById<android.widget.Button>(R.id.btn_set_alarm).setOnClickListener {
            stopPreview()
            
            val selectedIndex = spinnerSound.selectedItemPosition
            val soundUri = if (selectedIndex > 0 && selectedIndex < alarmSounds.size) {
                alarmSounds[selectedIndex].second.toString()
            } else {
                "" // Empty = default
            }
            
            alarmHelper.setAlarm(
                connection.departureDateTime,
                selectedMinutes,
                connection.trainName,
                connection.departureStation,
                connection.arrivalStation,
                volume = selectedVolume / 100f,
                soundUri = soundUri
            )
            
            dialog.dismiss()
        }
        
        dialog.setOnDismissListener { stopPreview() }
        dialog.show()
    }
    
    private fun stopPreview() {
        previewPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        previewPlayer = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPreview()
    }
    
    private fun updatePermissionStatus() {
        val alarmOk = alarmHelper.canScheduleExactAlarms()
        val batteryOk = isIgnoringBatteryOptimizations()
        
        when {
            alarmOk && batteryOk -> {
                binding.textAlarmPermission.text = "✅ Alle Berechtigungen erteilt"
                binding.btnCheckAlarmPermission.visibility = View.GONE
            }
            !alarmOk -> {
                binding.textAlarmPermission.text = "⚠️ Exakte Alarme nicht erlaubt"
                binding.btnCheckAlarmPermission.text = "Erlauben"
                binding.btnCheckAlarmPermission.visibility = View.VISIBLE
            }
            !batteryOk -> {
                binding.textAlarmPermission.text = "⚠️ Akku-Optimierung aktiv (Alarme unzuverlässig!)"
                binding.btnCheckAlarmPermission.text = "Deaktivieren"
                binding.btnCheckAlarmPermission.visibility = View.VISIBLE
            }
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
        updatePermissionStatus()
        // Refresh alarm list (cleanup expired)
        lifecycleScope.launch {
            prefsManager.cleanupExpiredAlarms()
        }
    }
}
