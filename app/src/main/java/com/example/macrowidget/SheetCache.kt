package com.example.macrowidget

import android.content.Context
import java.io.File

/**
 * Per-URL cache of the last fetched CSV body plus its HTTP validators (ETag /
 * Last-Modified). Backs two things:
 *   - conditional GET — send the validators; a 304 means reuse the cached body, and
 *   - the last-good fallback — when a fetch fails, re-render from the cached body.
 */
object SheetCache {
    private const val PREFS = "macro_widget_prefs"

    private fun key(url: String) = url.hashCode().toString()
    private fun bodyFile(c: Context, url: String) = File(c.filesDir, "sheet_${key(url)}.csv")
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadBody(c: Context, url: String): String? = try {
        val f = bodyFile(c, url)
        if (f.exists()) f.readText() else null
    } catch (_: Exception) { null }

    fun etag(c: Context, url: String): String? = prefs(c).getString("etag_${key(url)}", null)
    fun lastModified(c: Context, url: String): String? = prefs(c).getString("lmod_${key(url)}", null)

    fun save(c: Context, url: String, body: String, etag: String?, lastModified: String?) {
        try { bodyFile(c, url).writeText(body) } catch (_: Exception) { return }
        prefs(c).edit()
            .putString("etag_${key(url)}", etag)
            .putString("lmod_${key(url)}", lastModified)
            .apply()
    }
}
