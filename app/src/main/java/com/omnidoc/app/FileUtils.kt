package com.omnidoc.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {

    val TEXT_EXTENSIONS = setOf(
        "txt", "text", "log", "md", "markdown", "csv", "json", "xml", "html", "htm",
        "js", "ts", "css", "py", "java", "kt", "kts", "c", "cpp", "h", "hpp", "sh",
        "bat", "yaml", "yml", "ini", "cfg", "conf", "sql", "php", "rb", "go", "rs", "toml"
    )

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

    enum class Kind { PDF, DOCX, TEXT, UNSUPPORTED }

    fun kindOf(displayName: String, mime: String?): Kind {
        val ext = extOf(displayName)
        return when {
            ext == "pdf" || mime == "application/pdf" -> Kind.PDF
            ext == "docx" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Kind.DOCX
            ext in TEXT_EXTENSIONS || mime?.startsWith("text/") == true -> Kind.TEXT
            else -> Kind.UNSUPPORTED
        }
    }

    fun mimeForNewDoc(ext: String): String = when (ext) {
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "md" -> "text/markdown"
        else -> "text/plain"
    }

    fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                val n = cursor.getString(idx)
                if (!n.isNullOrBlank()) return n
            }
        }
        val last = uri.lastPathSegment ?: "document"
        return last.substringAfterLast('/')
    }

    fun querySize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) return cursor.getLong(idx)
        }
        return -1L
    }

    fun readAll(context: Context, uri: Uri): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { return it.readBytes() }
        throw IllegalStateException("Cannot open $uri")
    }

    fun writeBytes(context: Context, uri: Uri, bytes: ByteArray): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Copy the current remote content into the private backups folder. */
    fun backupOriginal(context: Context, uri: Uri, displayName: String): String? {
        return try {
            val dir = File(context.filesDir, "backups").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val out = File(dir, "$safeName.$stamp.bak")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            trimBackups(dir, 50)
            out.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun trimBackups(dir: File, keep: Int) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > keep) {
            files.take(files.size - keep).forEach { it.delete() }
        }
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1 shl 30 -> "%.1f GB".format(Locale.US, bytes / 1e9)
        bytes >= 1 shl 20 -> "%.1f MB".format(Locale.US, bytes / 1e6)
        bytes >= 1 shl 10 -> "%.1f KB".format(Locale.US, bytes / 1e3)
        else -> "$bytes B"
    }

    /** Decode a picked image downscaled so long-edge <= maxSide, as JPEG/PNG bytes. */
    fun downscaleImage(context: Context, uri: Uri, maxSide: Int = 1200): Pair<ByteArray, String>? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val stream = ByteArrayOutputStream()
            val hasAlpha = bounds.outConfig == Bitmap.Config.ARGB_8888 && bmp.hasAlpha()
            val mime = if (hasAlpha) "image/png" else "image/jpeg"
            if (hasAlpha) bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
            else bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            bmp.recycle()
            stream.toByteArray() to mime
        } catch (e: Exception) {
            null
        }
    }

    fun bytesToDataUri(bytes: ByteArray, mime: String): String =
        "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}
