package com.l1khith.readrust

import android.graphics.Bitmap
import android.util.Log

object PdfiumBridge {
    init {
        try {
            System.loadLibrary("pdfium_bridge")
            nativeInitEngine()
            Log.i("PdfiumBridge", "Native pdfium_bridge initialized successfully")
        } catch (e: Throwable) {
            Log.e("PdfiumBridge", "Failed to load native library pdfium_bridge", e)
        }
    }

    @JvmStatic external fun nativeInitEngine(): Int
    @JvmStatic external fun nativeDestroyEngine(): Int

    @JvmStatic external fun nativeLoadDocument(fd: Int): Long
    @JvmStatic external fun nativeLoadDocumentPath(path: String): Long
    @JvmStatic external fun nativeCloseDocument(handle: Long): Int
    @JvmStatic external fun nativeGetPageCount(handle: Long): Int

    @JvmStatic external fun nativeRenderPage(
        handle: Long,
        pageIndex: Int,
        bitmap: Bitmap,
        width: Int,
        height: Int
    ): Int

    @JvmStatic external fun nativeRenderThumbnail(
        handle: Long,
        pageIndex: Int,
        bitmap: Bitmap,
        width: Int,
        height: Int
    ): Int

    @JvmStatic external fun nativeExtractText(handle: Long, pageIndex: Int): String?
    @JvmStatic external fun nativeExtractSentencesJson(handle: Long, pageIndex: Int): String?
}
