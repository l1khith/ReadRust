package com.l1khith.readrust

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class ReaderService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private var tts: TextToSpeech? = null
    @Volatile private var isTtsReady = false

    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 5

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var wakeLock: PowerManager.WakeLock? = null

    private var sentences: List<String> = emptyList()
    private var currentSentenceIndex = 0

    private val _currentSentenceIndexFlow = MutableStateFlow(0)
    val currentSentenceIndexFlow: StateFlow<Int> = _currentSentenceIndexFlow

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow

    private val _ttsErrorFlow = MutableStateFlow<String?>(null)
    val ttsErrorFlow: StateFlow<String?> = _ttsErrorFlow

    var onPageEndReached: (() -> Unit)? = null

    private lateinit var mediaSession: MediaSessionCompat

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            consecutiveErrors = 0
            refreshWakeLock()
        }
        override fun onDone(utteranceId: String?) {
            consecutiveErrors = 0
            serviceScope.launch { nextSentence() }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.w(TAG, "TTS onError for utterance: $utteranceId")
            handleTtsError()
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w(TAG, "TTS onError for utterance: $utteranceId, code: $errorCode")
            handleTtsError()
        }
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            Log.d(TAG, "TTS onStop for utterance: $utteranceId, interrupted: $interrupted")
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        setupMediaSession()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReaderApp::TTSPlayLock")
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "ReaderService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resumeAudio() }
                override fun onPause() { pauseAudio() }
                override fun onSkipToNext() { nextSentence() }
                override fun onSkipToPrevious() { prevSentence() }
            })
            isActive = true
        }
    }

    private var currentSpeechRate = 1.0f
    private var currentPitch = 1.0f

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _ttsErrorFlow.value = "Text-to-Speech failed to initialize. Check system settings."
            return
        }

        val googleTtsEngine = "com.google.android.tts"
        val engines = tts?.engines?.map { it.name } ?: emptyList()
        val targetEngine = if (googleTtsEngine in engines) googleTtsEngine else null

        if (targetEngine != null && tts?.defaultEngine != targetEngine) {
            tts?.shutdown()
            tts = TextToSpeech(this, this, targetEngine)
            return
        }

        val result = tts?.setLanguage(Locale.US)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            _ttsErrorFlow.value = "English voice not available. Install it in Settings > Languages & Input."
            return
        }

        val bestVoice = tts?.voices
            ?.filter {
                it.locale.language == "en" &&
                !it.isNetworkConnectionRequired &&
                (it.quality >= Voice.QUALITY_HIGH ||
                 it.name.contains("neural", ignoreCase = true))
            }
            ?.maxByOrNull {
                when {
                    it.name.contains("neural2", ignoreCase = true) -> 4
                    it.name.contains("wavenet", ignoreCase = true) -> 3
                    it.name.contains("neural", ignoreCase = true) -> 2
                    it.quality == Voice.QUALITY_VERY_HIGH -> 1
                    else -> 0
                }
            }

        if (bestVoice != null) tts?.voice = bestVoice

        tts?.setSpeechRate(currentSpeechRate)
        tts?.setPitch(currentPitch)

        tts?.setOnUtteranceProgressListener(utteranceListener)
        isTtsReady = true
        consecutiveErrors = 0
        _ttsErrorFlow.value = null

        if (_isPlayingFlow.value && sentences.isNotEmpty()) {
            Log.d(TAG, "TTS reinitialized, resuming playback at sentence $currentSentenceIndex")
            speakCurrentSentence()
        }
    }

    fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(currentSpeechRate)
    }

    fun setPitch(pitch: Float) {
        currentPitch = pitch.coerceIn(0.5f, 2.0f)
        tts?.setPitch(currentPitch)
    }

    fun getAudioSettings(): Pair<Float, Float> = currentSpeechRate to currentPitch

    private fun cleanTextForTts(text: String): String {
        return text.trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\[\\](){}<>]"), "")
            .replace(Regex("[*_~`#]"), "")
            .replace(Regex("\\n+"), " ")
    }

    fun setSentences(newSentences: List<String>) {
        if (_isPlayingFlow.value) {
            tts?.stop()
        }

        sentences = newSentences
        currentSentenceIndex = 0
        _currentSentenceIndexFlow.value = 0

        if (_isPlayingFlow.value && sentences.isNotEmpty()) {
            updateForegroundNotification()
            speakCurrentSentence()
        }
    }

    private fun speakCurrentSentence() {
        val index = currentSentenceIndex
        if (index !in sentences.indices) return

        if (!isTtsHealthy()) {
            Log.w(TAG, "TTS not healthy, attempting reinitialization")
            reinitializeTts()
            return
        }

        val rawText = sentences[index]
        val cleanText = cleanTextForTts(rawText)

        if (cleanText.isBlank()) {
            serviceScope.launch { nextSentence() }
            return
        }

        val params = android.os.Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "id_$index")
        val result = tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "id_$index")
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "tts.speak() returned ERROR for sentence $index")
            handleTtsError()
        }
    }

    fun playSentence(index: Int) {
        if (!isTtsReady || index !in sentences.indices) return
        currentSentenceIndex = index
        _currentSentenceIndexFlow.value = index
        updateMediaState(PlaybackStateCompat.STATE_PLAYING)
        speakCurrentSentence()
    }

    fun pauseAudio() {
        tts?.stop()
        _isPlayingFlow.value = false
        mediaSession.isActive = false
        updateMediaState(PlaybackStateCompat.STATE_PAUSED)

        if (wakeLock?.isHeld == true) wakeLock?.release()

        updateForegroundNotification()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    fun resumeAudio() {
        _isPlayingFlow.value = true
        mediaSession.isActive = true

        refreshWakeLock()

        updateMediaState(PlaybackStateCompat.STATE_PLAYING)
        updateForegroundNotification()
        speakCurrentSentence()
    }

    private fun nextSentence() {
        if (currentSentenceIndex < sentences.size - 1) {
            currentSentenceIndex++
            _currentSentenceIndexFlow.value = currentSentenceIndex
            speakCurrentSentence()
        } else {
            serviceScope.launch(Dispatchers.Main) {
                onPageEndReached?.invoke()
            }
        }
    }

    private fun prevSentence() {
        if (currentSentenceIndex > 0) {
            playSentence(currentSentenceIndex - 1)
        }
    }

    private fun updateForegroundNotification() {
        val notification = NotificationCompat.Builder(this, "READER_CHANNEL")
            .setContentTitle("Reading Book")
            .setContentText(sentences.getOrElse(currentSentenceIndex) { "Reader Active" })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(
                android.R.drawable.ic_media_previous, "Prev",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
            )
            .addAction(
                if (_isPlayingFlow.value) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (_isPlayingFlow.value) "Pause" else "Play",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    if (_isPlayingFlow.value) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY
                )
            )
            .addAction(
                android.R.drawable.ic_media_next, "Next",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
            )
            .setOngoing(_isPlayingFlow.value)
            .build()

        startForeground(1, notification)
    }

    private fun updateMediaState(state: Int) {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "READER_CHANNEL",
            "Reader Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val earlyNotification = NotificationCompat.Builder(this, "READER_CHANNEL")
            .setContentTitle("Reader")
            .setContentText("Preparing…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setSilent(true)
            .build()
        startForeground(1, earlyNotification)

        androidx.media.session.MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        mediaSession.isActive = false
        mediaSession.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun isTtsHealthy(): Boolean {
        if (!isTtsReady || tts == null) return false
        return try {
            val probe = tts?.setLanguage(Locale.US)
            probe != TextToSpeech.LANG_MISSING_DATA && probe != TextToSpeech.LANG_NOT_SUPPORTED
        } catch (e: Exception) {
            Log.w(TAG, "TTS health check failed", e)
            false
        }
    }

    private fun reinitializeTts() {
        Log.w(TAG, "Reinitializing TTS engine")
        isTtsReady = false
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down old TTS", e)
        }
        tts = TextToSpeech(this, this)
    }

    private fun handleTtsError() {
        consecutiveErrors++
        if (consecutiveErrors > maxConsecutiveErrors) {
            Log.e(TAG, "Too many consecutive TTS errors ($consecutiveErrors), reinitializing")
            consecutiveErrors = 0
            serviceScope.launch {
                reinitializeTts()
            }
            return
        }
        serviceScope.launch {
            delay(500L * consecutiveErrors)
            if (_isPlayingFlow.value) {
                speakCurrentSentence()
            }
        }
    }

    private fun refreshWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock refresh failed", e)
        }
    }

    companion object {
        private const val TAG = "ReaderService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): ReaderService = this@ReaderService
    }

    override fun onBind(intent: Intent): IBinder = binder
}
