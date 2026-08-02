package com.l1khith.readrust

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Disk-backed thumbnail cache for PDF first-page previews.
 */
object ThumbnailCache {

    private const val THUMBNAIL_DIR = "pdf_thumbnails"
    private const val THUMBNAIL_WIDTH = 200
    private const val THUMBNAIL_HEIGHT = 280
    private const val JPEG_QUALITY = 85
    private const val MAX_CACHE_BYTES = 50L * 1024L * 1024L

    private fun cacheDir(context: Context): File {
        val dir = File(context.cacheDir, THUMBNAIL_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun keyFor(uriString: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(uriString.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun fileFor(context: Context, uriString: String): File {
        return File(cacheDir(context), "${keyFor(uriString)}.jpg")
    }

    private fun evictIfNeeded(context: Context) {
        val dir = cacheDir(context)
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var totalSize = files.sumOf { it.length() }

        for (file in files) {
            if (totalSize <= MAX_CACHE_BYTES) break
            totalSize -= file.length()
            file.delete()
        }
    }

    fun clearAll(context: Context) {
        try {
            val dir = cacheDir(context)
            dir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w("ThumbnailCache", "Failed to clear thumbnail cache", e)
        }
    }

    suspend fun get(context: Context, uriString: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            val file = fileFor(context, uriString)
            if (file.exists()) {
                try {
                    BitmapFactory.decodeFile(file.absolutePath)
                } catch (e: Exception) {
                    file.delete()
                    null
                }
            } else {
                null
            }
        }
    }

    suspend fun generateAndCache(context: Context, uri: Uri): Bitmap? {
        val uriString = uri.toString()
        val cached = get(context, uriString)
        if (cached != null) return cached

        return withContext(Dispatchers.IO) {
            try {
                evictIfNeeded(context)

                var handle = 0L
                var tempFile: File? = null
                var pfd: ParcelFileDescriptor? = null

                // 1. Try opening direct ParcelFileDescriptor
                try {
                    pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        handle = PdfiumBridge.nativeLoadDocument(pfd.fd)
                    }
                } catch (e: Exception) {
                    Log.w("ThumbnailCache", "Direct PFD load failed for $uri, trying temp file fallback", e)
                }

                // 2. Fallback: Copy URI content to local temp file if direct PFD failed
                if (handle <= 0L) {
                    pfd?.close()
                    pfd = null

                    val temp = File.createTempFile("thumb_tmp_", ".pdf", context.cacheDir)
                    tempFile = temp
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }

                    pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
                    if (pfd != null) {
                        handle = PdfiumBridge.nativeLoadDocument(pfd.fd)
                    }
                }

                if (handle <= 0L) {
                    pfd?.close()
                    tempFile?.delete()
                    Log.e("ThumbnailCache", "Failed to load document for thumbnail generation: $uri")
                    return@withContext null
                }

                val bitmap = Bitmap.createBitmap(
                    THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, Bitmap.Config.ARGB_8888
                )

                val res = PdfiumBridge.nativeRenderThumbnail(
                    handle, 0, bitmap, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT
                )

                PdfiumBridge.nativeCloseDocument(handle)
                pfd?.close()
                tempFile?.delete()

                if (res != 0) {
                    Log.e("ThumbnailCache", "Native render thumbnail failed with error code $res for $uri")
                    return@withContext null
                }

                val file = fileFor(context, uriString)
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }

                bitmap
            } catch (e: Exception) {
                Log.e("ThumbnailCache", "Thumbnail generation crashed for $uri", e)
                null
            }
        }
    }

    fun evict(context: Context, uriString: String) {
        val file = fileFor(context, uriString)
        if (file.exists()) file.delete()
    }
}
