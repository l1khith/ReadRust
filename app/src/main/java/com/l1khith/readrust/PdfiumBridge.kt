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

    interface ProgressCallback {
        fun onProgress(current: Int, total: Int)
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
        height: Int,
        hlLeft: Float = -1f,
        hlTop: Float = -1f,
        hlRight: Float = -1f,
        hlBottom: Float = -1f
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

    /**
     * Initialize Piper TTS with voice model
     * @param modelPath Absolute path to Piper voice model (.onnx file)
     */
    @JvmStatic external fun nativeInitPiper(modelPath: String): Int

    /**
     * Export entire PDF to WAV audiobook via Rust Piper pipeline
     * @param handle PDF document handle
     * @param outputPath Absolute path for output WAV file
     * @param progressCallback Progress listener
     */
    @JvmStatic external fun nativeExportToWav(
        handle: Long,
        outputPath: String,
        progressCallback: ProgressCallback?
    ): Int

    /**
     * Extracts all sentences from the entire PDF for audio export via Rust native engine.
     * Returns JSON: {"sentences":[{"text":"...","page":0,"idx":0},...],"total":N}
     */
    @JvmStatic external fun nativeExtractAllSentencesForAudio(handle: Long): String?
}
