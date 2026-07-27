package com.example.macrowidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Persists the last rendered bitmap per widget id so a refresh can paint the
 * previous frame *instantly* instead of dropping to the empty initial layout
 * while the network fetch runs. This is what stops the periodic blank/blink.
 */
object BitmapCache {

    private fun file(context: Context, id: Int) = File(context.filesDir, "widget_$id.png")

    fun save(context: Context, id: Int, bmp: Bitmap) {
        try {
            file(context, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) { /* a missed cache write just means one blink, not a crash */ }
    }

    fun load(context: Context, id: Int): Bitmap? = try {
        val f = file(context, id)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (_: Exception) { null }

    fun clear(context: Context, id: Int) {
        try { file(context, id).delete() } catch (_: Exception) {}
    }
}
