package com.html2png.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.html2png.app.capture.PngSaver
import com.html2png.app.capture.WebViewCapture
import com.html2png.app.databinding.ActivityMainBinding
import com.html2png.app.importer.FontAssetPrefetcher
import com.html2png.app.importer.SiteImporter
import com.html2png.app.server.LocalSiteServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var siteDir: File? = null
    private var entryHtmlRelativePath: String? = null
    private var localServer: LocalSiteServer? = null
    private var currentPageUrl: String? = null

    private val pickHtmlLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handlePickedFile(it) }
    }
    private val pickZipLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handlePickedFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        configureWebView()
        wireButtons()
    }

    // ---------------------------------------------------------------------
    // WebView configuration
    // ---------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val webView = binding.webView
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = false
        settings.useWideViewPort = true
        settings.allowFileAccess = false // we serve everything via local http server instead
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.textZoom = 100
        settings.defaultTextEncodingName = "utf-8"
        settings.loadsImagesAutomatically = true
        // Force 1 CSS px == 1 device px (ignore device DPI auto-scaling) so the
        // "render width" the user enters is exactly what the page lays out at,
        // and contentHeight() math used for full-page capture stays accurate.
        webView.setInitialScale(100)

        // Disable scrollbars in the captured output for a clean screenshot.
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.setBackgroundColor(android.graphics.Color.WHITE)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.previewProgress.visibility = android.view.View.GONE
                binding.txtPreviewHint.visibility = android.view.View.GONE
                binding.btnExport.isEnabled = true
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    // ---------------------------------------------------------------------
    // Buttons
    // ---------------------------------------------------------------------

    private fun wireButtons() {
        binding.btnPickHtml.setOnClickListener { pickHtmlLauncher.launch("text/html") }
        binding.btnPickZip.setOnClickListener { pickZipLauncher.launch("application/zip") }
        binding.btnFetchAssets.setOnClickListener { fetchOnlineAssets() }
        binding.btnExport.setOnClickListener { exportPng() }
    }

    // ---------------------------------------------------------------------
    // Import
    // ---------------------------------------------------------------------

    private fun handlePickedFile(uri: Uri) {
        val displayName = queryDisplayName(uri)
        binding.txtFileStatus.text = "Importing $displayName ..."
        binding.btnExport.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SiteImporter.importFromUri(this@MainActivity, uri, displayName)
            }
            when (result) {
                is SiteImporter.ImportResult.Success -> {
                    siteDir = result.siteDir
                    entryHtmlRelativePath = result.entryHtmlFile
                    binding.txtFileStatus.text = "Loaded: $displayName  →  ${result.entryHtmlFile}"
                    startServerAndLoad()
                }
                is SiteImporter.ImportResult.Error -> {
                    binding.txtFileStatus.text = "Error: ${result.message}"
                    showSnackbar(result.message)
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = "file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
        }
        return name
    }

    // ---------------------------------------------------------------------
    // Online asset fetching (fonts, emoji images, pictures)
    // ---------------------------------------------------------------------

    private fun fetchOnlineAssets() {
        val dir = siteDir
        val entry = entryHtmlRelativePath
        if (dir == null || entry == null) {
            showSnackbar("Upload a file first.")
            return
        }
        if (!isOnline()) {
            showSnackbar("No internet connection right now. Connect to Wi-Fi/data to fetch remote assets.")
            return
        }

        binding.txtFileStatus.text = "Fetching fonts & images online…"
        binding.btnFetchAssets.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FontAssetPrefetcher.prefetchAll(dir, entry)
            }
            binding.btnFetchAssets.isEnabled = true
            binding.txtFileStatus.text =
                "Fetched ${result.downloadedCount} remote asset(s)." +
                if (result.failedUrls.isNotEmpty()) " ${result.failedUrls.size} failed." else ""
            // Reload to pick up rewritten local references.
            startServerAndLoad()
        }
    }

    private fun isOnline(): Boolean {
        val cm = ContextCompat.getSystemService(this, android.net.ConnectivityManager::class.java)
        val network = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---------------------------------------------------------------------
    // Local server + load into WebView
    // ---------------------------------------------------------------------

    private fun startServerAndLoad() {
        val dir = siteDir ?: return
        val entry = entryHtmlRelativePath ?: return

        localServer?.stop()
        val server = LocalSiteServer(dir)
        try {
            server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: Exception) {
            showSnackbar("Could not start local server: ${e.message}")
            return
        }
        localServer = server

        val widthPx = currentWidthPxOrDefault()
        applyWebViewViewportWidth(widthPx)

        binding.previewProgress.visibility = android.view.View.VISIBLE
        binding.txtPreviewHint.visibility = android.view.View.GONE

        val url = "http://127.0.0.1:${server.boundPort}/$entry"
        currentPageUrl = url
        binding.webView.loadUrl(url)
    }

    /** Preview always fills its 400dp card at full width; only export resizes precisely. */
    private fun applyWebViewViewportWidth(widthPx: Int) {
        val webView = binding.webView
        val params = webView.layoutParams
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        webView.layoutParams = params
    }

    private fun currentWidthPxOrDefault(): Int {
        val text = binding.inputWidth.text?.toString()?.trim()
        return text?.toIntOrNull() ?: 412
    }

    // ---------------------------------------------------------------------
    // Export
    // ---------------------------------------------------------------------

    private fun exportPng() {
        val webView = binding.webView
        val url = currentPageUrl
        if (url == null) {
            showSnackbar("Load a page first.")
            return
        }

        val cssWidth = currentWidthPxOrDefault()
        val scale = currentScaleFactor()
        val fullPage = binding.chipFullPage.isChecked

        binding.btnExport.isEnabled = false
        binding.txtExportStatus.text = "Rendering at real browser resolution…"

        lifecycleScope.launch {
            try {
                val density = resources.displayMetrics.density
                val widthPx = (cssWidth * density).toInt().coerceAtLeast(1)
                val previewParent = webView.parent as ViewGroup
                val previewIndex = previewParent.indexOfChild(webView)
                val originalLayoutParams = webView.layoutParams

                // Detach from the visible preview card and attach to the off-screen
                // host so resizing to full page height doesn't disturb the on-screen
                // UI or risk being clipped by the preview card's fixed-height frame.
                previewParent.removeView(webView)
                binding.captureHost.addView(
                    webView,
                    FrameLayout.LayoutParams(widthPx, webView.height.coerceAtLeast(dpToPx(1)))
                )
                awaitNextFrame()

                val finalHeightPx: Int = if (fullPage) {
                    // contentHeight() (CSS px) reflects the true full-page height now
                    // that width is locked correctly. Convert to device px and resize
                    // again so the ENTIRE page paints in one pass (no scroll-stitching).
                    (webView.contentHeight * density).toInt().coerceAtLeast(dpToPx(1))
                } else {
                    webView.layoutParams.height
                }

                if (fullPage) {
                    val p = webView.layoutParams
                    p.width = widthPx
                    p.height = finalHeightPx
                    webView.layoutParams = p
                    webView.requestLayout()
                    awaitNextFrame()
                }

                val bitmap: Bitmap = WebViewCapture.captureBySoftwareDraw(
                    webView,
                    webView.width,
                    webView.height
                )
                val scaledBitmap = WebViewCapture.scaleBitmap(bitmap, scale)

                // Restore WebView to the visible preview card at its original size.
                binding.captureHost.removeView(webView)
                webView.layoutParams = originalLayoutParams
                previewParent.addView(webView, previewIndex)
                webView.requestLayout()

                val saveResult = withContext(Dispatchers.IO) {
                    PngSaver.savePng(this@MainActivity, scaledBitmap)
                }

                if (saveResult.success) {
                    binding.txtExportStatus.text =
                        "Saved: ${saveResult.displayPath}  (${scaledBitmap.width}×${scaledBitmap.height}px)"
                    showSnackbar("PNG saved to ${saveResult.displayPath}")
                } else {
                    binding.txtExportStatus.text = "Export failed: ${saveResult.error}"
                    showSnackbar("Export failed: ${saveResult.error}")
                }
            } catch (e: Exception) {
                binding.txtExportStatus.text = "Export failed: ${e.message}"
                showSnackbar("Export failed: ${e.message}")
                // Safety net: if an exception happened after the WebView was moved
                // into the off-screen capture host but before it was restored,
                // bring it back so the preview UI doesn't lose the WebView.
                if (webView.parent === binding.captureHost) {
                    binding.captureHost.removeView(webView)
                    binding.previewContainer.addView(webView, 0)
                    val p = webView.layoutParams
                    p.width = ViewGroup.LayoutParams.MATCH_PARENT
                    p.height = ViewGroup.LayoutParams.MATCH_PARENT
                    webView.layoutParams = p
                    webView.requestLayout()
                }
            } finally {
                binding.btnExport.isEnabled = true
            }
        }
    }

    private fun currentScaleFactor(): Float {
        return when {
            binding.chipScale1x.isChecked -> 1f
            binding.chipScale3x.isChecked -> 3f
            else -> 2f
        }
    }

    /** Waits one extra rendering frame so layout/paint fully settles before capture. */
    private suspend fun awaitNextFrame() = withContext(Dispatchers.Main) {
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            binding.webView.postDelayed({
                if (cont.isActive) cont.resume(Unit) {}
            }, 120L)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        localServer?.stop()
        super.onDestroy()
    }
}
