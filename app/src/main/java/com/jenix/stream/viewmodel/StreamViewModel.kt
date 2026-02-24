package com.jenix.stream.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.jenix.stream.data.model.*
import com.jenix.stream.data.preferences.AppPreferences
import com.jenix.stream.data.repository.AppDatabase
import com.jenix.stream.onvif.OnvifDiscovery
import com.jenix.stream.scheduler.StreamScheduler
import com.jenix.stream.service.StreamingService
import android.util.Log
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class StreamViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()
    private val prefs = AppPreferences(ctx)
    private val db = AppDatabase.getInstance(ctx)
    private val discovery = OnvifDiscovery(ctx)
    private val scheduler = StreamScheduler(ctx)

    // ── Stream state (from service) ───────────────────────────────────────────
    val streamStats: StateFlow<StreamStats> = StreamingService.streamStats
    val logLines: StateFlow<List<String>> = StreamingService.logLines
    /** Non-empty when the current stream was started by a schedule. */
    val scheduledByLabel: StateFlow<String> = StreamingService.scheduledByLabel

    // ── Config ────────────────────────────────────────────────────────────────
    val streamConfig: StateFlow<StreamConfig> = prefs.streamConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreamConfig())

    // ── Auth ──────────────────────────────────────────────────────────────────
    val userProfile: StateFlow<UserProfile?> = prefs.userProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val isLoggedIn: StateFlow<Boolean> = prefs.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ── Camera scan ───────────────────────────────────────────────────────────
    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())
    val cameras: StateFlow<List<Camera>> = _cameras

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _scanMessage = MutableStateFlow("")
    val scanMessage: StateFlow<String> = _scanMessage

    // ── Probe ─────────────────────────────────────────────────────────────────
    private val _probeResult = MutableStateFlow<ProbeResult?>(null)
    val probeResult: StateFlow<ProbeResult?> = _probeResult

    private val _isProbing = MutableStateFlow(false)
    val isProbing: StateFlow<Boolean> = _isProbing

    // ── Schedules ─────────────────────────────────────────────────────────────
    val schedules: StateFlow<List<Schedule>> = db.scheduleDao().getAllSchedules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The next enabled schedule that will fire (earliest start time). */
    val nextSchedule: StateFlow<Schedule?> = schedules
        .map { list -> computeNextSchedule(list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ── UI feedback ──────────────────────────────────────────────────────────
    private val _snackMessage = MutableStateFlow<String?>(null)
    val snackMessage: StateFlow<String?> = _snackMessage

    // ════════════════════════════════════════════════════════════════════════
    // STREAMING
    // ════════════════════════════════════════════════════════════════════════
    fun startStream() {
        val config = streamConfig.value
        if (config.rtspUrl.isBlank()) { snack("Enter RTSP URL first"); return }
        if (config.ytEnabled && config.ytKey.isBlank()) { snack("Enter YouTube Stream Key"); return }
        if (!config.ytEnabled && !config.fbEnabled) { snack("Enable YouTube or Facebook output"); return }

        // Pre-populate log with session header for diagnostic purposes
        val u = userProfile.value
        StreamingService.appendLog("INFO: ═══════════════════════════════════════")
        StreamingService.appendLog("INFO: STREAM SESSION START — MANUAL")
        if (u != null) {
            val displayName = u.name.ifBlank { u.email }
            StreamingService.appendLog("INFO: User : $displayName")
            if (u.email.isNotBlank() && u.name.isNotBlank())
                StreamingService.appendLog("INFO: Email: ${u.email}")
            if (u.mobile.isNotBlank())
                StreamingService.appendLog("INFO: Mobile: ${u.mobile}")
            if (u.city.isNotBlank())
                StreamingService.appendLog("INFO: City : ${u.city}")
        }

        val intent = StreamingService.buildIntent(ctx, config) // scheduleLabel="" → manual start
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    fun stopStream() {
        ctx.startService(Intent(ctx, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        })
    }

    // ════════════════════════════════════════════════════════════════════════
    // SETTINGS SAVE
    // ════════════════════════════════════════════════════════════════════════
    fun saveConfig(config: StreamConfig) {
        viewModelScope.launch { prefs.saveStreamConfig(config) }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PROBE RTSP STREAM
    // Uses FFmpeg (not FFprobe) to detect stream info from stderr log output.
    // This works with ALL ffmpeg-kit packages including the 16kb community fork.
    // Strategy: run ffmpeg with -t 3 (capture 3 seconds then stop).
    //           Parse the stderr output for "Stream #0:x" lines which contain
    //           codec/resolution/fps info. Exit code will be non-zero (we stop
    //           it intentionally) but we still get all stream info from logs.
    // ════════════════════════════════════════════════════════════════════════
    fun probeStream(rtspUrl: String, transport: String) {
        if (rtspUrl.isBlank()) return
        _isProbing.value = true
        _probeResult.value = null

        viewModelScope.launch(Dispatchers.IO) {
            val logOutput = StringBuilder()

            // Run FFmpeg briefly - we deliberately stop after 0 frames
            // -vframes 0 = grab 0 frames (just read stream header info)
            // -t 5 = timeout after 5 seconds maximum
            // FFmpeg prints full stream info to stderr before processing any frames
            val cmd = buildString {
                append("-rtsp_transport $transport ")
                append("-timeout 5000000 ")
                append("-analyzeduration 2000000 ")
                append("-probesize 2000000 ")
                append("-i '$rtspUrl' ")
                append("-vframes 0 ")   // capture zero frames = just probe headers
                append("-f null /dev/null")
            }

            val session = FFmpegKit.execute(cmd)

            // Collect ALL log output (FFmpeg prints stream info here even on failure)
            val allLogs = session.logs ?: emptyList()
            allLogs.forEach { log -> logOutput.append(log.message).append("\n") }

            // Also add the fail stack trace which contains stream info
            session.failStackTrace?.let { logOutput.append(it) }

            val fullOutput = logOutput.toString()
            Log.d("JenixProbe", "FFmpeg output:\n$fullOutput")

            // Parse stream info from FFmpeg stderr
            val parsed = parseFFmpegStreamInfo(fullOutput)

            if (parsed != null) {
                val (issues, suggestions) = buildCompatibilityReport(
                    parsed.videoCodec, parsed.audioCodec,
                    parsed.width, parsed.height, parsed.fps
                )
                _probeResult.value = ProbeResult(
                    success = true,
                    videoCodec = parsed.videoCodec,
                    width = parsed.width,
                    height = parsed.height,
                    fps = parsed.fps,
                    audioCodec = parsed.audioCodec,
                    sampleRate = parsed.sampleRate,
                    compatIssues = issues,
                    suggestions = suggestions
                )
            } else {
                // Could not parse stream info
                val errorMsg = when {
                    fullOutput.contains("Connection refused") -> "Connection refused — check camera IP and port"
                    fullOutput.contains("401") || fullOutput.contains("Unauthorized") ->
                        "Authentication failed — check username/password"
                    fullOutput.contains("No route to host") || fullOutput.contains("Network unreachable") ->
                        "Camera not reachable — check WiFi and camera IP"
                    fullOutput.contains("Invalid data") -> "Invalid RTSP stream — check camera settings"
                    else -> "Cannot connect to stream. Check URL, credentials, and network."
                }
                _probeResult.value = ProbeResult(success = false, errorMessage = errorMsg)
            }
            _isProbing.value = false
        }
    }

    // ── Parse FFmpeg stderr for stream information ────────────────────────────
    // FFmpeg always prints lines like:
    //   Stream #0:0: Video: h264 (Baseline), yuv420p, 1920x1080, 2000 kb/s, 25 fps
    //   Stream #0:1: Audio: aac, 44100 Hz, stereo, fltp, 128 kb/s
    private data class ParsedStreamInfo(
        val videoCodec: String,
        val width: Int,
        val height: Int,
        val fps: Double,
        val audioCodec: String,
        val sampleRate: Int
    )

    private fun parseFFmpegStreamInfo(output: String): ParsedStreamInfo? {
        var videoCodec = ""
        var width = 0
        var height = 0
        var fps = 0.0
        var audioCodec = ""
        var sampleRate = 0
        var foundVideo = false

        // FFmpegKit delivers stream info split across multiple log entries.
        // e.g. "  Stream #0:0" on one line, ": Video: h264..." on the next.
        // Pre-join continuation lines (starting with ':' or ',') with the previous line.
        val rawLines = output.lines()
        val lines = mutableListOf<String>()
        for (raw in rawLines) {
            val trimmed = raw.trim()
            if ((trimmed.startsWith(":") || trimmed.startsWith(",")) && lines.isNotEmpty()) {
                lines[lines.lastIndex] = lines.last() + " " + trimmed
            } else {
                lines.add(trimmed)
            }
        }

        lines.forEach { line ->
            val trimmed = line.trim()

            // Match video stream line: "Stream #0:0: Video: h264 ..."
            if (trimmed.contains("Stream #") && trimmed.contains("Video:")) {
                foundVideo = true

                // Extract codec name (word after "Video:")
                val videoMatch = Regex("Video:\\s+(\\w+)").find(trimmed)
                videoCodec = videoMatch?.groupValues?.get(1) ?: ""

                // Extract resolution like "1920x1080" or "1920×1080"
                val resMatch = Regex("(\\d{2,4})[x×](\\d{2,4})").find(trimmed)
                resMatch?.let {
                    width = it.groupValues[1].toIntOrNull() ?: 0
                    height = it.groupValues[2].toIntOrNull() ?: 0
                }

                // Extract fps — look for "25 fps", "29.97 fps", "25 tbr"
                val fpsMatch = Regex("([\\d.]+)\\s+(?:fps|tbr)").find(trimmed)
                fps = fpsMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            }

            // Match audio stream line: "Stream #0:1: Audio: aac, 44100 Hz ..."
            if (trimmed.contains("Stream #") && trimmed.contains("Audio:")) {
                val audioMatch = Regex("Audio:\\s+(\\w+)").find(trimmed)
                audioCodec = audioMatch?.groupValues?.get(1) ?: ""

                val srMatch = Regex("(\\d{4,6})\\s+Hz").find(trimmed)
                sampleRate = srMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        }

        // Return null only if we found absolutely no stream info
        // (means the connection itself failed)
        return if (foundVideo || videoCodec.isNotBlank()) {
            ParsedStreamInfo(videoCodec, width, height, fps, audioCodec, sampleRate)
        } else null
    }

    private fun buildCompatibilityReport(
        vc: String, ac: String, w: Int, h: Int, fps: Double
    ): Pair<List<CompatItem>, List<String>> {
        val items = mutableListOf<CompatItem>()
        val suggestions = mutableListOf<String>()

        // Video codec check
        when {
            vc.contains("h264", true) || vc.contains("avc", true) ->
                items.add(CompatItem("VIDEO", "H.264 ✓", CompatStatus.OK))
            vc.contains("hevc", true) || vc.contains("h265", true) || vc.contains("265", true) -> {
                items.add(CompatItem("VIDEO", "H.265 (HEVC)", CompatStatus.WARN))
                suggestions.add("H.265 detected: requires transcoding. In Settings set Video Codec → libx264. Or change camera to H.264 for best performance.")
            }
            vc.contains("mjpeg", true) || vc.contains("jpg", true) -> {
                items.add(CompatItem("VIDEO", "MJPEG ✗", CompatStatus.ERROR))
                suggestions.add("MJPEG cannot be streamed to YouTube. Change camera codec to H.264 in camera web UI.")
            }
            vc.isNotBlank() -> {
                val label = if (vc.length > 8) "PRIVATE" else vc.uppercase()
                items.add(CompatItem("VIDEO", "$label ⚠", CompatStatus.WARN))
                suggestions.add("Unknown/private codec '$vc' — may not be compatible. Change camera to H.264.")
            }
        }

        // Resolution
        if (w > 0) {
            items.add(CompatItem("RES", "${w}×${h}",
                if (w >= 640) CompatStatus.OK else CompatStatus.WARN))
            if (w < 640) suggestions.add("Low resolution. Increase to 1280×720 minimum in camera settings")
        }

        // FPS
        if (fps > 0) {
            items.add(CompatItem("FPS", "${fps.toInt()}fps",
                if (fps >= 15) CompatStatus.OK else CompatStatus.WARN))
            if (fps < 15) suggestions.add("Low FPS. Set camera to minimum 15fps in Video settings")
        }

        // Audio codec
        when {
            ac.contains("aac", true) ->
                items.add(CompatItem("AUDIO", "AAC ✓", CompatStatus.OK))
            ac.contains("pcm", true) || ac.contains("g711", true) || ac.contains("g726", true) -> {
                items.add(CompatItem("AUDIO", "${ac.uppercase()}→AAC", CompatStatus.WARN))
                suggestions.add("Audio will be transcoded to AAC (required by YouTube)")
            }
            ac.isNotBlank() ->
                items.add(CompatItem("AUDIO", ac.uppercase(), CompatStatus.WARN))
        }

        // Overall YouTube readiness
        val ytReady = items.none { it.status == CompatStatus.ERROR }
        items.add(CompatItem("YT READY", if (ytReady) "YES ✓" else "NEEDS FIX",
            if (ytReady) CompatStatus.OK else CompatStatus.ERROR))

        return Pair(items, suggestions)
    }

    // ════════════════════════════════════════════════════════════════════════
    // CAMERA DISCOVERY
    // ════════════════════════════════════════════════════════════════════════
    fun startCameraScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        _cameras.value = emptyList()

        discovery.onCameraFound = { camera ->
            _cameras.value = _cameras.value + camera
        }
        discovery.onProgress = { msg -> _scanMessage.value = msg }

        viewModelScope.launch(Dispatchers.IO) {
            discovery.discover()
            _isScanning.value = false
            _scanMessage.value = "Scan complete: ${_cameras.value.size} device(s) found"
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SCHEDULES
    // ════════════════════════════════════════════════════════════════════════
    fun addSchedule(schedule: Schedule) {
        viewModelScope.launch {
            val id = db.scheduleDao().insert(schedule)
            scheduler.scheduleAlarm(schedule.copy(id = id.toInt()))
            val label = if (schedule.label.isNotBlank()) " \"${schedule.label}\"" else ""
            snack("⏰ Schedule$label added — ${schedule.startFormatted} → ${schedule.stopFormatted}")
        }
    }

    fun deleteSchedule(schedule: Schedule) {
        viewModelScope.launch {
            scheduler.cancelAlarm(schedule.id)
            db.scheduleDao().delete(schedule)
            snack("Schedule deleted")
        }
    }

    fun toggleSchedule(schedule: Schedule, enabled: Boolean) {
        viewModelScope.launch {
            db.scheduleDao().setEnabled(schedule.id, enabled)
            if (enabled) scheduler.scheduleAlarm(schedule)
            else scheduler.cancelAlarm(schedule.id)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // AUTH
    // ════════════════════════════════════════════════════════════════════════
    fun login(email: String) {
        viewModelScope.launch {
            val existing = userProfile.value
            if (existing?.email == email) return@launch
            prefs.saveUserProfile(UserProfile(email = email, createdAt = System.currentTimeMillis()))
        }
    }

    fun updateProfile(name: String, mobile: String, city: String) {
        viewModelScope.launch {
            val current = userProfile.value ?: return@launch
            prefs.saveUserProfile(current.copy(name = name, mobile = mobile, city = city))
            snack("Profile saved")
        }
    }

    fun logout() { viewModelScope.launch { prefs.logout() } }

    // ════════════════════════════════════════════════════════════════════════
    // EXPORT
    // ════════════════════════════════════════════════════════════════════════
    fun exportSettingsJson(): String {
        val config = streamConfig.value
        return """
{
  "app": "JenixStream",
  "version": "${AppConstants.APP_VERSION}",
  "exported": "${java.util.Date()}",
  "settings": {
    "rtspUrl": "${config.rtspUrl}",
    "ytUrl": "${config.ytUrl}",
    "ytKey": "${config.ytKey}",
    "fbUrl": "${config.fbUrl}",
    "ytEnabled": ${config.ytEnabled},
    "fbEnabled": ${config.fbEnabled},
    "vcodec": "${config.vcodec}",
    "acodec": "${config.acodec}",
    "bitrate": "${config.bitrate}",
    "rtspTransport": "${config.rtspTransport}"
  }
}""".trimIndent()
    }

    fun clearLogs() { StreamingService.logLines.value = emptyList() }

    /** Build a shareable plain-text log with user and device context header. */
    fun buildShareableLog(): String {
        val u = userProfile.value
        val config = streamConfig.value
        val sb = StringBuilder()
        sb.appendLine("╔══════════════════════════════════════════╗")
        sb.appendLine("║        JENIX STREAM — DIAGNOSTIC LOG      ║")
        sb.appendLine("╚══════════════════════════════════════════╝")
        sb.appendLine("App     : ${AppConstants.APP_NAME} v${AppConstants.APP_VERSION} (build ${AppConstants.BUILD_NUMBER})")
        sb.appendLine("Date    : ${java.util.Date()}")
        sb.appendLine()
        sb.appendLine("── USER INFO ───────────────────────────────")
        sb.appendLine("Name    : ${u?.name?.ifBlank { "N/A" } ?: "N/A"}")
        sb.appendLine("Email   : ${u?.email?.ifBlank { "N/A" } ?: "N/A"}")
        sb.appendLine("Mobile  : ${u?.mobile?.ifBlank { "N/A" } ?: "N/A"}")
        sb.appendLine("City    : ${u?.city?.ifBlank { "N/A" } ?: "N/A"}")
        sb.appendLine()
        sb.appendLine("── DEVICE INFO ─────────────────────────────")
        sb.appendLine("Make    : ${Build.MANUFACTURER}")
        sb.appendLine("Model   : ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI     : ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
        sb.appendLine()
        sb.appendLine("── STREAM CONFIG ───────────────────────────")
        sb.appendLine("RTSP    : ${config.rtspUrl.ifBlank { "N/A" }}")
        sb.appendLine("Codec   : ${config.vcodec} / ${config.acodec}")
        sb.appendLine("Bitrate : ${config.bitrate}")
        sb.appendLine("YT      : ${if (config.ytEnabled) "enabled" else "disabled"}")
        sb.appendLine("FB      : ${if (config.fbEnabled) "enabled" else "disabled"}")
        sb.appendLine()
        sb.appendLine("── STREAM LOG ──────────────────────────────")
        logLines.value.forEach { sb.appendLine(it) }
        return sb.toString()
    }

    /** Write diagnostic log to a temp file and open the system share sheet. */
    fun shareStreamLog() {
        viewModelScope.launch {
            try {
                val file = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val text = buildShareableLog()
                    val f = File(ctx.cacheDir, "jenixstream_log.txt")
                    f.writeText(text)
                    f
                }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Jenix Stream — Diagnostic Log")
                    putExtra(Intent.EXTRA_TEXT, "Jenix Stream diagnostic log attached.")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(Intent.createChooser(intent, "Share Stream Log...").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                snack("Could not share log: ${e.message}")
            }
        }
    }

    // ── Schedule helpers ──────────────────────────────────────────────────────
    private fun computeNextSchedule(list: List<Schedule>): Schedule? {
        return list
            .filter { it.enabled }
            .mapNotNull { s ->
                val t = computeNextTriggerMs(s.startHour, s.startMinute, s.daysList)
                if (t != null) s to t else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun computeNextTriggerMs(hour: Int, minute: Int, days: List<String>): Long? {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (days.isEmpty()) return cal.timeInMillis // "once" pattern
        val abbr = mapOf(
            Calendar.MONDAY to "mon", Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed",
            Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri",
            Calendar.SATURDAY to "sat", Calendar.SUNDAY to "sun"
        )
        for (i in 0..6) {
            if (days.contains(abbr[cal.get(Calendar.DAY_OF_WEEK)])) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════════════
    // IMPORT SETTINGS FROM BACKUP JSON
    // ════════════════════════════════════════════════════════════════════════
    fun importSettingsJson(json: String) {
        viewModelScope.launch {
            try {
                val current = streamConfig.value
                val imported = StreamConfig(
                    rtspUrl      = json.jsonString("rtspUrl")      ?: current.rtspUrl,
                    ytUrl        = json.jsonString("ytUrl")        ?: current.ytUrl,
                    ytKey        = json.jsonString("ytKey")        ?: current.ytKey,
                    fbUrl        = json.jsonString("fbUrl")        ?: current.fbUrl,
                    ytEnabled    = json.jsonBool("ytEnabled")      ?: current.ytEnabled,
                    fbEnabled    = json.jsonBool("fbEnabled")      ?: current.fbEnabled,
                    vcodec       = json.jsonString("vcodec")       ?: current.vcodec,
                    acodec       = json.jsonString("acodec")       ?: current.acodec,
                    bitrate      = json.jsonString("bitrate")      ?: current.bitrate,
                    rtspTransport= json.jsonString("rtspTransport")?: current.rtspTransport,
                    preset       = json.jsonString("preset")       ?: current.preset,
                    resolution   = json.jsonString("resolution")   ?: current.resolution
                )
                prefs.saveStreamConfig(imported)
                snack("✓ Settings imported — go to Stream tab to start")
            } catch (e: Exception) {
                snack("⚠ Import failed: ${e.message}")
            }
        }
    }

    private fun String.jsonString(key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]*)"""").find(this)?.groupValues?.get(1)

    private fun String.jsonBool(key: String): Boolean? =
        Regex(""""$key"\s*:\s*(true|false)""").find(this)?.groupValues?.get(1)?.toBooleanStrictOrNull()

    private fun snack(msg: String) { _snackMessage.value = msg }
    fun clearSnack() { _snackMessage.value = null }
}
