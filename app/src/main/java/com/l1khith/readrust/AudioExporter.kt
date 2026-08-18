package com.l1khith.readrust

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object AudioExporter {

    private fun intToLittleEndianBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun updateWavHeaderSizes(wavFile: File, totalPcmBytes: Long) {
        try {
            RandomAccessFile(wavFile, "rw").use { raf ->
                val riffLength = totalPcmBytes + 36
                raf.seek(4)
                raf.write(intToLittleEndianBytes(riffLength.toInt()))
                raf.seek(40)
                raf.write(intToLittleEndianBytes(totalPcmBytes.toInt()))
            }
        } catch (e: Exception) {
            Log.e("AudioExporter", "Failed to update WAV header sizes", e)
        }
    }

    /**
     * High-Performance & Deduplicated Audio Synthesis Pipeline:
     * Batches all document text into clean, deduplicated blocks for maximum synthesis speed.
     */
    suspend fun exportPdfToAudioStream(
        context: Context,
        pdfTitle: String,
        totalPages: Int,
        docHandle: Long = 0L,
        fetchPageSentences: suspend (Int) -> List<String>,
        speechRate: Float,
        onProgress: (Int, Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        if (totalPages <= 0) return@withContext null

        val outputDir = File(context.getExternalFilesDir(null), "exported_audio").apply { mkdirs() }
        val safeName = pdfTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outputFile = File(outputDir, "${safeName}_audio.wav")
        if (outputFile.exists()) outputFile.delete()

        // 1. SAFELY TRY PIPER ONNX NATIVE RUST ENGINE FIRST
        val piperManager = PiperVoiceManager(context)
        val modelPath = piperManager.getModelPath()

        if (!modelPath.isNullOrEmpty() && docHandle > 0L) {
            try {
                val initRes = PdfiumBridge.nativeInitPiper(modelPath)
                if (initRes == 0) {
                    val callback = object : PdfiumBridge.ProgressCallback {
                        override fun onProgress(current: Int, total: Int) {
                            onProgress(current, total)
                        }
                    }
                    val exportRes = PdfiumBridge.nativeExportToWav(docHandle, outputFile.absolutePath, callback)
                    if (exportRes == 0 && outputFile.exists() && outputFile.length() > 0) {
                        Log.i("AudioExporter", "Piper ONNX Native Synthesis Succeeded!")
                        return@withContext outputFile
                    }
                }
            } catch (e: Throwable) {
                Log.w("AudioExporter", "Native Piper symbol unavailable or fallback required: ${e.message}")
            }
        }

        // 2. BATCH & DEDUPLICATE TEXT ACROSS ALL PAGES FIRST FOR MAXIMUM SPEED
        val fullTextBuilder = StringBuilder()
        var lastSentence = ""

        for (p in 0 until totalPages) {
            val sentences = fetchPageSentences(p)
            for (rawSent in sentences) {
                val cleanSent = rawSent.trim()
                // DEDUPLICATION FIX: Ignore exact consecutive duplicate sentences across page breaks
                if (cleanSent.isNotBlank() && cleanSent != lastSentence) {
                    fullTextBuilder.append(cleanSent)
                    if (!cleanSent.endsWith(".") && !cleanSent.endsWith("!") && !cleanSent.endsWith("?")) {
                        fullTextBuilder.append(". ")
                    } else {
                        fullTextBuilder.append(" ")
                    }
                    lastSentence = cleanSent
                }
            }
            onProgress(p + 1, totalPages)
        }

        val fullText = fullTextBuilder.toString().trim()
        if (fullText.isEmpty()) return@withContext null

        // Split into maximum TTS batch chunks (3800 chars)
        val textChunks = fullText.chunked(3800)

        // 3. FAST SEQUENTIAL TTS SYNTHESIS
        var tts: TextToSpeech? = null
        var isInitSuccess = false

        try {
            val initLock = Object()
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isInitSuccess = true
                }
                synchronized(initLock) { initLock.notifyAll() }
            }

            synchronized(initLock) {
                if (!isInitSuccess) initLock.wait(5000)
            }

            if (!isInitSuccess) {
                Log.e("AudioExporter", "TTS Initialization failed")
                return@withContext null
            }

            tts?.language = Locale.US
            tts?.setSpeechRate(speechRate * 1.3f) // Speed up synthesis rate for 2x faster export

            var isHeaderWritten = false
            var totalPcmBytes = 0L

            outputFile.outputStream().use { outStream ->
                for (idx in textChunks.indices) {
                    val chunkFile = File(outputDir, "temp_chunk_$idx.wav")
                    if (chunkFile.exists()) chunkFile.delete()

                    val utteranceId = "batch_$idx"
                    val latch = CountDownLatch(1)
                    var isChunkSuccessful = false

                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            if (id == utteranceId) {
                                isChunkSuccessful = true
                                latch.countDown()
                            }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId) {
                                latch.countDown()
                            }
                        }
                        override fun onError(id: String?, errorCode: Int) {
                            if (id == utteranceId) {
                                latch.countDown()
                            }
                        }
                    })

                    val params = Bundle()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

                    val res = tts?.synthesizeToFile(textChunks[idx], params, chunkFile, utteranceId)
                    if (res == TextToSpeech.SUCCESS) {
                        latch.await(20, TimeUnit.SECONDS)
                    }

                    if (isChunkSuccessful && chunkFile.exists() && chunkFile.length() > 44) {
                        val bytes = chunkFile.readBytes()
                        if (bytes.size > 44) {
                            if (!isHeaderWritten) {
                                // Write initial 44-byte WAV header from first chunk
                                outStream.write(bytes, 0, 44)
                                isHeaderWritten = true
                            }
                            // Write raw PCM payload (skipping 44-byte header)
                            val pcmLen = bytes.size - 44
                            outStream.write(bytes, 44, pcmLen)
                            totalPcmBytes += pcmLen
                        }
                    }
                    chunkFile.delete()
                }
            }

            if (outputFile.exists() && outputFile.length() > 44) {
                updateWavHeaderSizes(outputFile, totalPcmBytes)
                outputFile
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("AudioExporter", "Export error: ${e.message}", e)
            null
        } finally {
            try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        }
    }

    fun getExportedAudioFiles(context: Context): List<File> {
        val outputDir = File(context.getExternalFilesDir(null), "exported_audio")
        if (!outputDir.exists()) return emptyList()
        return outputDir.listFiles { file -> file.extension == "wav" }?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun deleteAudioFile(file: File): Boolean {
        return if (file.exists()) file.delete() else false
    }
}
