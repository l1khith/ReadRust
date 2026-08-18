package com.l1khith.readrust.tts

object NativeAudioBridge {
    init {
        try {
            System.loadLibrary("pdfium_bridge")
        } catch (e: Throwable) {
            android.util.Log.e("NativeAudioBridge", "Failed to load pdfium_bridge library", e)
        }
    }

    /**
     * Native WAV file stitcher.
     * Concatenates synthesized chunk files into a single WAV file at [outputPath].
     * Returns true on success, false on failure.
     */
    external fun nativeStitchWavFiles(
        inputPaths: Array<String>,
        outputPath: String
    ): Boolean
}
