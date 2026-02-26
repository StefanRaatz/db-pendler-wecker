package de.gingerbeard3d.dbpendler.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.appcompat.app.AppCompatActivity
import de.gingerbeard3d.dbpendler.R
import de.gingerbeard3d.dbpendler.data.PreferencesManager
import de.gingerbeard3d.dbpendler.databinding.ActivityWidgetConfigBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Configuration activity shown when a widget is first added.
 * In this app, we just confirm and set up the widget immediately,
 * since configuration is done in the main activity.
 */
class WidgetConfigActivity : AppCompatActivity() {
    
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var binding: ActivityWidgetConfigBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWidgetConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Set result to CANCELED in case user backs out
        setResult(RESULT_CANCELED)
        
        // Get the widget ID from the intent
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        
        // Check if stations are already configured
        CoroutineScope(Dispatchers.Main).launch {
            val prefsManager = PreferencesManager(this@WidgetConfigActivity)
            val (from, to) = prefsManager.getStations()
            
            if (from != null && to != null) {
                // Stations already configured, just confirm widget
                binding.textStatus.text = "Route: ${from.name} → ${to.name}"
                binding.btnConfirm.text = "Widget hinzufügen"
            } else {
                binding.textStatus.text = "Bitte konfiguriere zuerst Start und Ziel in der App"
                binding.btnConfirm.text = "App öffnen"
            }
        }
        
        binding.btnConfirm.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                val prefsManager = PreferencesManager(this@WidgetConfigActivity)
                val (from, to) = prefsManager.getStations()
                
                if (from == null || to == null) {
                    // Open main activity to configure
                    val configIntent = Intent(this@WidgetConfigActivity, de.gingerbeard3d.dbpendler.MainActivity::class.java)
                    startActivity(configIntent)
                    finish()
                    return@launch
                }
                
                // Register widget
                prefsManager.registerWidget(appWidgetId)
                
                // Update the widget
                val appWidgetManager = AppWidgetManager.getInstance(this@WidgetConfigActivity)
                val views = RemoteViews(packageName, R.layout.widget_layout)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                
                // Trigger a refresh
                PendlerWidgetProvider.refreshAllWidgets(this@WidgetConfigActivity)
                
                // Return success
                val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                setResult(RESULT_OK, resultValue)
                finish()
            }
        }
        
        binding.btnCancel.setOnClickListener {
            finish()
        }
    }
}
