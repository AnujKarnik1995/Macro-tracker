package com.example.macrowidget

import android.content.Context

/** Per-widget config (Log + Targets CSV URLs) plus a remembered last-used pair. */
object WidgetPrefs {
    private const val PREFS = "macro_widget_prefs"

    // Optional: paste URLs here to hard-default every NEW widget. Leave blank to rely on
    // the remembered last-used URLs instead (recommended — no need to hardcode).
    const val DEFAULT_LOG_URL = ""
    const val DEFAULT_TARGETS_URL = ""

    fun setConfig(context: Context, id: Int, logUrl: String, targetsUrl: String) {
        prefs(context).edit()
            .putString("log_$id", logUrl)
            .putString("targets_$id", targetsUrl)
            // Remember globally so the next widget pre-fills without re-pasting.
            .putString("last_log", logUrl)
            .putString("last_targets", targetsUrl)
            .apply()
    }

    fun logUrl(context: Context, id: Int): String? = prefs(context).getString("log_$id", null)
    fun targetsUrl(context: Context, id: Int): String? = prefs(context).getString("targets_$id", null)

    /** The most recently saved URLs, used to pre-fill the setup form for a new widget. */
    fun lastLogUrl(context: Context): String? = prefs(context).getString("last_log", null)
    fun lastTargetsUrl(context: Context): String? = prefs(context).getString("last_targets", null)

    /** Signature of the last rendered content, used to skip no-op re-renders (avoids flicker). */
    fun signature(context: Context, id: Int): String? = prefs(context).getString("sig_$id", null)
    fun setSignature(context: Context, id: Int, sig: String) {
        prefs(context).edit().putString("sig_$id", sig).apply()
    }

    /** Which view this widget is showing: 0 = Today (macros), 1 = Energy (burn), 2 = Weight. */
    fun page(context: Context, id: Int): Int = prefs(context).getInt("page_$id", 0)
    fun setPage(context: Context, id: Int, p: Int) {
        prefs(context).edit().putInt("page_$id", p).apply()
    }

    /** Clears this widget's own keys. The remembered last-used URLs are kept on purpose. */
    fun clear(context: Context, id: Int) {
        prefs(context).edit()
            .remove("log_$id").remove("targets_$id")
            .remove("sig_$id").remove("w_$id").remove("h_$id").remove("page_$id")
            .apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
