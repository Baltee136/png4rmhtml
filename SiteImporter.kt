package com.html2png.app.importer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.FileOutputStream

/**
 * Takes whatever the user picked (a .zip site bundle, or a single .html file)
 * and produces a clean directory on disk that LocalSiteServer can serve.
 *
 * For a lone .html file, it is copied in as index.html. Any <img src="http...">
 * or remote @font-face URLs inside it will be resolved live by the WebView's own
 * network stack if the device is online; FontPrefetcher additionally allows
 * proactively downloading & rewriting those into local files for offline reuse.
 */
object SiteImporter {

    sealed class ImportResult {
        data class Success(val siteDir: File, val entryHtmlFile: String) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    fun importFromUri(context: Context, uri: Uri, displayName: String?): ImportResult {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: ""
        val name = displayName ?: uri.lastPathSegment ?: "upload"

        val workRoot = File(context.cacheDir, "sites")
        if (workRoot.exists()) workRoot.deleteRecursively()
        workRoot.mkdirs()
        val siteDir = File(workRoot, "current")
        siteDir.mkdirs()

        return try {
            when {
                name.endsWith(".zip", true) || mime.contains("zip") -> importZip(resolver, uri, siteDir)
                name.endsWith(".html", true) || name.endsWith(".htm", true) || mime.contains("html") ->
                    importSingleHtml(resolver, uri, siteDir)
                else -> ImportResult.Error("Unsupported file type. Please upload a .html file or a .zip bundle.")
            }
        } catch (e: Exception) {
            ImportResult.Error("Import failed: ${e.message}")
        }
    }

    private fun importZip(resolver: ContentResolver, uri: Uri, siteDir: File): ImportResult {
        val tmpZip = File(siteDir.parentFile, "upload.zip")
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmpZip).use { output -> input.copyTo(output) }
        } ?: return ImportResult.Error("Could not read uploaded zip.")

        ZipFile(tmpZip).extractAll(siteDir.absolutePath)
        tmpZip.delete()

        // Find an entry HTML file: prefer index.html at any depth, else first .html found.
        val htmlFiles = siteDir.walkTopDown().filter { it.isFile && it.extension.lowercase() in listOf("html", "htm") }.toList()
        if (htmlFiles.isEmpty()) {
            return ImportResult.Error("Zip did not contain any .html file.")
        }
        val preferred = htmlFiles.firstOrNull { it.name.equals("index.html", true) } ?: htmlFiles.first()

        // If the html isn't at the zip root, NanoHTTPD still serves fine since we
        // pass the relative path as the server's start path.
        val relativePath = preferred.relativeTo(siteDir).path
        return ImportResult.Success(siteDir, relativePath)
    }

    private fun importSingleHtml(resolver: ContentResolver, uri: Uri, siteDir: File): ImportResult {
        val target = File(siteDir, "index.html")
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: return ImportResult.Error("Could not read uploaded HTML file.")
        return ImportResult.Success(siteDir, "index.html")
    }

    /** Used when the user pastes raw HTML text directly instead of uploading a file. */
    fun importFromRawHtml(context: Context, html: String): ImportResult {
        val workRoot = File(context.cacheDir, "sites")
        if (workRoot.exists()) workRoot.deleteRecursively()
        workRoot.mkdirs()
        val siteDir = File(workRoot, "current")
        siteDir.mkdirs()
        val target = File(siteDir, "index.html")
        target.writeText(html)
        return ImportResult.Success(siteDir, "index.html")
    }
}
