package com.jenix.stream.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.*
import com.jenix.stream.MainActivity
import com.jenix.stream.R
import com.jenix.stream.data.model.AppConstants
import com.jenix.stream.data.model.StreamConfig
import com.jenix.stream.data.model.StreamStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StreamingService : Service() {

    companion object {
        const val TAG = "JenixStreamService"
        const val ACTION_START = "com.jenix.stream.START"
        const val ACTION_STOP = "com.jenix.stream.STOP"
        const val EXTRA_RTSP_URL = "rtsp_url"
        const val EXTRA_YT_URL = "yt_url"
        const val EXTRA_YT_KEY = "yt_key"
        const val EXTRA_FB_URL = "fb_url"
        const val EXTRA_YT_ENABLED = "yt_enabled"
        const val EXTRA_FB_ENABLED = "fb_enabled"
        const val EXTRA_VCODEC = "vcodec"
        const val EXTRA_ACODEC = "acodec"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_RESOLUTION = "resolution"
        const val EXTRA_SCHEDULE_LABEL = "schedule_label"

        // Singleton state - accessible from ViewModel
        val streamStats = MutableStateFlow(StreamStats())
        val logLines = MutableStateFlow<List<String>>(emptyList())
        // Non-empty when stream was started by a schedule; cleared on stop
        val scheduledByLabel = MutableStateFlow("")

        /** Write a timestamped log line to the shared log buffer. Can be called from any context. */
        fun appendLog(message: String) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "[$ts] $message"
            Log.d(TAG, line)
            val current = logLines.value.toMutableList()
            current.add(line)
            if (current.size > 500) current.removeAt(0)
            logLines.value = current
        }

        fun buildIntent(context: Context, config: StreamConfig, scheduleLabel: String = ""): Intent {
            return Intent(context, StreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RTSP_URL, config.rtspUrl)
                putExtra(EXTRA_YT_URL, config.ytUrl)
                putExtra(EXTRA_YT_KEY, config.ytKey)
                putExtra(EXTRA_FB_URL, config.fbUrl)
                putExtra(EXTRA_YT_ENABLED, config.ytEnabled)
                putExtra(EXTRA_FB_ENABLED, config.fbEnabled)
                putExtra(EXTRA_VCODEC, config.vcodec)
                putExtra(EXTRA_ACODEC, config.acodec)
                putExtra(EXTRA_BITRATE, config.bitrate)
                putExtra(EXTRA_TRANSPORT, config.rtspTransport)
                putExtra(EXTRA_PRESET, config.preset)
                putExtra(EXTRA_RESOLUTION, config.resolution)
                putExtra(EXTRA_SCHEDULE_LABEL, scheduleLabel)
            }
        }
    }

    private var sessionId: Long = -1
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null
    private var retryCount = 0
    private val MAX_RETRIES = 5
    private var currentConfig: StreamConfig? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = extractConfig(intent)
                val label = intent.getStringExtra(EXTRA_SCHEDULE_LABEL) ?: ""
                scheduledByLabel.value = label
                currentConfig = config
                retryCount = 0
                startForeground(AppConstants.STREAM_NOTIFICATION_ID, buildNotification("● LIVE — Starting stream..."))
                startStreaming(config)
            }
            ACTION_STOP -> {
                stopStreaming()
            }
        }
        return START_NOT_STICKY
    }

    private fun extractConfig(intent: Intent) = StreamConfig(
        rtspUrl = intent.getStringExtra(EXTRA_RTSP_URL) ?: "",
        ytUrl = intent.getStringExtra(EXTRA_YT_URL) ?: "rtmp://a.rtmp.youtube.com/live2",
        ytKey = intent.getStringExtra(EXTRA_YT_KEY) ?: "",
        fbUrl = intent.getStringExtra(EXTRA_FB_URL) ?: "",
        ytEnabled = intent.getBooleanExtra(EXTRA_YT_ENABLED, true),
        fbEnabled = intent.getBooleanExtra(EXTRA_FB_ENABLED, false),
        vcodec = intent.getStringExtra(EXTRA_VCODEC) ?: "copy",
        acodec = intent.getStringExtra(EXTRA_ACODEC) ?: "aac",
        bitrate = intent.getStringExtra(EXTRA_BITRATE) ?: "1000k",
        rtspTransport = intent.getStringExtra(EXTRA_TRANSPORT) ?: "tcp",
        preset = intent.getStringExtra(EXTRA_PRESET) ?: "veryfast",
        resolution = intent.getStringExtra(EXTRA_RESOLUTION) ?: "source"
    )

    private fun startStreaming(config: StreamConfig) {
        val cmd = buildFFmpegCommand(config)
        addLog("INFO: Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        addLog("INFO: App: ${AppConstants.APP_NAME} v${AppConstants.APP_VERSION} (build ${AppConstants.BUILD_NUMBER})")
        if (scheduledByLabel.value.isNotBlank()) {
            addLog("INFO: Started by schedule: \"${scheduledByLabel.value}\"")
        }
        addLog("INFO: Starting stream...")
        addLog("CMD: ${cmd.take(120)}...")

        streamStats.value = StreamStats(
            isRunning = true,
            startTimeMs = System.currentTimeMillis(),
            retryCount = retryCount
        )

        // Start stats ticker
        startStatsTicker()

        // Execute FFmpeg asynchronously
        sessionId = FFmpegKit.executeAsync(
            cmd,
            { session ->
                // Completion callback
                val returnCode = session.returnCode
                val isSuccess = ReturnCode.isSuccess(returnCode)
                val isKilled = ReturnCode.isCancel(returnCode)

                addLog(if (isSuccess) "OK: Stream ended cleanly" else "ERROR: FFmpeg exit code ${returnCode?.value}")

                if (!isKilled && !isSuccess && retryCount < MAX_RETRIES) {
                    val isConfigError = session.failStackTrace?.contains("Unrecognized option") == true ||
                        session.failStackTrace?.contains("Option not found") == true ||
                        session.failStackTrace?.contains("Invalid option") == true

                    if (isConfigError) {
                        addLog("ERROR: Fatal config error — not retrying. Check settings.")
                        onStreamStopped(session.failStackTrace ?: "Fatal config error")
                    } else {
                        retryCount++
                        val delayMs = (retryCount * 3000L).coerceAtMost(15000L)
                        addLog("WARN: Auto-retry #$retryCount in ${delayMs/1000}s...")
                        serviceScope.launch {
                            delay(delayMs)
                            currentConfig?.let { startStreaming(it) }
                        }
                    }
                } else {
                    onStreamStopped(if (isKilled) null else session.failStackTrace)
                }
            },
            { log ->
                // Log callback - parse FFmpeg output for stats
                val message = log.message ?: return@executeAsync
                parseFFmpegLog(message)
            },
            { statistics ->
                // Statistics callback - live bitrate/fps
                val fps = statistics.videoFps
                val kbps = statistics.bitrate / 1000.0
                val frames = statistics.videoFrameNumber.toLong()
                streamStats.value = streamStats.value.copy(
                    currentFps = fps.toDouble(),
                    currentKbps = kbps,
                    framesProcessed = frames
                )
                updateNotification("● LIVE — ${kbps.toInt()} kbps · ${fps.toInt()} fps")
            }
        ).sessionId
    }

    private fun buildFFmpegCommand(config: StreamConfig): String {
        val sb = StringBuilder()

        // Input options
        sb.append("-rtsp_transport ${config.rtspTransport} ")
        sb.append("-fflags +nobuffer+genpts ")
        sb.append("-flags low_delay ")
        sb.append("-timeout 5000000 ")
        sb.append("-analyzeduration 1000000 ")
        sb.append("-probesize 1000000 ")
        sb.append("-i '${config.rtspUrl}' ")

        // Video codec
        when (config.vcodec) {
            "copy" -> sb.append("-c:v copy ")
            "h264_mediacodec" -> {
                // Android hardware encoder - fastest, lowest battery
                sb.append("-c:v h264_mediacodec ")
                sb.append("-b:v ${config.bitrate} ")
            }
            else -> {
                // Software encode (libx264)
                sb.append("-c:v ${config.vcodec} ")
                sb.append("-preset ${config.preset} ")
                sb.append("-b:v ${config.bitrate} ")
                sb.append("-maxrate ${config.bitrate} ")
                val bitrateInt = config.bitrate.replace("k","").toIntOrNull() ?: 1000
                sb.append("-bufsize ${bitrateInt * 2}k ")
                sb.append("-tune zerolatency ")
                sb.append("-g 50 ")
            }
        }

        // Resolution scaling (only if not source)
        if (config.resolution != "source") {
            sb.append("-vf scale=${config.resolution.replace("x",":")} ")
        }

        // Audio codec
        when (config.acodec) {
            "copy" -> sb.append("-c:a copy ")
            else -> sb.append("-c:a aac -b:a 128k -ar 44100 ")
        }

        sb.append("-pix_fmt yuv420p ")

        // Build outputs
        val outputs = mutableListOf<String>()
        if (config.ytEnabled && config.ytUrl.isNotBlank() && config.ytKey.isNotBlank()) {
            outputs.add("${config.ytUrl}/${config.ytKey}")
        }
        if (config.fbEnabled && config.fbUrl.isNotBlank()) {
            outputs.add(config.fbUrl)
        }

        if (outputs.isEmpty()) {
            // No outputs - will fail gracefully
            sb.append("-f null /dev/null")
        } else if (outputs.size == 1) {
            sb.append("-f flv '${outputs[0]}'")
        } else {
            // Simultaneous multi-output using tee muxer
            val teeOutputs = outputs.joinToString("|") { "[f=flv]'$it'" }
            sb.append("-f tee '$teeOutputs'")
        }

        return sb.toString()
    }

    private fun parseFFmpegLog(message: String) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return
        if (trimmed.contains("frame=") || trimmed.contains("fps=")) return // handled by stats callback

        val level = when {
            trimmed.startsWith("Error") || trimmed.startsWith("error") -> "ERROR"
            trimmed.startsWith("Warning") || trimmed.startsWith("warning") -> "WARN"
            trimmed.contains("Stream #") -> "INFO"
            else -> "DBG"
        }

        // Extract codec info from stream opening
        if (trimmed.contains("Video:") || trimmed.contains("Audio:")) {
            addLog("INFO: $trimmed")
            parseCodecInfo(trimmed)
        } else if (level != "DBG") {
            addLog("$level: $trimmed")
        }
    }

    private fun parseCodecInfo(message: String) {
        val videoMatch = Regex("Video: (\\w+).*?(\\d+)x(\\d+)").find(message)
        val audioMatch = Regex("Audio: (\\w+)").find(message)
        var updated = streamStats.value
        videoMatch?.let {
            updated = updated.copy(
                videoCodec = it.groupValues[1],
                resolution = "${it.groupValues[2]}x${it.groupValues[3]}"
            )
        }
        audioMatch?.let { updated = updated.copy(audioCodec = it.groupValues[1]) }
        streamStats.value = updated
    }

    private fun onStreamStopped(error: String?) {
        statsJob?.cancel()
        streamStats.value = StreamStats(isRunning = false, errorMessage = error)
        scheduledByLabel.value = ""
        if (error != null) addLog("ERROR: $error")
        addLog("INFO: ─────────────────────────────────────")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopStreaming() {
        addLog("INFO: Stream stopped by user")
        if (sessionId != -1L) {
            FFmpegKit.cancel(sessionId)
        }
        FFmpegKit.cancel() // cancel all
        onStreamStopped(null)
    }

    private fun startStatsTicker() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                if (streamStats.value.isRunning) {
                    streamStats.value = streamStats.value.copy() // trigger recompose
                }
            }
        }
    }

    private fun addLog(message: String) = appendLog(message)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            AppConstants.NOTIFICATION_CHANNEL_ID,
            "Jenix Stream",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Live streaming status"
            setShowBadge(true)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, AppConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Jenix Stream")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_stream)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_stop, "Stop Stream", stopPi)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(AppConstants.STREAM_NOTIFICATION_ID, buildNotification(content))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        FFmpegKit.cancel()
        super.onDestroy()
    }
}
