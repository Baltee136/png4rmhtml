package com.html2png.app.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PngSaver {

    data class SaveResult(val success: Boolean, val displayPath: String?, val error: String? = null)

    fun savePng(context: Context, bitmap: Bitmap, baseName: String = "html2png"): SaveResult {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "${baseName}_$timestamp.png"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bitmap, filename)
            } else {
                saveViaLegacyFile(bitmap, filename)
            }
        } catch (e: Exception) {
            SaveResult(false, null, e.message)
        }
    }

    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, filename: String): SaveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Html2Png")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return SaveResult(false, null, "Could not create MediaStore entry")

        resolver.openOutputStream(uri)?.use { out: OutputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: return SaveResult(false, null, "Could not open output stream")

        return SaveResult(true, "Pictures/Html2Png/$filename")
    }

    private fun saveViaLegacyFile(bitmap: Bitmap, filename: String): SaveResult {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Html2Png"
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return SaveResult(true, file.absolutePath)
    }
}
