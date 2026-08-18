package com.l1khith.readrust.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class TtsEngine(context: Context) {

    private val mutex = Mutex()
    private var isInitSuccess = false
    private val initLock = Object()

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            isInitSuccess = true
            try {
                tts.language = Locale.US
                // Select neural / high-quality voice if available
                val neuralVoice = tts.voices?.find { voice ->
                    !voice.isNetworkConnectionRequired && voice.quality >= Voice.QUALITY_HIGH
                }
                neuralVoice?.let { tts.voice = it }
            } catch (e: Exception) {
                Log.w("TtsEngine", "Failed to configure neural voice", e)
            }
        }
        synchronized(initLock) { initLock.notifyAll() }
    }

    suspend fun synthesizeToFile(
        text: String,
        outputFile: File,
        speechRate: Float = 1.2f,
        utteranceId: String = UUID.randomUUID().toString()
    ): Result<File> = withContext(Dispatchers.IO) {
        synchronized(initLock) {
            if (!isInitSuccess) {
                try { initLock.wait(4000) } catch (_: Exception) {}
            }
        }

        if (!isInitSuccess) {
            return@withContext Result.failure(Exception("TTS engine initialization failed"))
        }

        mutex.withLock {
            val deferred = CompletableDeferred<Boolean>()

            tts.setSpeechRate(speechRate)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        deferred.complete(true)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId) {
                        deferred.complete(false)
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) {
                        deferred.complete(false)
                    }
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            if (outputFile.exists()) outputFile.delete()

            val res = tts.synthesizeToFile(text, params, outputFile, utteranceId)
            if (res == TextToSpeech.SUCCESS) {
                val success = deferred.await()
                if (success && outputFile.exists() && outputFile.length() > 44) {
                    Result.success(outputFile)
                } else {
                    Result.failure(Exception("TTS synthesis produced invalid file"))
                }
            } else {
                Result.failure(Exception("TTS synthesizeToFile error code: $res"))
            }
        }
    }

    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (_: Exception) {}
    }
}
