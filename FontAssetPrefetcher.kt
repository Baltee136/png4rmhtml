package com.html2png.app.importer

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * Optional step (only runs when the user taps "Fetch online assets" AND the
 * device has connectivity). Scans the site's HTML/CSS for:
 *   - @font-face src: url(...) references (incl. Google Fonts @import)
 *   - <img src="http...">, background-image: url("http...")
 * Downloads each into the local site folder and rewrites the reference to the
 * local relative path, so the page subsequently renders fully offline and the
 * WebView never blocks on network during capture.
 *
 * This directly supports "download fonts, emojis and pictures online" while
 * keeping the actual render+screenshot step 100% offline and deterministic.
 */
object FontAssetPrefetcher {

    private val URL_PATTERN: Pattern = Pattern.compile("url\\(\\s*['\"]?(https?://[^'\")]+)['\"]?\\s*\\)")
    private val IMG_SRC_PATTERN: Pattern = Pattern.compile("<img[^>]+src=[\"'](https?://[^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    private val IMPORT_PATTERN: Pattern = Pattern.compile("@import\\s+url\\(\\s*['\"]?(https?://[^'\")]+)['\"]?\\s*\\)")

    data class Result(val downloadedCount: Int, val failedUrls: List<String>)

    fun prefetchAll(siteDir: File, entryHtmlRelativePath: String): Result {
        val assetsDir = File(siteDir, "_fetched_assets").apply { mkdirs() }
        var downloaded = 0
        val failed = mutableListOf<String>()

        // Gather every text-based file (html, css, js) that might reference remote URLs.
        val textFiles = siteDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in listOf("html", "htm", "css", "js") }
            .toList()

        // First pass: resolve @import url(...) for remote stylesheets (e.g. Google Fonts CSS),
        // fetch that CSS text too, and scan IT for font file URLs.
        val extraCssBlobs = mutableListOf<Pair<File, String>>() // (owning file, css text) for rewriting

        for (file in textFiles) {
            var text = file.readText()
            val importMatcher = IMPORT_PATTERN.matcher(text)
            while (importMatcher.find()) {
                val cssUrl = importMatcher.group(1)!!
                try {
                    val remoteCss = downloadText(cssUrl)
                    extraCssBlobs.add(file to remoteCss)
                } catch (e: Exception) {
                    failed.add(cssUrl)
                }
            }
        }

        // Download fonts referenced inside fetched remote CSS (e.g. Google Fonts woff2 files),
        // save them locally, and append a rewritten @font-face block into a local stylesheet.
        if (extraCssBlobs.isNotEmpty()) {
            val localFontsCss = StringBuilder()
            for ((owner, cssText) in extraCssBlobs) {
                var rewritten = cssText
                val fontMatcher = URL_PATTERN.matcher(cssText)
                while (fontMatcher.find()) {
                    val fontUrl = fontMatcher.group(1)!!
                    try {
                        val localName = "font_${downloaded}_${fontUrl.substringAfterLast('/').substringBefore('?')}"
                        val localFile = File(assetsDir, localName)
                        downloadBinary(fontUrl, localFile)
                        downloaded++
                        rewritten = rewritten.replace(fontUrl, "_fetched_assets/$localName")
                    } catch (e: Exception) {
                        failed.add(fontUrl)
                    }
                }
                localFontsCss.append(rewritten).append("\n")
            }
            val localFontsFile = File(siteDir, "_fetched_assets/remote_fonts.css")
            localFontsFile.writeText(localFontsCss.toString())

            // Inject a <link> to this generated stylesheet into the entry HTML if not present.
            val entryFile = File(siteDir, entryHtmlRelativePath)
            if (entryFile.exists()) {
                var html = entryFile.readText()
                if (!html.contains("_fetched_assets/remote_fonts.css")) {
                    val linkTag = "<link rel=\"stylesheet\" href=\"_fetched_assets/remote_fonts.css\">"
                    html = if (html.contains("</head>", true)) {
                        html.replaceFirst(Regex("(?i)</head>"), "$linkTag\n</head>")
                    } else {
                        linkTag + "\n" + html
                    }
                    entryFile.writeText(html)
                }
            }
        }

        // Direct @font-face url(...) and <img src="http..."> inside each local file: download + rewrite in place.
        for (file in textFiles) {
            var text = file.readText()
            var changed = false

            val fontMatcher = URL_PATTERN.matcher(text)
            val fontReplacements = mutableMapOf<String, String>()
            while (fontMatcher.find()) {
                val url = fontMatcher.group(1)!!
                if (fontReplacements.containsKey(url)) continue
                try {
                    val localName = "asset_${downloaded}_${url.substringAfterLast('/').substringBefore('?')}"
                    val localFile = File(assetsDir, localName)
                    downloadBinary(url, localFile)
                    downloaded++
                    val relPrefix = relativePrefixFromFileToRoot(file, siteDir)
                    fontReplacements[url] = "${relPrefix}_fetched_assets/$localName"
                } catch (e: Exception) {
                    failed.add(url)
                }
            }
            for ((orig, local) in fontReplacements) {
                text = text.replace(orig, local)
                changed = true
            }

            val imgMatcher = IMG_SRC_PATTERN.matcher(text)
            val imgReplacements = mutableMapOf<String, String>()
            while (imgMatcher.find()) {
                val url = imgMatcher.group(1)!!
                if (imgReplacements.containsKey(url)) continue
                try {
                    val localName = "img_${downloaded}_${url.substringAfterLast('/').substringBefore('?')}"
                    val localFile = File(assetsDir, localName)
                    downloadBinary(url, localFile)
                    downloaded++
                    val relPrefix = relativePrefixFromFileToRoot(file, siteDir)
                    imgReplacements[url] = "${relPrefix}_fetched_assets/$localName"
                } catch (e: Exception) {
                    failed.add(url)
                }
            }
            for ((orig, local) in imgReplacements) {
                text = text.replace(orig, local)
                changed = true
            }

            if (changed) file.writeText(text)
        }

        return Result(downloaded, failed)
    }

    private fun relativePrefixFromFileToRoot(file: File, siteDir: File): String {
        val depth = file.parentFile?.relativeTo(siteDir)?.path?.split(File.separatorChar)
            ?.count { it.isNotEmpty() } ?: 0
        return if (depth <= 0) "" else "../".repeat(depth)
    }

    private fun downloadText(urlStr: String): String {
        val conn = openConnection(urlStr)
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun downloadBinary(urlStr: String, target: File) {
        val conn = openConnection(urlStr)
        conn.inputStream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun openConnection(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        // Identify as a normal browser so font/CDN hosts (e.g. Google Fonts) serve
        // modern woff2 instead of legacy fallback formats.
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        )
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        return conn
    }
}
