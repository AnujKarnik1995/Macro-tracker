package com.example.macrowidget

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a published-to-web CSV with:
 *   - retry + backoff on transient (network / 5xx) failures,
 *   - conditional GET (ETag / If-Modified-Since): an unchanged sheet returns 304 and we
 *     reuse the cached body instead of re-downloading,
 *   - a body cache (via SheetCache) that also powers the last-good fallback in the worker.
 * 4xx responses are treated as permanent and are not retried.
 */
object SheetFetcher {
    private const val MAX_ATTEMPTS = 3

    private class NonRetryable(message: String) : RuntimeException(message)

    fun fetchCsv(context: Context, urlStr: String): String {
        val cachedBody = SheetCache.loadBody(context, urlStr)
        // Only send validators when we actually have a cached body to fall back on.
        val etag = if (cachedBody != null) SheetCache.etag(context, urlStr) else null
        val lastModified = if (cachedBody != null) SheetCache.lastModified(context, urlStr) else null

        var lastError: Exception? = null
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "MacroWidget/1.0")
                    etag?.let { setRequestProperty("If-None-Match", it) }
                    lastModified?.let { setRequestProperty("If-Modified-Since", it) }
                }
                try {
                    val code = conn.responseCode
                    when {
                        code == 304 && cachedBody != null -> return cachedBody
                        code in 200..299 -> {
                            val body = conn.inputStream.bufferedReader().use { it.readText() }
                            SheetCache.save(context, urlStr, body,
                                conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"))
                            return body
                        }
                        code in 400..499 -> throw NonRetryable("HTTP $code")
                        else -> throw IOException("HTTP $code") // 5xx etc. -> retry
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: NonRetryable) {
                throw e // a client error won't fix itself on retry
            } catch (e: Exception) {
                lastError = e
                attempt++
                if (attempt < MAX_ATTEMPTS) {
                    try { Thread.sleep(500L * attempt) } catch (_: InterruptedException) {}
                }
            }
        }
        throw lastError ?: IOException("fetch failed after $MAX_ATTEMPTS attempts")
    }
}
