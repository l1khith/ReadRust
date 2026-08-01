package com.l1khith.readrust

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.pdfium_bridge.*
import java.util.LinkedHashMap

sealed class PdfResult<out T> {
    data class Success<T>(val value: T) : PdfResult<T>()
    data class Error(val message: String) : PdfResult<Nothing>()
    object NotReady : PdfResult<Nothing>()
}

data class PageRender(
    val bitmap: Bitmap,
    val updateTrigger: Long = System.nanoTime()
)

/**
 * LRU Bitmap cache for the 3-page bucket architecture.
 * When full, evicts the least-recently-used page and recycles its Bitmap.
 */
private class PageBitmapCache(private val maxSize: Int = 3) {
    // accessOrder = true makes this an LRU cache
    private val cache = LinkedHashMap<Int, Bitmap>(maxSize, 0.75f, true)
    
    fun get(pageIndex: Int, width: Int, height: Int): Bitmap {
        cache[pageIndex]?.let { existing ->
            if (!existing.isRecycled) {
                return existing
            }
            // Was recycled externally — remove stale entry
            cache.remove(pageIndex)
        }
        
        // Evict oldest if at capacity
        if (cache.size >= maxSize) {
            val oldest = cache.entries.first()
            oldest.value.recycle()
            cache.remove(oldest.key)
            Log.d("PageBitmapCache", "Evicted page ${oldest.key}, recycled bitmap")
        }
        
        val fresh = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        cache[pageIndex] = fresh
        return fresh
    }
    
    fun has(pageIndex: Int): Boolean {
        val bmp = cache[pageIndex]
        return bmp != null && !bmp.isRecycled
    }
    
    fun clear() {
        cache.values.forEach { it.recycle() }
        cache.clear()
    }
}

object PdfHelper {

    private var docHandle: DocumentHandle? = null
    private var cachedUri: Uri? = null
    private var activePfd: ParcelFileDescriptor? = null
    
    private val bitmapCache = PageBitmapCache(3)
    
    private var targetWidth: Int = 1080
    private var targetHeight: Int = 1527
    
    private val mutex = Mutex()
    
    suspend fun openDocument(context: Context, uri: Uri): PdfResult<Int> {
        return mutex.withLock {
            // Already open with same URI
            if (cachedUri == uri && docHandle != null) {
                return@withLock PdfResult.Success(getPageCountInternal())
            }
            
            // Close previous document first
            closeAllUnlocked()
            
            resolveTargetSize(context)
            
            runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withLock PdfResult.Error("ContentResolver returned null for $uri")
                
                // UniFFI: load_document returns Result<DocumentHandle, PdfiumError>
                val handle = loadDocument(pfd.fd)
                
                docHandle = handle
                cachedUri = uri
                activePfd = pfd
                
                val count = getPageCountInternal()
                PdfResult.Success(count)
                
            }.getOrElse { e ->
                PdfResult.Error(e.message ?: "Unknown error opening document")
            }
        }
    }
    
    private fun getPageCountInternal(): Int {
        val handle = docHandle ?: return 0
        return getPageCount(handle)
    }
    
    suspend fun getPageCount(): Int {
        return mutex.withLock { getPageCountInternal() }
    }
    
    /**
     * Render a page using the 3-slot LRU cache.
     * If the page is already cached and valid, returns immediately.
     * If not, evicts the oldest cached page (recycling its Bitmap) and renders into a fresh one.
     */
    suspend fun renderPageToBitmap(pageIndex: Int): PdfResult<PageRender> {
        return mutex.withLock {
            val handle = docHandle ?: return@withLock PdfResult.NotReady
            
            runCatching {
                val bitmap = bitmapCache.get(pageIndex, targetWidth, targetHeight)
                
                val config = RenderConfig(
                    width = targetWidth.toUInt(),
                    height = targetHeight.toUInt(),
                    stride = bitmap.rowBytes.toUInt(),
                    nativeEnv = 0uL,      // Will be set by JNI helper
                    nativeBitmap = 0uL      // Will be set by JNI helper
                )
                
                // For UniFFI with raw bitmap access, we still need JNI to lock pixels.
                // This uses a small JNI helper to lock/unlock and pass the raw pointer.
                val success = renderPageWithBitmap(handle, pageIndex, bitmap)
                
                if (success) {
                    PdfResult.Success(PageRender(bitmap))
                } else {
                    PdfResult.Error("Pdfium renderPage returned false for page $pageIndex")
                }
                
            }.getOrElse { e ->
                PdfResult.Error(e.message ?: "Render failed on page $pageIndex")
            }
        }
    }
    
    /**
     * Extract text from a page.
     */
    suspend fun extractTextFromPage(pageIndex: Int): PdfResult<List<String>> {
        return mutex.withLock {
            val handle = docHandle ?: return@withLock PdfResult.Error("Document not open")
            
            runCatching {
                val raw = extractText(handle, pageIndex)
                val sentences = raw.split(Regex("(?<=[.!?])\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                PdfResult.Success(sentences)
                
            }.getOrElse { e ->
                PdfResult.Error(e.message ?: "Text extraction crashed on page $pageIndex")
            }
        }
    }
    
    /**
     * Close document and recycle all cached bitmaps.
     */
    fun closeDocument() {
        // Fire-and-forget on IO dispatcher
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            mutex.withLock { closeAllUnlocked() }
        }
    }
    
    private fun closeAllUnlocked() {
        docHandle?.let { handle ->
            runCatching { closeDocument(handle) }
            docHandle = null
        }
        activePfd?.let { pfd ->
            runCatching { pfd.close() }
            activePfd = null
        }
        cachedUri = null
        bitmapCache.clear()
    }
    
    private fun resolveTargetSize(context: Context) {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = wm.currentWindowMetrics
                metrics.bounds.width() to metrics.bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                dm.widthPixels to dm.heightPixels
            }
            targetWidth = width
            targetHeight = (height * 0.75f).toInt()
        } catch (e: Exception) {
            Log.w("PdfHelper", "resolveTargetSize fallback", e)
        }
    }
    
    /**
     * JNI helper to lock Android Bitmap pixels and call UniFFI render.
     * This bridges the gap because UniFFI cannot directly model Android Bitmap locking.
     */
    private fun renderPageWithBitmap(handle: DocumentHandle, pageIndex: Int, bitmap: Bitmap): Boolean {
        // Use reflection to access native lockPixels/unlockPixels
        // In production, use a dedicated JNI helper library
        val lockMethod = Bitmap::class.java.getDeclaredMethod("lockPixels")
        lockMethod.isAccessible = true
        val unlockMethod = Bitmap::class.java.getDeclaredMethod("unlockPixels")
        unlockMethod.isAccessible = true
        
        val pixels = lockMethod.invoke(bitmap) as Long
        if (pixels == 0L) return false
        
        return try {
            val config = RenderConfig(
                width = bitmap.width.toUInt(),
                height = bitmap.height.toUInt(),
                stride = bitmap.rowBytes.toUInt(),
                nativeEnv = 0uL,  // Not used in this path
                nativeBitmap = pixels.toULong()  // Pass raw pixel buffer pointer
            )
            val result = renderPage(handle, pageIndex, config)
            result.success
        } finally {
            unlockMethod.invoke(bitmap)
        }
    }
}
