package com.example.macrowidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * The two-URL setup screen. Paste the published Summary and Targets CSV links, hit save.
 *
 * The Health Connect grant flow that used to live here was removed: the watch -> Health Connect
 * -> Form path never worked reliably, and training calories are now hand-entered through the
 * Google Form like everything else. Nothing on the phone reads health data any more.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

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
}
