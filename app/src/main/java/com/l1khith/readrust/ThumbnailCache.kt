package com.l1khith.readrust

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe, disk and memory-backed thumbnail cache for PDF first-page previews.
 */
object ThumbnailCache {

    private const val THUMBNAIL_DIR = "pdf_thumbnails"
    private const val THUMBNAIL_WIDTH = 200
    private const val THUMBNAIL_HEIGHT = 280
    private const val JPEG_QUALITY = 85
    private const val MAX_DISK_CACHE_BYTES = 50L * 1024L * 1024L // 50 MB
    private const val MAX_MEMORY_CACHE_BYTES = 10 * 1024 * 1024 // 10 MB

    // In-memory LRU cache to prevent memory bloat (#37, #38)
    private val memoryCache = object : LruCache<String, Bitmap>(MAX_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    // Per-URI mutexes to make thumbnail generation atomic (#39)
    private val generationLocks = ConcurrentHashMap<String, Mutex>()

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

    private fun evictDiskCacheIfNeeded(context: Context) {
        val dir = cacheDir(context)
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var totalSize = files.sumOf { it.length() }

        for (file in files) {
            if (totalSize <= MAX_DISK_CACHE_BYTES) break
            totalSize -= file.length()
            file.delete()
        }
    }

    fun getCacheSize(context: Context): Long {
        return try {
            cacheDir(context).listFiles()?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun clearCache(context: Context) {
        clearAll(context)
    }

    fun clearAll(context: Context) {
        try {
            memoryCache.evictAll()
            val dir = cacheDir(context)
            dir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w("ThumbnailCache", "Failed to clear thumbnail cache", e)
        }
    }

    suspend fun get(context: Context, uriString: String): Bitmap? {
        val cachedMem = memoryCache.get(uriString)
        if (cachedMem != null && !cachedMem.isRecycled) {
            return cachedMem
        }

        return withContext(Dispatchers.IO) {
            val file = fileFor(context, uriString)
            if (file.exists()) {
                try {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        memoryCache.put(uriString, bmp)
                    }
                    bmp
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

        // Ensure single generation execution per URI (#39)
        val lock = generationLocks.computeIfAbsent(uriString) { Mutex() }
        return lock.withLock {
            // Re-check cache after acquiring lock
            val rechecked = get(context, uriString)
            if (rechecked != null) return@withLock rechecked

            withContext(Dispatchers.IO) {
                var handle = 0L
                var tempFile: File? = null
                var pfd: ParcelFileDescriptor? = null

                try {
                    evictDiskCacheIfNeeded(context)

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
                        Log.e("ThumbnailCache", "Failed to load document for thumbnail generation: $uri")
                        return@withContext null
                    }

                    val bitmap = Bitmap.createBitmap(
                        THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, Bitmap.Config.ARGB_8888
                    )

                    val res = PdfiumBridge.nativeRenderThumbnail(
                        handle, 0, bitmap, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT
                    )

                    if (res != 0) {
                        Log.e("ThumbnailCache", "Native render thumbnail failed with error code $res for $uri")
                        return@withContext null
                    }

                    // Write to temp file first to prevent partial disk files (#114)
                    val targetFile = fileFor(context, uriString)
                    val tmpTargetFile = File(cacheDir(context), "tmp_${targetFile.name}")
                    try {
                        FileOutputStream(tmpTargetFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        }
                        if (tmpTargetFile.exists()) {
                            tmpTargetFile.renameTo(targetFile)
                        }
                    } catch (e: Exception) {
                        tmpTargetFile.delete()
                        Log.e("ThumbnailCache", "Failed to write thumbnail file for $uri", e)
                    }

                    memoryCache.put(uriString, bitmap)
                    bitmap
                } catch (e: Exception) {
                    Log.e("ThumbnailCache", "Thumbnail generation crashed for $uri", e)
                    null
                } finally {
                    // Resource Cleanup Guarantee (#40)
                    if (handle > 0L) {
                        try { PdfiumBridge.nativeCloseDocument(handle) } catch (_: Exception) {}
                    }
                    try { pfd?.close() } catch (_: Exception) {}
                    tempFile?.delete()
                }
            }
        }
    }

    fun evict(context: Context, uriString: String) {
        memoryCache.remove(uriString)
        val file = fileFor(context, uriString)
        if (file.exists()) file.delete()
    }
}
