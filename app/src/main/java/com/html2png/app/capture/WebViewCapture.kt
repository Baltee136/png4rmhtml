package com.html2png.app.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebView
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Captures the WebView's *actual rendered pixels* -- this is the real Chromium
 * compositor output, not a re-implementation of layout/paint like html2canvas.
 * That's what guarantees correct kerning, line-height, shadows, blend modes,
 * web fonts, emoji glyphs, gradients, clipping, etc. all match exactly what
 * you'd see live in the browser.
 *
 * Two capture strategies:
 *  1) PixelCopy (API 26+, hardware-accelerated views): copies the real
 *     compositor surface. Used for what's currently within the WebView's
 *     visible viewport bounds.
 *  2) view.draw(canvas) software capture: used for "full page" capture, where
 *     we resize the WebView's layout to the full content height first (so the
 *     whole page is laid out and painted at once, same engine, same paint
 *     path) and then draw it to a bitmap. This avoids any manual stitching of
 *     scroll-position screenshots, which could risk seams or timing artifacts.
 */
object WebViewCapture {

    /**
     * Captures the currently visible viewport-sized region exactly as painted.
     * Use this when contentWidth/contentHeight equal the WebView's current size.
     */
    suspend fun capturePixelCopy(webView: WebView): Bitmap = suspendCoroutine { cont ->
        val width = webView.width
        val height = webView.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val location = IntArray(2)
            webView.getLocationInWindow(location)
            val window = webView.context.let { ctx ->
                (ctx as? android.app.Activity)?.window
            }
            if (window != null) {
                val rect = android.graphics.Rect(
                    location[0], location[1],
                    location[0] + width, location[1] + height
                )
                PixelCopy.request(window, rect, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        cont.resume(bitmap)
                    } else {
                        cont.resume(captureBySoftwareDraw(webView, width, height))
                    }
                }, Handler(Looper.getMainLooper()))
                return@suspendCoroutine
            }
        }
        // Fallback path (no window / pre-API26): software draw.
        cont.resume(captureBySoftwareDraw(webView, width, height))
    }

    /**
     * Draws the WebView's current paint state directly into a bitmap canvas.
     * This still goes through the real Chromium paint pipeline (WebView.draw
     * delegates to the engine's compositor/raster output) -- it's not DOM
     * traversal or manual box-model re-implementation like html2canvas.
     */
    fun captureBySoftwareDraw(webView: WebView, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        webView.draw(canvas)
        return bitmap
    }

    /** Applies a device-pixel-ratio style upscale (e.g. 2x/3x for retina-quality export). */
    fun scaleBitmap(source: Bitmap, scale: Float): Bitmap {
        if (scale == 1f) return source
        val newWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }
}
