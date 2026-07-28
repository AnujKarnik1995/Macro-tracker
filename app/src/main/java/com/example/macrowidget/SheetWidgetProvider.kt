package com.example.macrowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class SheetWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Paint the last known frame immediately so the system's reset to the empty
        // initial layout is never visible, then refresh in the background.
        ids.forEach { paintCached(context, mgr, it) }
        enqueue(context, ids, force = false)
    }

    // Re-render on resize so the rings reflow (1 row <-> 2x2) as the tile changes
    // shape. We paint the cached frame first (no blank) and the enqueue is throttled,
    // so launchers that fire this rapidly can't cause a fetch loop or flicker.
    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        // Only react to a real size change. Guards against launchers that re-fire this
        // after every updateAppWidget (the loop the previous version avoided entirely).
        if (!sizeChanged(context, id, newOptions)) return
        paintCached(context, mgr, id)
        enqueue(context, intArrayOf(id), force = false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) when (intent.action) {
            // A tap doesn't blank the tile, so we don't repaint the cached frame here
            // (that only adds a flash). The worker repaints only if values changed.
            ACTION_REFRESH -> enqueue(context, intArrayOf(id), force = true)
            ACTION_PAGE_NEXT -> {
                WidgetPrefs.setPage(context, id, (WidgetPrefs.page(context, id) + 1) % PAGE_COUNT)
                enqueue(context, intArrayOf(id), force = true)   // re-render the new page
            }
            ACTION_PAGE_PREV -> {
                // +PAGE_COUNT before modulo so page 0 wraps to the last page, not a negative.
                WidgetPrefs.setPage(context, id, (WidgetPrefs.page(context, id) - 1 + PAGE_COUNT) % PAGE_COUNT)
                enqueue(context, intArrayOf(id), force = true)
            }
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, ids: IntArray) = ids.forEach {
        WidgetPrefs.clear(context, it)
        BitmapCache.clear(context, it)
    }

    companion object {
        const val ACTION_REFRESH = "com.example.macrowidget.ACTION_REFRESH"
        const val ACTION_PAGE_NEXT = "com.example.macrowidget.ACTION_PAGE_NEXT"
        const val ACTION_PAGE_PREV = "com.example.macrowidget.ACTION_PAGE_PREV"
        const val PAGE_COUNT = 3                     // Today, Energy, Weight
        // Distinct request-code bands so refresh/prev/next PendingIntents never collapse
        // into one another (same Intent action would otherwise be treated as equal).
        private const val PREV_REQ_OFFSET = 1_000_000
        private const val NEXT_REQ_OFFSET = 2_000_000
        private const val UNIQUE_WORK = "macro_refresh"
        private const val THROTTLE_MS = 1200L

        /** Wire tap targets: left half = prev page, right half = next page, corner = refresh.
         *  refresh_hit is topmost in the layout, so a corner tap refreshes and never pages. */
        fun applyClicks(context: Context, views: RemoteViews, id: Int) {
            views.setOnClickPendingIntent(R.id.page_prev_hit, prevPendingIntent(context, id))
            views.setOnClickPendingIntent(R.id.page_next_hit, nextPendingIntent(context, id))
            views.setOnClickPendingIntent(R.id.refresh_hit, refreshPendingIntent(context, id))
        }

        /** Push the cached bitmap (if any) into the tile right now — instant, no fetch. */
        fun paintCached(context: Context, mgr: AppWidgetManager, id: Int) {
            val bmp = BitmapCache.load(context, id) ?: return
            val views = RemoteViews(context.packageName, R.layout.widget_chart)
            applyClicks(context, views, id)
            views.setImageViewBitmap(R.id.chart_image, bmp)
            mgr.updateAppWidget(id, views)
        }

        fun enqueue(context: Context, ids: IntArray, force: Boolean = true) {
            if (!force && isThrottled(context)) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ChartWorker>()
                    .setInputData(workDataOf(ChartWorker.KEY_WIDGET_IDS to ids)).build()
            )
        }

        /** True only when this widget's reported min size differs from what we last saw. */
        private fun sizeChanged(context: Context, id: Int, opts: Bundle): Boolean {
            val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, -1)
            val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, -1)
            val sp = context.getSharedPreferences("macro_widget_prefs", Context.MODE_PRIVATE)
            if (w == sp.getInt("w_$id", -2) && h == sp.getInt("h_$id", -2)) return false
            sp.edit().putInt("w_$id", w).putInt("h_$id", h).apply()
            return true
        }

        /** Collapse bursts of system/resize updates so they can't loop into a fetch storm. */
        private fun isThrottled(context: Context): Boolean {
            val sp = context.getSharedPreferences("macro_widget_prefs", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - sp.getLong("last_enqueue", 0L) < THROTTLE_MS) return true
            sp.edit().putLong("last_enqueue", now).apply()
            return false
        }

        fun refreshPendingIntent(context: Context, id: Int): PendingIntent =
            broadcast(context, id, ACTION_REFRESH, id)

        fun nextPendingIntent(context: Context, id: Int): PendingIntent =
            broadcast(context, id, ACTION_PAGE_NEXT, id + NEXT_REQ_OFFSET)

        fun prevPendingIntent(context: Context, id: Int): PendingIntent =
            broadcast(context, id, ACTION_PAGE_PREV, id + PREV_REQ_OFFSET)

        private fun broadcast(context: Context, id: Int, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, SheetWidgetProvider::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
            return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
