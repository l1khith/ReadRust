package com.l1khith.readrust

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.google.gson.annotations.SerializedName

sealed class PdfResult<out T> {
    data class Success<T>(val value: T) : PdfResult<T>()
    data class Error(val message: String) : PdfResult<Nothing>()
    object NotReady : PdfResult<Nothing>()
}

data class PageRender(
    val bitmap: Bitmap,
    val updateTrigger: Long = System.nanoTime()
)

data class SentenceWithBounds(
    @SerializedName("text") val text: String = "",
    @SerializedName("left") val left: Float = 0f,
    @SerializedName("top") val top: Float = 0f,
    @SerializedName("right") val right: Float = 0f,
    @SerializedName("bottom") val bottom: Float = 0f
)

object PdfHelper {

    private var docHandle: Long = 0L
    private var cachedUri: Uri? = null
    private var activePfd: ParcelFileDescriptor? = null

    private var targetWidth: Int = 1080
    private var targetHeight: Int = 1527

    // 3-bucket reusable Bitmaps for zero-allocation page rendering
    private val buckets = Array<Bitmap?>(3) { null }

    private fun getOrCreateBucket(index: Int): Bitmap {
        val slot = Math.floorMod(index, 3)
        var bmp = buckets[slot]
        if (bmp == null || bmp.width != targetWidth || bmp.height != targetHeight || bmp.isRecycled) {
            bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            buckets[slot] = bmp
        }
        return bmp
    }

    suspend fun openDocument(context: Context, uri: Uri): PdfResult<Int> {
        if (cachedUri == uri && docHandle != 0L) {
            return PdfResult.Success(getPageCountInternal())
        }

        closeAll()
        resolveTargetSize(context)

        return withContext(Dispatchers.IO) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext PdfResult.Error("Could not open file descriptor for URI: $uri")
                activePfd = pfd

                val handle = PdfiumBridge.nativeLoadDocument(pfd.fd)
                if (handle <= 0L) {
                    pfd.close()
                    activePfd = null
                    return@withContext PdfResult.Error("Failed to load PDF in native engine")
                }

                docHandle = handle
                cachedUri = uri

                val count = getPageCountInternal()
                PdfResult.Success(count)
            } catch (e: Exception) {
                Log.e("PdfHelper", "Failed to open document", e)
                PdfResult.Error(e.message ?: "Unknown error opening document")
            }
        }
    }

    private fun getPageCountInternal(): Int {
        val handle = docHandle
        if (handle <= 0L) return 0
        return try {
            val count = PdfiumBridge.nativeGetPageCount(handle)
            if (count > 0) count else 0
        } catch (e: Exception) {
            Log.e("PdfHelper", "Error getting page count", e)
            0
        }
    }

    suspend fun getPageCount(): Int = getPageCountInternal()

    suspend fun renderPageToBitmap(pageIndex: Int, activeSentence: SentenceWithBounds? = null): PdfResult<PageRender> {
        val handle = docHandle
        if (handle <= 0L) return PdfResult.NotReady

        return withContext(Dispatchers.IO) {
            try {
                val bitmap = getOrCreateBucket(pageIndex)

                val hlLeft = activeSentence?.left ?: -1f
                val hlTop = activeSentence?.top ?: -1f
                val hlRight = activeSentence?.right ?: -1f
                val hlBottom = activeSentence?.bottom ?: -1f

                val res = PdfiumBridge.nativeRenderPage(
                    handle, pageIndex, bitmap, targetWidth, targetHeight,
                    hlLeft, hlTop, hlRight, hlBottom
                )
                if (res != 0) {
                    return@withContext PdfResult.Error("Native render failed with error code $res")
                }

                PdfResult.Success(PageRender(bitmap))
            } catch (e: Exception) {
                Log.e("PdfHelper", "Render failed for page $pageIndex", e)
                PdfResult.Error(e.message ?: "Render failed on page $pageIndex")
            }
        }
    }

    private fun isNoiseOrPageNumber(text: String): Boolean {
        val t = text.trim()
        if (t.length <= 3) return true
        if (t.all { it.isDigit() || it.isWhitespace() || it == '-' || it == '—' || it == '.' }) return true
        if (t.matches(Regex("(?i)^(page\\s*)?\\d+(\\s*(of|/|—|-)\\s*\\d+)?$"))) return true
        if (t.matches(Regex("(?i)^—\\s*\\d+\\s*—$"))) return true
        if (t.all { !it.isLetterOrDigit() }) return true
        return false
    }

    suspend fun extractTextFromPage(pageIndex: Int): PdfResult<List<String>> {
        val handle = docHandle
        if (handle <= 0L) return PdfResult.Error("Document not open")

        return withContext(Dispatchers.IO) {
            try {
                val raw = PdfiumBridge.nativeExtractText(handle, pageIndex) ?: ""
                val sentences = raw.split(Regex("(?<=[.!?])\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.length > 3 && !isNoiseOrPageNumber(it) }
                PdfResult.Success(sentences)
            } catch (e: Exception) {
                Log.e("PdfHelper", "Text extraction failed for page $pageIndex", e)
                PdfResult.Error(e.message ?: "Text extraction crashed on page $pageIndex")
            }
        }
    }

    suspend fun extractSentencesWithBoundsFromPage(pageIndex: Int): PdfResult<List<SentenceWithBounds>> {
        val handle = docHandle
        if (handle <= 0L) return PdfResult.Error("Document not open")

        return withContext(Dispatchers.IO) {
            try {
                val json = PdfiumBridge.nativeExtractSentencesJson(handle, pageIndex) ?: "[]"
                val array = com.google.gson.Gson().fromJson(json, Array<SentenceWithBounds>::class.java) ?: emptyArray()
                val items = array.toList()
                val filtered = items.filter { it.text.isNotBlank() && it.text.length > 3 && !isNoiseOrPageNumber(it.text) }
                PdfResult.Success(filtered)
            } catch (e: Exception) {
                Log.e("PdfHelper", "Failed to extract sentences with bounds for page $pageIndex", e)
                PdfResult.Error(e.message ?: "Failed to extract sentences with bounds")
            }
        }
    }

    fun closeDocument() {
        closeAll()
    }

    private fun closeAll() {
        if (docHandle > 0L) {
            try { PdfiumBridge.nativeCloseDocument(docHandle) } catch (_: Exception) {}
            docHandle = 0L
        }
        activePfd?.let {
            try { it.close() } catch (_: Exception) {}
            activePfd = null
        }
        cachedUri = null
        for (i in 0 until 3) {
            buckets[i] = null
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
            targetWidth = width
            targetHeight = (height * 0.75f).toInt()
        } catch (e: Exception) {
            Log.w("PdfHelper", "resolveTargetSize fallback", e)
        }
    }
}