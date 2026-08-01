package com.l1khith.readrust

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.sync.withLock

sealed class PdfResult<out T> {
    data class Success<T>(val value: T) : PdfResult<T>()
    data class Error(val message: String) : PdfResult<Nothing>()
    object NotReady : PdfResult<Nothing>()
}

data class PageRender(
    val bitmap: Bitmap,
    val updateTrigger: Long = System.nanoTime()
)

object PdfHelper {

    private var docPtr: Long = 0L
    private var cachedUri: Uri? = null
    private var activePfd: ParcelFileDescriptor? = null

    private val bitmapPool: Array<Bitmap?> = arrayOfNulls(3)

    private var targetWidth:  Int = 1080
    private var targetHeight: Int = 1527

    suspend fun openDocument(context: Context, uri: Uri): PdfResult<Int> {
        return PdfiumLock.mutex.withLock {
            if (cachedUri == uri && docPtr != 0L) {
                return@withLock PdfResult.Success(NativePdfEngine.getPageCount(docPtr))
            }

            closeAllUnlocked()
            resolveTargetSize(context)
            prewarmPool()

            runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withLock PdfResult.Error("ContentResolver returned null for $uri")

                val ptr = NativePdfEngine.loadDocument(pfd.fd)

                if (ptr == 0L) {
                    pfd.close()
                    return@withLock PdfResult.Error("Pdfium failed to load document")
                }

                docPtr    = ptr
                cachedUri = uri
                activePfd = pfd
                PdfResult.Success(NativePdfEngine.getPageCount(docPtr))
            }.getOrElse { e ->
                PdfResult.Error(e.message ?: "Unknown error opening document")
            }
        }
    }

    private fun closeAllUnlocked() {
        if (docPtr != 0L) {
            NativePdfEngine.closeDocument(docPtr)
            docPtr = 0L
        }
        activePfd?.close()
        activePfd = null
        cachedUri = null
        clearBitmapPool()
    }

    suspend fun getPageCount(): Int {
        return PdfiumLock.mutex.withLock {
            if (docPtr == 0L) 0 else NativePdfEngine.getPageCount(docPtr)
        }
    }

    suspend fun renderPageToBitmap(pageIndex: Int): PdfResult<PageRender> {
        return PdfiumLock.mutex.withLock {
            if (docPtr == 0L) return@withLock PdfResult.NotReady

            runCatching {
                val slot   = pageIndex % 3
                val bitmap = ensurePoolBitmap(slot)

                if (NativePdfEngine.renderPage(docPtr, pageIndex, bitmap)) {
                    PdfResult.Success(PageRender(bitmap))
                } else {
                    PdfResult.Error("Pdfium renderPage returned false for page $pageIndex")
                }
            }.getOrElse { e ->
                PdfResult.Error(e.message ?: "Native render crashed on page $pageIndex")
            }
        }
    }

    suspend fun extractTextFromPage(pageIndex: Int): PdfResult<List<String>> {
        return PdfiumLock.mutex.withLock {
            if (docPtr == 0L) return@withLock PdfResult.Error("Document not open")

            runCatching {
                val raw = NativePdfEngine.extractText(docPtr, pageIndex) ?: ""
                val sentences = raw.split(Regex("(?<=[.!?])\\s+"))
                    .map    { it.trim() }
                    .filter { it.isNotBlank() }
                PdfResult.Success(sentences)
            }.getOrElse { e ->
                PdfResult.Error(e.message ?: "Text extraction crashed on page $pageIndex")
            }
        }
    }

    private fun prewarmPool() {
        for (slot in 0 until 3) ensurePoolBitmap(slot)
    }

    private fun ensurePoolBitmap(slot: Int): Bitmap {
        val existing = bitmapPool[slot]
        if (existing != null &&
            existing.width  == targetWidth &&
            existing.height == targetHeight &&
            !existing.isRecycled) {
            return existing
        }
        val fresh = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        bitmapPool[slot] = fresh
        return fresh
    }

    private fun clearBitmapPool() {
        for (i in bitmapPool.indices) {
            bitmapPool[i] = null
        }
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
            targetWidth  = width
            targetHeight = (height * 0.75f).toInt()
        } catch (e: Exception) {
            Log.w("PdfHelper", "resolveTargetSize fallback to defaults", e)
        }
    }
}
