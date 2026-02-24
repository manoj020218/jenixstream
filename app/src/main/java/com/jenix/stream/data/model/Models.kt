package com.jenix.stream.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

// ── Stream Configuration ──────────────────────────────────────────────────────
data class StreamConfig(
    val rtspUrl: String = "",
    val ytUrl: String = "rtmp://a.rtmp.youtube.com/live2",
    val ytKey: String = "",
    val fbUrl: String = "",
    val ytEnabled: Boolean = true,
    val fbEnabled: Boolean = false,
    val vcodec: String = "copy",      // copy | libx264 | h264_mediacodec
    val acodec: String = "aac",       // copy | aac
    val bitrate: String = "1000k",
    val rtspTransport: String = "tcp",
    val preset: String = "veryfast",
    val resolution: String = "source"
)

// ── Stream Statistics (live) ──────────────────────────────────────────────────
data class StreamStats(
    val isRunning: Boolean = false,
    val startTimeMs: Long = 0L,
    val framesProcessed: Long = 0L,
    val currentFps: Double = 0.0,
    val currentKbps: Double = 0.0,
    val errorMessage: String? = null,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val resolution: String = "",
    val retryCount: Int = 0
) {
    val elapsedSeconds: Long get() =
        if (isRunning && startTimeMs > 0) (System.currentTimeMillis() - startTimeMs) / 1000L else 0L

    val durationFormatted: String get() {
        val s = elapsedSeconds
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}

// ── Probe Result ──────────────────────────────────────────────────────────────
data class ProbeResult(
    val success: Boolean,
    val videoCodec: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val audioCodec: String = "",
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val errorMessage: String = "",
    val compatIssues: List<CompatItem> = emptyList(),
    val suggestions: List<String> = emptyList()
)

data class CompatItem(
    val label: String,
    val value: String,
    val status: CompatStatus
)

enum class CompatStatus { OK, WARN, ERROR }

// ── Discovered Camera ─────────────────────────────────────────────────────────
data class Camera(
    val ip: String,
    val port: String = "80",
    val name: String = "IP Camera",
    val xaddr: String = "",
    val brand: String = ""
)

// ── Schedule ──────────────────────────────────────────────────────────────────
@Entity(tableName = "schedules")
data class Schedule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val startHour: Int,
    val startMinute: Int,
    val stopHour: Int,
    val stopMinute: Int,
    val repeatPattern: String, // everyday | weekdays | weekends | custom | once
    val days: String,          // comma-separated: mon,tue,wed
    val enabled: Boolean = true,
    val label: String = ""
) {
    val startFormatted: String get() = "%02d:%02d".format(startHour, startMinute)
    val stopFormatted: String get() = "%02d:%02d".format(stopHour, stopMinute)
    val daysList: List<String> get() = days.split(",").filter { it.isNotBlank() }
}

// ── User Profile ──────────────────────────────────────────────────────────────
data class UserProfile(
    val email: String = "",
    val name: String = "",
    val mobile: String = "",
    val city: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

// ── Camera RTSP Brand Templates ───────────────────────────────────────────────
data class CameraRtspTemplate(
    val brand: String,
    val paths: List<Pair<String, String>>,  // path value → display label
    val defaultPort: String = "554",
    val defaultUser: String = "admin"
)

object CameraTemplates {
    const val MANUAL = "Manual / Generic"

    val all = listOf(
        CameraRtspTemplate("Hikvision", listOf(
            "/Streaming/Channels/101" to "Ch1 Main Stream",
            "/Streaming/Channels/102" to "Ch1 Sub Stream",
            "/Streaming/Channels/201" to "Ch2 Main Stream",
            "/Streaming/Channels/1"   to "Legacy Main",
        )),
        CameraRtspTemplate("Dahua", listOf(
            "/cam/realmonitor?channel=1&subtype=0" to "Ch1 Main Stream",
            "/cam/realmonitor?channel=1&subtype=1" to "Ch1 Sub Stream",
            "/cam/realmonitor?channel=2&subtype=0" to "Ch2 Main Stream",
        )),
        CameraRtspTemplate("Xiongmai / XM", listOf(
            "/user=admin&password=&channel=1&stream=0.sdp" to "Ch1 Main Stream",
            "/user=admin&password=&channel=1&stream=1.sdp" to "Ch1 Sub Stream",
            "/0" to "Stream 0 (shorthand)",
            "/1" to "Stream 1 (shorthand)",
        )),
        CameraRtspTemplate("Hanwha Techwin", listOf(
            "/profile1/media.smp" to "Profile 1 (Main)",
            "/profile2/media.smp" to "Profile 2 (Sub)",
        )),
        CameraRtspTemplate("UNV (Uniview)", listOf(
            "/media/video1" to "Main Stream",
            "/media/video2" to "Sub Stream",
        )),
        CameraRtspTemplate("Samsung", listOf(
            "/profile1/media.smp" to "Profile 1 (Main)",
            "/profile2/media.smp" to "Profile 2 (Sub)",
        )),
        CameraRtspTemplate("Siemens", listOf(
            "/stream1" to "Stream 1 (Main)",
            "/stream2" to "Stream 2 (Sub)",
        )),
        CameraRtspTemplate("Panasonic", listOf(
            "/MediaInput/h264/stream_1"  to "H.264 Stream 1 (Main)",
            "/MediaInput/h264/stream_2"  to "H.264 Stream 2 (Sub)",
            "/MediaInput/mpeg4/stream_1" to "MPEG4 Stream (older models)",
        )),
        CameraRtspTemplate("Honeywell", listOf(
            "/Streaming/Channels/1" to "Main Stream",
            "/video1"               to "Video 1 (alt)",
        )),
        CameraRtspTemplate("EzViz", listOf(
            "/h264/ch1/main/av_stream" to "Ch1 Main Stream",
            "/h264/ch1/sub/av_stream"  to "Ch1 Sub Stream",
        )),
        CameraRtspTemplate("Juan Technology", listOf(
            "/ch1/0" to "Ch1 Main Stream",
            "/ch1/1" to "Ch1 Sub Stream",
            "/ch2/0" to "Ch2 Main Stream",
        )),
        CameraRtspTemplate("Foscam", listOf(
            "/h264/ch1/main/av_stream" to "Main Stream",
            "/videoMain"               to "Video Main (alt)",
        )),
        CameraRtspTemplate(MANUAL, listOf(
            "/stream1"    to "Generic Stream 1",
            "/stream2"    to "Generic Sub-stream",
            "/live/main"  to "Live Main",
            "/live"       to "Live",
            "/video.h264" to "Video H.264",
        )),
    )

    fun forBrand(brand: String): CameraRtspTemplate =
        all.find { it.brand == brand } ?: all.last()  // fallback to Manual
}

// ── App Constants ─────────────────────────────────────────────────────────────
object AppConstants {
    const val APP_NAME = "Jenix Stream"
    const val APP_VERSION = "1.0.0"
    const val BUILD_NUMBER = 100
    const val DEVELOPER = "Manoj Jain"
    const val CONTACT_EMAIL = "manoj020218@gmail.com"
    const val PACKAGE_ID = "com.jenix.stream"
    const val NOTIFICATION_CHANNEL_ID = "jenix_stream_channel"
    const val NOTIFICATION_ID = 1001
    const val STREAM_NOTIFICATION_ID = 1002
}
