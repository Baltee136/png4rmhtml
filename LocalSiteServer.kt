package com.html2png.app.server

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * A tiny local HTTP server bound to 127.0.0.1 that serves files out of a given
 * root directory (the unpacked uploaded site).
 *
 * WHY THIS EXISTS:
 * Loading HTML via file:// URIs in a WebView breaks relative paths for many
 * browsers' security models, breaks fetch()/XHR for local files, and can break
 * @font-face loading. By serving the exact same files over real http://, the
 * WebView (Chromium engine) parses, loads, and lays out the page exactly as it
 * would for a live website -- which is required for pixel-perfect, real-engine
 * rendering (no canvas reconstruction, no synthetic layout).
 */
class LocalSiteServer(private val rootDir: File, port: Int = 0) : NanoHTTPD("127.0.0.1", port) {

    val boundPort: Int
        get() = listeningPort

    override fun serve(session: IHTTPSession): Response {
        var requestedPath = session.uri.removePrefix("/")
        if (requestedPath.isEmpty()) requestedPath = "index.html"

        // Prevent path traversal outside the sandboxed site root.
        val target = File(rootDir, requestedPath).canonicalFile
        if (!target.path.startsWith(rootDir.canonicalFile.path)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        }

        if (!target.exists() || target.isDirectory) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found: $requestedPath")
        }

        val mime = mimeTypeFor(target.name)
        val stream = FileInputStream(target)
        val response = newFixedLengthResponse(Response.Status.OK, mime, stream, target.length())
        // Allow fonts/images to be fetched cross-"origin" within our own local server freely.
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    private fun mimeTypeFor(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "eot" -> "application/vnd.ms-fontobject"
            else -> "application/octet-stream"
        }
    }
}
