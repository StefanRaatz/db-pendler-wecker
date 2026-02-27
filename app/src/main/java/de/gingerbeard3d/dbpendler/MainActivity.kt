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
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    
    // Screen indices for ViewFlipper
    private companion object {
        const val SCREEN_HOME = 0
        const val SCREEN_ALARMS = 1
        const val SCREEN_SETTINGS = 2
        const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme before super.onCreate
        prefsManager = PreferencesManager(this)
        applyTheme()
        
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Handle system insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinatorRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.bottomNavigation.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }
        
        apiClient = DBApiClient()
        alarmHelper = AlarmHelper(this)
        
        setupBottomNavigation()
        setupHomeScreen()
        setupAlarmsScreen()
        setupSettingsScreen()
        
        loadSavedStations()
        observeAlarms()
        loadAlarmSounds()
    }
    
    private fun applyTheme() {
        val themeMode = runBlocking { prefsManager.getThemeMode() }
        
        when (themeMode) {
            PreferencesManager.THEME_SYSTEM -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                // Apply dynamic colors on Android 12+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    DynamicColors.applyToActivityIfAvailable(this)
                }
            }
            PreferencesManager.THEME_LIGHT -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
            PreferencesManager.THEME_DARK -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
        }
    }
    
    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    binding.viewFlipper.displayedChild = SCREEN_HOME
                    binding.toolbar.title = "🚆 DB Pendler Wecker"
                    true
                }
                R.id.nav_alarms -> {
                    binding.viewFlipper.displayedChild = SCREEN_ALARMS
                    binding.toolbar.title = "⏰ Alarme"
                    refreshAlarmsDisplay()
                    true
                }
                R.id.nav_settings -> {
                    binding.viewFlipper.displayedChild = SCREEN_SETTINGS
                    binding.toolbar.title = "⚙️ Einstellungen"
                    updatePermissionStatus()
                    true
                }
                else -> false
            }
        }
    }
    
    // ==================== HOME SCREEN ====================
    
    private fun setupHomeScreen() {
        val homeView = binding.viewFlipper.getChildAt(SCREEN_HOME)
        
        val editFromStation = homeView.findViewById<android.widget.AutoCompleteTextView>(R.id.edit_from_station)
        val editToStation = homeView.findViewById<android.widget.AutoCompleteTextView>(R.id.edit_to_station)
        val btnSwap = homeView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_swap)
        val btnRefresh = homeView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_refresh)
        val btnEarlier = homeView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_earlier)
        val btnLater = homeView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_later)
        
        // From station autocomplete
        editFromStation.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.length >= 2) {
                    searchFromStation(query)
                }
            }
        })
        
        editFromStation.setOnItemClickListener { _, _, position, _ ->
            if (position < fromStations.size) {
                selectedFromStation = fromStations[position]
                saveFromStation()
                editFromStation.dismissDropDown()
                hideKeyboard()
            }
        }
        
        // To station autocomplete
        editToStation.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.length >= 2) {
                    searchToStation(query)
                }
            }
        })
        
        editToStation.setOnItemClickListener { _, _, position, _ ->
            if (position < toStations.size) {
                selectedToStation = toStations[position]
                saveToStation()
                editToStation.dismissDropDown()
                hideKeyboard()
            }
        }
        
        // Swap button
        btnSwap.setOnClickListener { swapStations() }
        
        // Refresh button
        btnRefresh.setOnClickListener { refreshConnections() }
        
        // Earlier/Later buttons
        btnEarlier.setOnClickListener {
            timeOffsetMinutes -= 30
            refreshConnections()
        }
        
        btnLater.setOnClickListener {
            timeOffsetMinutes += 30
            refreshConnections()
        }
    }
    
    // ==================== ALARMS SCREEN ====================
    
    private fun setupAlarmsScreen() {
        // Initial setup - will be populated by observeAlarms
    }
    
    private fun refreshAlarmsDisplay() {
        lifecycleScope.launch {
            val alarms = prefsManager.getSavedAlarms()
            displayAlarms(alarms)
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
        val alarmsView = binding.viewFlipper.getChildAt(SCREEN_ALARMS)
        val cardAlarms = alarmsView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_alarms)
        val emptyState = alarmsView.findViewById<View>(R.id.empty_alarms_state)
        val alarmsContainer = alarmsView.findViewById<android.widget.LinearLayout>(R.id.alarms_container)
        
        if (alarms.isEmpty()) {
            cardAlarms.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            return
        }
        
        cardAlarms.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        alarmsContainer.removeAllViews()
        
        for (alarm in alarms) {
            val alarmView = layoutInflater.inflate(R.layout.item_alarm, alarmsContainer, false)
            
            alarmView.findViewById<TextView>(R.id.text_alarm_time).text = alarm.alarmTime
            alarmView.findViewById<TextView>(R.id.text_train_info).text = 
                "🚆 ${alarm.trainName} um ${alarm.departureTime}"
            alarmView.findViewById<TextView>(R.id.text_route).text = 
                "${alarm.fromStation} → ${alarm.toStation}"
            
            // Show alarm settings
            val settingsText = alarmView.findViewById<TextView>(R.id.text_alarm_settings)
            val volumePercent = (alarm.volume * 100).toInt()
            settingsText.text = "🔊 $volumePercent% • ${alarm.minutesBefore} Min. vorher"
            settingsText.visibility = View.VISIBLE
            
            // Edit button
            alarmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_edit_alarm).setOnClickListener {
                showEditAlarmDialog(alarm)
            }
            
            // Delete button
            alarmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_delete_alarm).setOnClickListener {
                alarmHelper.cancelAlarm(alarm.id)
            }
            
            alarmsContainer.addView(alarmView)
        }
    }
    
    private fun showEditAlarmDialog(alarm: SavedAlarm) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_alarm_settings, null)
        
        // Setup train info
        dialogView.findViewById<TextView>(R.id.text_train_info).text = 
            "🚆 ${alarm.trainName}\nAb: ${alarm.departureTime} • ${alarm.fromStation} → ${alarm.toStation}"
        
        // Setup minutes selection
        var selectedMinutes = alarm.minutesBefore
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
        var selectedVolume = (alarm.volume * 100).toInt()
        seekbarVolume.progress = selectedVolume
        textVolumePercent.text = "$selectedVolume%"
        
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
        
        // Select current sound
        if (alarm.soundUri.isNotEmpty()) {
            val index = alarmSounds.indexOfFirst { it.second.toString() == alarm.soundUri }
            if (index >= 0) spinnerSound.setSelection(index)
        }
        
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
                    binding.root.postDelayed({ stopPreview() }, 3000)
                } catch (e: Exception) {
                    Toast.makeText(this, "Vorschau fehlgeschlagen", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Alarm bearbeiten")
            .setView(dialogView)
            .create()
        
        // Cancel button
        dialogView.findViewById<android.widget.Button>(R.id.btn_cancel).setOnClickListener {
            stopPreview()
            dialog.dismiss()
        }
        
        // Update alarm button
        dialogView.findViewById<android.widget.Button>(R.id.btn_set_alarm).apply {
            text = "Speichern"
            setOnClickListener {
                stopPreview()
                
                val selectedIndex = spinnerSound.selectedItemPosition
                val soundUri = if (selectedIndex > 0 && selectedIndex < alarmSounds.size) {
                    alarmSounds[selectedIndex].second.toString()
                } else {
                    ""
                }
                
                // Calculate new alarm time based on minutes change
                val minutesDiff = alarm.minutesBefore - selectedMinutes
                val newAlarmTimeMillis = alarm.alarmTimeMillis + (minutesDiff * 60 * 1000)
                
                val updatedAlarm = alarm.copy(
                    minutesBefore = selectedMinutes,
                    volume = selectedVolume / 100f,
                    soundUri = soundUri,
                    alarmTimeMillis = newAlarmTimeMillis,
                    alarmTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                        .format(java.time.Instant.ofEpochMilli(newAlarmTimeMillis)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalTime())
                )
                
                // Cancel old alarm and set new one
                alarmHelper.cancelAlarm(alarm.id)
                alarmHelper.rescheduleAlarm(updatedAlarm)
                
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "✅ Alarm aktualisiert", Toast.LENGTH_SHORT).show()
            }
        }
        
        dialog.setOnDismissListener { stopPreview() }
        dialog.show()
    }
    
    // ==================== SETTINGS SCREEN ====================
    
    private fun setupSettingsScreen() {
        val settingsView = binding.viewFlipper.getChildAt(SCREEN_SETTINGS)
        
        // Theme radio buttons
        val radioGroupTheme = settingsView.findViewById<RadioGroup>(R.id.radio_group_theme)
        val radioThemeSystem = settingsView.findViewById<MaterialRadioButton>(R.id.radio_theme_system)
        val radioThemeLight = settingsView.findViewById<MaterialRadioButton>(R.id.radio_theme_light)
        val radioThemeDark = settingsView.findViewById<MaterialRadioButton>(R.id.radio_theme_dark)
        
        // Set current theme selection
        lifecycleScope.launch {
            when (prefsManager.getThemeMode()) {
                PreferencesManager.THEME_SYSTEM -> radioThemeSystem.isChecked = true
                PreferencesManager.THEME_LIGHT -> radioThemeLight.isChecked = true
                PreferencesManager.THEME_DARK -> radioThemeDark.isChecked = true
            }
        }
        
        radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            lifecycleScope.launch {
                val mode = when (checkedId) {
                    R.id.radio_theme_system -> PreferencesManager.THEME_SYSTEM
                    R.id.radio_theme_light -> PreferencesManager.THEME_LIGHT
                    R.id.radio_theme_dark -> PreferencesManager.THEME_DARK
                    else -> PreferencesManager.THEME_SYSTEM
                }
                prefsManager.saveThemeMode(mode)
                
                // Apply theme change
                when (mode) {
                    PreferencesManager.THEME_SYSTEM -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    }
                    PreferencesManager.THEME_LIGHT -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    }
                    PreferencesManager.THEME_DARK -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    }
                }
            }
        }
        
        // Permission switches
        val switchAlarm = settingsView.findViewById<MaterialSwitch>(R.id.switch_alarm_permission)
        val switchBattery = settingsView.findViewById<MaterialSwitch>(R.id.switch_battery_permission)
        val switchNotification = settingsView.findViewById<MaterialSwitch>(R.id.switch_notification_permission)
        val layoutNotification = settingsView.findViewById<View>(R.id.layout_notification_permission)
        
        // Hide notification permission on older Android versions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            layoutNotification.visibility = View.GONE
        }
        
        // Alarm permission switch
        switchAlarm.setOnClickListener {
            if (!alarmHelper.canScheduleExactAlarms()) {
                alarmHelper.openExactAlarmSettings()
            }
        }
        
        // Battery permission switch
        switchBattery.setOnClickListener {
            if (!isIgnoringBatteryOptimizations()) {
                requestIgnoreBatteryOptimization()
            }
        }
        
        // Notification permission switch
        switchNotification.setOnClickListener {
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
        
        // App version
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            settingsView.findViewById<TextView>(R.id.text_app_version).text = "Version: $versionName"
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun updatePermissionStatus() {
        val settingsView = binding.viewFlipper.getChildAt(SCREEN_SETTINGS)
        
        // Alarm permission
        val alarmOk = alarmHelper.canScheduleExactAlarms()
        val switchAlarm = settingsView.findViewById<MaterialSwitch>(R.id.switch_alarm_permission)
        val textAlarmStatus = settingsView.findViewById<TextView>(R.id.text_alarm_permission_status)
        switchAlarm.isChecked = alarmOk
        textAlarmStatus.text = if (alarmOk) "✅ Erteilt" else "❌ Nicht erteilt"
        
        // Battery permission
        val batteryOk = isIgnoringBatteryOptimizations()
        val switchBattery = settingsView.findViewById<MaterialSwitch>(R.id.switch_battery_permission)
        val textBatteryStatus = settingsView.findViewById<TextView>(R.id.text_battery_permission_status)
        switchBattery.isChecked = batteryOk
        textBatteryStatus.text = if (batteryOk) "✅ Erteilt" else "❌ Nicht erteilt"
        
        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifOk = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val switchNotification = settingsView.findViewById<MaterialSwitch>(R.id.switch_notification_permission)
            val textNotifStatus = settingsView.findViewById<TextView>(R.id.text_notification_permission_status)
            switchNotification.isChecked = notifOk
            textNotifStatus.text = if (notifOk) "✅ Erteilt" else "❌ Nicht erteilt"
        }
    }
    
    // ==================== SHARED METHODS ====================
    
    private fun loadAlarmSounds() {
        alarmSounds.clear()
        
        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        alarmSounds.add("Standard-Wecker" to defaultUri)
        
        val ringtoneManager = RingtoneManager(this)
        ringtoneManager.setType(RingtoneManager.TYPE_ALARM)
        val cursor = ringtoneManager.cursor
        
        while (cursor.moveToNext()) {
            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
            val uri = ringtoneManager.getRingtoneUri(cursor.position)
            alarmSounds.add(title to uri)
        }
        
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
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Bitte manuell in Einstellungen → Akku → App deaktivieren", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun loadSavedStations() {
        lifecycleScope.launch {
            val (from, to) = prefsManager.getStations()
            
            val homeView = binding.viewFlipper.getChildAt(SCREEN_HOME)
            val editFromStation = homeView.findViewById<android.widget.AutoCompleteTextView>(R.id.edit_from_station)
            val editToStation = homeView.findViewById<android.widget.AutoCompleteTextView>(R.id.edit_to_station)
            
            from?.let {
                editFromStation.setText(it.name)
                selectedFromStation = Station(value = it.name, id = it.id)
            }
            
            to?.let {
                editToStation.setText(it.name)
                selectedToStation = Station(value = it.name, id = it.id)
            }
            
            if (from != null && to != null) {
                refreshConnections()
            }
            
            prefsManager.cleanupExpiredAlarms()
        }
    }
    
    private fun searchFromStation(query: String) {
        fromStationSearchJob?.cancel()
        fromStationSearchJob = lifecycleScope.launch {
            delay(300)
            
            val homeView = binding.viewFlipper.getChildAt(SCREEN_HOME)
            val progressFrom = homeView.findViewById<View>(R.id.progress_from)
            val editFromStation = homeView.findViewById<android.widget.AutoCompleteTextView>(R.id.edit_from_station)
            
            progressFrom.visibility = View.VISIBLE
            
            apiClient.searchStations(query).fold(
                onSuccess = { stations ->
                    fromStations = stations
                    val adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_dropdown_item_1line,
                        stations.map { it.displayName }
                    )
                    editFromStation.setAdapter(adapter)
                    adapter.notifyDataSetChanged()
                    if (editFromStation.hasFocus()) {
                        editFromStation.showDropDown()
                    }
                },
                onFailure = { error ->
                    Toast.makeText(this@MainActivity, "Fehler: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            )
            
            progressFrom.visibility = View.GONE
        }
    }
    
    private fun searchToStation(query: String) {
        toStationSearchJob?.cancel()
        toStationSearchJob = lifecycleScope.launch {
            delay(300)
            
            val homeView = binding.viewFlipper.getChildAt(SCREEN_HOME)
            val progressTo = homeView.findViewById<View>(R.id.progress_to)
            val editToStation = homeView.findViewById<android.widget.AutoCompleteTextView>(R.id.edit_to_station)
            
            progressTo.visibility = View.VISIBLE
            
            apiClient.searchStations(query).fold(
                onSuccess = { stations ->
                    toStations = stations
                    val adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_dropdown_item_1line,
                        stations.map { it.displayName }
                    )
                    editToStation.setAdapter(adapter)
                    adapter.notifyDataSetChanged()
                    if (editToStation.hasFocus()) {
                        editToStation.showDropDown()
                    }
                },
                onFailure = { error ->
                    Toast.makeText(this@MainActivity, "Fehler: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            )
            
            progressTo.visibility = View.GONE
        }
    }
    
    private fun saveFromStation() {
        selectedFromStation?.let { station ->
            lifecycleScope.launch {
                prefsManager.saveFromStation(station.id, station.displayName)
                timeOffsetMinutes = 0
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
                timeOffsetMinutes = 0
                Toast.makeText(this@MainActivity, "✅ Zielbahnhof gespeichert", Toast.LENGTH_SHORT).show()
                PendlerWidgetProvider.refreshAllWidgets(this@MainActivity)
                refreshConnections()
            }
        }
    }
    
    private fun swapStations() {
        lifecycleScope.launch {
            prefsManager.swapStations()
            
            val homeView = binding.viewFlipper.getChildAt(SCREEN_HOME)
            val editFromStation = homeView.findViewById<android.widget.AutoCompleteTextView>(R.id.edit_from_station)
            val editToStation = homeView.findViewById<android.widget.AutoCompleteTextView>(R.id.edit_to_station)
            
            val temp = editFromStation.text.toString()
            editFromStation.setText(editToStation.text.toString())
            editToStation.setText(temp)
            
            val tempStation = selectedFromStation
            selectedFromStation = selectedToStation
            selectedToStation = tempStation
            
            timeOffsetMinutes = 0
            
            Toast.makeText(this@MainActivity, "🔄 Bahnhöfe getauscht", Toast.LENGTH_SHORT).show()
            PendlerWidgetProvider.refreshAllWidgets(this@MainActivity)
            refreshConnections()
        }
    }
    
    private fun refreshConnections() {
        val homeView = binding.viewFlipper.getChildAt(SCREEN_HOME)
        val textConnectionsStatus = homeView.findViewById<TextView>(R.id.text_connections_status)
        val connectionsContainer = homeView.findViewById<android.widget.LinearLayout>(R.id.connections_container)
        val progressConnections = homeView.findViewById<View>(R.id.progress_connections)
        val btnEarlier = homeView.findViewById<View>(R.id.btn_earlier)
        val btnLater = homeView.findViewById<View>(R.id.btn_later)
        
        if (selectedFromStation == null || selectedToStation == null) {
            textConnectionsStatus.text = "Bitte Start und Ziel auswählen"
            connectionsContainer.visibility = View.GONE
            btnEarlier.visibility = View.GONE
            btnLater.visibility = View.GONE
            return
        }
        
        lifecycleScope.launch {
            progressConnections.visibility = View.VISIBLE
            textConnectionsStatus.text = "Verbindungen werden geladen..."
            connectionsContainer.visibility = View.GONE
            btnEarlier.visibility = View.GONE
            btnLater.visibility = View.GONE
            
            val departureTime = LocalDateTime.now().plusMinutes(timeOffsetMinutes)
            
            apiClient.getConnections(
                selectedFromStation!!.id,
                selectedToStation!!.id,
                departureTime
            ).fold(
                onSuccess = { connections ->
                    progressConnections.visibility = View.GONE
                    
                    if (connections.isEmpty()) {
                        textConnectionsStatus.text = "Keine Verbindungen gefunden"
                        connectionsContainer.visibility = View.GONE
                        btnEarlier.visibility = if (timeOffsetMinutes > 0) View.VISIBLE else View.GONE
                        btnLater.visibility = View.VISIBLE
                    } else {
                        val timeInfo = if (timeOffsetMinutes == 0L) {
                            "Nächste Verbindungen:"
                        } else {
                            val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                            "Verbindungen ab ${departureTime.format(formatter)}:"
                        }
                        textConnectionsStatus.text = timeInfo
                        connectionsContainer.visibility = View.VISIBLE
                        btnEarlier.visibility = View.VISIBLE
                        btnLater.visibility = View.VISIBLE
                        displayConnections(connections.take(5))
                    }
                },
                onFailure = { error ->
                    progressConnections.visibility = View.GONE
                    textConnectionsStatus.text = "Fehler: ${error.message}"
                    connectionsContainer.visibility = View.GONE
                    btnEarlier.visibility = View.GONE
                    btnLater.visibility = View.GONE
                }
            )
        }
    }
    
    private fun displayConnections(connections: List<Connection>) {
        val homeView = binding.viewFlipper.getChildAt(SCREEN_HOME)
        val connectionsContainer = homeView.findViewById<android.widget.LinearLayout>(R.id.connections_container)
        connectionsContainer.removeAllViews()
        
        for (connection in connections) {
            val connectionView = layoutInflater.inflate(R.layout.item_connection, connectionsContainer, false)
            
            connectionView.findViewById<android.widget.TextView>(R.id.text_train).text = 
                "${connection.trainIcon} ${connection.trainName}"
            connectionView.findViewById<android.widget.TextView>(R.id.text_times).text = 
                "Ab: ${connection.departureTime}  An: ${connection.arrivalTime}"
            connectionView.findViewById<android.widget.TextView>(R.id.text_platform).text = 
                if (connection.platform.isNotEmpty()) "Gleis ${connection.platform}" else ""
            
            connectionView.findViewById<android.widget.Button>(R.id.btn_alarm_10).setOnClickListener {
                showAlarmSettingsDialog(connection, 10)
            }
            
            connectionView.findViewById<android.widget.Button>(R.id.btn_alarm_15).setOnClickListener {
                showAlarmSettingsDialog(connection, 15)
            }
            
            connectionsContainer.addView(connectionView)
        }
    }
    
    private fun showAlarmSettingsDialog(connection: Connection, defaultMinutes: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_alarm_settings, null)
        
        dialogView.findViewById<TextView>(R.id.text_train_info).text = 
            "${connection.trainIcon} ${connection.trainName}\nAb: ${connection.departureTime} • ${connection.departureStation} → ${connection.arrivalStation}"
        
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
        
        val spinnerSound = dialogView.findViewById<Spinner>(R.id.spinner_sound)
        val soundNames = alarmSounds.map { it.first }
        val soundAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, soundNames)
        soundAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSound.adapter = soundAdapter
        
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
                    binding.root.postDelayed({ stopPreview() }, 3000)
                } catch (e: Exception) {
                    Toast.makeText(this, "Vorschau fehlgeschlagen", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<android.widget.Button>(R.id.btn_cancel).setOnClickListener {
            stopPreview()
            dialog.dismiss()
        }
        
        dialogView.findViewById<android.widget.Button>(R.id.btn_set_alarm).setOnClickListener {
            stopPreview()
            
            val selectedIndex = spinnerSound.selectedItemPosition
            val soundUri = if (selectedIndex > 0 && selectedIndex < alarmSounds.size) {
                alarmSounds[selectedIndex].second.toString()
            } else {
                ""
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
    
    override fun onResume() {
        super.onResume()
        if (binding.viewFlipper.displayedChild == SCREEN_SETTINGS) {
            updatePermissionStatus()
        }
        lifecycleScope.launch {
            prefsManager.cleanupExpiredAlarms()
        }
    }
}
