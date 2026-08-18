package com.l1khith.readrust.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.l1khith.readrust.PdfHelper
import com.l1khith.readrust.PdfResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class AudioExporterService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var exportJob: Job? = null
    private var ttsEngine: TtsEngine? = null

    companion object {
        const val ACTION_START_EXPORT = "action_start_export"
        const val ACTION_CANCEL = "action_cancel"
        const val EXTRA_BOOK_TITLE = "extra_book_title"
        const val EXTRA_TOTAL_PAGES = "extra_total_pages"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "tts_export_channel"

        fun startExport(context: Context, bookTitle: String, totalPages: Int) {
            val intent = Intent(context, AudioExporterService::class.java).apply {
                action = ACTION_START_EXPORT
                putExtra(EXTRA_BOOK_TITLE, bookTitle)
                putExtra(EXTRA_TOTAL_PAGES, totalPages)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ttsEngine = TtsEngine(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_EXPORT -> {
                val title = intent.getStringExtra(EXTRA_BOOK_TITLE) ?: "Book"
                val totalPages = intent.getIntExtra(EXTRA_TOTAL_PAGES, 1)
                startExportPipeline(title, totalPages)
            }
            ACTION_CANCEL -> cancelExport()
        }
        return START_NOT_STICKY
    }

    private fun startExportPipeline(title: String, totalPages: Int) {
        startForeground(NOTIFICATION_ID, buildNotification("Preparing export...", 0))

        exportJob = serviceScope.launch {
            try {
                val outputDir = File(getExternalFilesDir(null), "exported_audio").apply { mkdirs() }
                val tempDir = File(cacheDir, "tts_chunks_${System.currentTimeMillis()}").apply { mkdirs() }
                val safeName = title.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val finalWavFile = File(outputDir, "${safeName}_audio.wav")

                // 1. Gather all deduplicated text across pages
                val fullTextBuilder = StringBuilder()
                var lastSentence = ""

                for (p in 0 until totalPages) {
                    if (!isActive) throw CancellationException()
                    val res = PdfHelper.extractSentencesWithBoundsFromPage(p)
                    if (res is PdfResult.Success) {
                        for (item in res.value) {
                            val clean = item.text.trim()
                            if (clean.isNotBlank() && clean != lastSentence) {
                                fullTextBuilder.append(clean)
                                if (!clean.endsWith(".") && !clean.endsWith("!") && !clean.endsWith("?")) {
                                    fullTextBuilder.append(". ")
                                } else {
                                    fullTextBuilder.append(" ")
                                }
                                lastSentence = clean
                            }
                        }
                    }
                }

                val fullText = fullTextBuilder.toString().trim()
                if (fullText.isEmpty()) {
                    updateNotification("Export failed: No text extracted", 0)
                    stopSelf()
                    return@launch
                }

                // 2. Split into safe 3500-character chunks
                val textChunks = fullText.chunked(3500)
                val totalChunks = textChunks.size
                val tempChunkFiles = mutableListOf<File>()

                // 3. Synthesize each chunk to temp WAV file
                for (idx in textChunks.indices) {
                    if (!isActive) throw CancellationException()
                    val percent = ((idx + 1) * 90) / totalChunks
                    updateNotification("Synthesizing chunk ${idx + 1} of $totalChunks...", percent)

                    val chunkFile = File(tempDir, "chunk_%04d.wav".format(idx))
                    val synthResult = ttsEngine?.synthesizeToFile(textChunks[idx], chunkFile)

                    if (synthResult != null && synthResult.isSuccess && chunkFile.exists()) {
                        tempChunkFiles.add(chunkFile)
                    }
                }

                if (tempChunkFiles.isEmpty()) {
                    updateNotification("Export failed: Synthesis produced no chunks", 0)
                    stopSelf()
                    return@launch
                }

                // 4. Invoke native Rust stitcher via JNI
                updateNotification("Stitching audio files...", 95)
                val pathsArray = tempChunkFiles.map { it.absolutePath }.toTypedArray()
                val stitchSuccess = NativeAudioBridge.nativeStitchWavFiles(pathsArray, finalWavFile.absolutePath)

                // Cleanup temp files
                tempDir.deleteRecursively()

                if (stitchSuccess && finalWavFile.exists()) {
                    updateNotification("Export complete: ${finalWavFile.name}", 100)
                } else {
                    updateNotification("Export failed during WAV stitching", 0)
                }

            } catch (e: CancellationException) {
                updateNotification("Export cancelled", 0)
            } catch (e: Exception) {
                updateNotification("Export failed: ${e.message}", 0)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelExport() {
        exportJob?.cancel()
        ttsEngine?.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(content: String, progress: Int): Notification {
        val channelId = CHANNEL_ID
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Audio Export", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val cancelIntent = Intent(this, AudioExporterService::class.java).apply { action = ACTION_CANCEL }
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ReadRust Audio Export")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    override fun onDestroy() {
        ttsEngine?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
