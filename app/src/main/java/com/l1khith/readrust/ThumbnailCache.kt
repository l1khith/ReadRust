package com.l1khith.readrust

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
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
    private const val JPEG_QUALITY = 80
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

    suspend fun get(context: Context, uriString: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            val file = fileFor(context, uriString)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
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

                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext null

                val bitmap = Bitmap.createBitmap(
                    THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, Bitmap.Config.ARGB_8888
                )

                val success = PdfiumLock.mutex.withLock {
                    val docPtr = NativePdfEngine.loadDocument(pfd.fd)
                    if (docPtr == 0L) {
                        pfd.close()
                        return@withLock false
                    }

                    val rendered = NativePdfEngine.renderPage(docPtr, 0, bitmap)

                    NativePdfEngine.closeDocument(docPtr)
                    pfd.close()

                    rendered
                }

                if (!success) {
                    return@withContext null
                }

                val file = fileFor(context, uriString)
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }

                bitmap
            } catch (e: Exception) {
                null
            }
        }
    }

    fun evict(context: Context, uriString: String) {
        val file = fileFor(context, uriString)
        if (file.exists()) file.delete()
    }
}
