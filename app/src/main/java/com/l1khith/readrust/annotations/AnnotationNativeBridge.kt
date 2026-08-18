package com.l1khith.readrust.annotations

object AnnotationNativeBridge {
    init {
        try {
            System.loadLibrary("pdfium_bridge")
        } catch (e: Throwable) {
            android.util.Log.e("AnnotationNativeBridge", "Failed to load pdfium_bridge library", e)
        }
    }

    /**
     * Injects PDF /Annots (highlights) and /Outlines (bookmarks) into a cloned PDF file.
     * Returns 0 on success, non-zero on failure.
     */
    external fun nativeExportAnnotatedPdf(
        pdfFd: Int,
        outputPath: String,
        highlightsJson: String,
        bookmarksJson: String
    ): Int

    /**
     * Generates a sidecar JSON file mapping bookmarks and highlights to estimated audio timestamps.
     * Returns 0 on success, non-zero on failure.
     */
    external fun nativeGenerateAudioSidecar(
        outputSidecarPath: String,
        highlightsJson: String,
        bookmarksJson: String,
        totalPages: Int,
        totalAudioSeconds: Double
    ): Int
}
