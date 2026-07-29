package com.example.macrowidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val reader by lazy { HealthConnectBurnReader(this) }

    private val requestPerms = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(reader.permissions)) {
            Toast.makeText(this, "Watch permissions granted!", Toast.LENGTH_SHORT).show()
            triggerDirectSync()
        } else {
            Toast.makeText(this, "Watch permissions missing or denied. Check Health Connect -> App permissions.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_config)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        val log = findViewById<EditText>(R.id.log_field)
        val targets = findViewById<EditText>(R.id.targets_field)
        
        log.setText(WidgetPrefs.logUrl(this, widgetId) ?: WidgetPrefs.lastLogUrl(this) ?: WidgetPrefs.DEFAULT_LOG_URL)
        targets.setText(WidgetPrefs.targetsUrl(this, widgetId) ?: WidgetPrefs.lastTargetsUrl(this) ?: WidgetPrefs.DEFAULT_TARGETS_URL)

        findViewById<Button>(R.id.grant_watch_button)?.setOnClickListener {
            lifecycleScope.launch {
                val status = HealthConnectClient.getSdkStatus(this@WidgetConfigActivity)
                when (status) {
                    HealthConnectClient.SDK_UNAVAILABLE -> {
                        Toast.makeText(this@WidgetConfigActivity, "Health Connect app is not installed on this phone.", Toast.LENGTH_LONG).show()
                    }
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                        Toast.makeText(this@WidgetConfigActivity, "Health Connect update required in Play Store.", Toast.LENGTH_LONG).show()
                    }
                    HealthConnectClient.SDK_AVAILABLE -> {
                        if (!reader.hasAccess()) {
                            requestPerms.launch(reader.permissions)
                        } else {
                            triggerDirectSync()
                        }
                    }
                }
            }
        }

        findViewById<Button>(R.id.save_button).setOnClickListener {
            val l = log.text.toString().trim()
            val t = targets.text.toString().trim()
            if (!l.startsWith("http") || !t.startsWith("http")) {
                Toast.makeText(this, "Enter both published CSV URLs", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                WidgetPrefs.setConfig(this, widgetId, l, t)
                SheetWidgetProvider.enqueue(this, intArrayOf(widgetId))
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            }
            finish()
        }
    }

    private fun triggerDirectSync() {
        lifecycleScope.launch {
            Toast.makeText(this@WidgetConfigActivity, "Reading Watch data from Health Connect...", Toast.LENGTH_SHORT).show()
            val result = BurnUploadWorker.performSync(this@WidgetConfigActivity, days = 30)
            Toast.makeText(this@WidgetConfigActivity, result, Toast.LENGTH_LONG).show()
        }
    }
}
