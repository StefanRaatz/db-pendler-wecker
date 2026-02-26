package de.gingerbeard3d.dbpendler.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.RemoteViews
import androidx.appcompat.app.AppCompatActivity
import de.gingerbeard3d.dbpendler.R
import de.gingerbeard3d.dbpendler.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Configuration activity shown when a widget is first added.
 * Simplified version without ViewBinding to avoid potential crashes.
 */
class WidgetConfigActivity : AppCompatActivity() {
    
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_config)
        
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
        
        val textStatus = findViewById<TextView>(R.id.text_status)
        val btnConfirm = findViewById<Button>(R.id.btn_confirm)
        val btnCancel = findViewById<Button>(R.id.btn_cancel)
        
        // Check if stations are already configured
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val prefsManager = PreferencesManager(this@WidgetConfigActivity)
                val (from, to) = prefsManager.getStations()
                
                if (from != null && to != null) {
                    textStatus.text = "Route: ${from.name} → ${to.name}"
                    btnConfirm.text = "Widget hinzufügen"
                } else {
                    textStatus.text = "Bitte konfiguriere zuerst Start und Ziel in der App"
                    btnConfirm.text = "App öffnen"
                }
            } catch (e: Exception) {
                textStatus.text = "Fehler beim Laden der Einstellungen"
            }
        }
        
        btnConfirm.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                try {
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
                } catch (e: Exception) {
                    val textStatus = findViewById<TextView>(R.id.text_status)
                    textStatus.text = "Fehler: ${e.message}"
                }
            }
        }
        
        btnCancel.setOnClickListener {
            finish()
        }
    }
}
