package com.l1khith.readrust

import android.content.Context
import android.util.Log
import java.io.File

class PiperVoiceManager(private val context: Context) {

    /**
     * Prepares and returns the absolute filesystem path for the Piper voice ONNX model.
     */
    fun getModelPath(): String? {
        return try {
            val modelFile = File(context.cacheDir, "piper/en_US-lessac-low.onnx")
            if (!modelFile.exists()) {
                modelFile.parentFile?.mkdirs()
                val assetNames = context.assets.list("piper-voices") ?: emptyArray()
                if (assetNames.contains("en_US-lessac-low.onnx")) {
                    context.assets.open("piper-voices/en_US-lessac-low.onnx").use { input ->
                        modelFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    Log.w("PiperVoiceManager", "Piper model not found in assets, returning fallback path")
                }
            }
            modelFile.absolutePath
        } catch (e: Exception) {
            Log.e("PiperVoiceManager", "Error preparing Piper model", e)
            null
        }
    }
}
