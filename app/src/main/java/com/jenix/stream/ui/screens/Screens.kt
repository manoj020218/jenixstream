package com.jenix.stream.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import com.jenix.stream.data.model.*
import com.jenix.stream.onvif.OnvifDiscovery
import com.jenix.stream.ui.components.*
import com.jenix.stream.ui.theme.*
import com.jenix.stream.viewmodel.StreamViewModel

// ════════════════════════════════════════════════════════════════════════════
// DEVICE COMPATIBILITY CHECK
// ════════════════════════════════════════════════════════════════════════════
private data class DeviceCompat(
    val androidOk: Boolean,
    val ramOk: Boolean,
    val storageOk: Boolean,
    val networkOk: Boolean,
    val androidVersion: String,
    val ramLabel: String,
    val storageLabel: String,
    val networkLabel: String
) {
    val isCompatible get() = androidOk && ramOk && storageOk
    val failReasons get(): String {
        val r = mutableListOf<String>()
        if (!androidOk) r.add("Requires Android 8.0+ (yours: $androidVersion)")
        if (!ramOk)     r.add("Requires 2 GB RAM (yours: $ramLabel)")
        if (!storageOk) r.add("Requires 100 MB free storage (yours: $storageLabel)")
        return r.joinToString(". ")
    }
}

private fun checkDeviceCompat(context: Context): DeviceCompat {
    val androidOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    am.getMemoryInfo(memInfo)
    val totalRamMb = memInfo.totalMem / (1024 * 1024)
    val ramOk = totalRamMb >= 1800  // ~2 GB with tolerance
    val ramLabel = "%.1f GB".format(totalRamMb / 1024.0)

    val stat = StatFs(Environment.getDataDirectory().path)
    val freeMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
    val storageOk = freeMb >= 100
    val storageLabel = if (freeMb >= 1024) "%.1f GB free".format(freeMb / 1024.0) else "$freeMb MB free"

    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val networkOk: Boolean
    val networkLabel: String
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val hasCell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        networkOk = hasWifi || hasCell
        networkLabel = when { hasWifi -> "WiFi Connected"; hasCell -> "Mobile Data"; else -> "No Network" }
    } else {
        @Suppress("DEPRECATION")
        val ni = cm.activeNetworkInfo
        networkOk = ni?.isConnected == true
        networkLabel = if (networkOk) "Connected" else "No Network"
    }

    return DeviceCompat(androidOk, ramOk, storageOk, networkOk,
        androidVersion, ramLabel, storageLabel, networkLabel)
}

@Composable
private fun CompatRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (ok) "✓" else "✗", fontSize = 12.sp,
            color = if (ok) Green else Red, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp))
        Text(label, fontSize = 11.sp, color = TextSecondary,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Text(value, fontSize = 10.sp, color = if (ok) TextDim else Red,
            fontFamily = FontFamily.Monospace)
    }
}

// ════════════════════════════════════════════════════════════════════════════
// LOGIN SCREEN
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun LoginScreen(viewModel: StreamViewModel, onLoggedIn: () -> Unit) {
    var email by remember { mutableStateOf("") }
    val isValidEmail = android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var compatResult by remember { mutableStateOf<DeviceCompat?>(null) }
    val isCompatible = compatResult?.isCompatible ?: false

    // Auto-check on first load; show 10-second toast if device is not eligible
    LaunchedEffect(Unit) {
        val compat = checkDeviceCompat(context)
        compatResult = compat
        if (!compat.isCompatible) {
            val msg = "⚠ Device not supported: ${compat.failReasons}"
            repeat(3) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                delay(3500)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // Live pill
            Surface(
                color = Red.copy(alpha = 0.12f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Red.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(7.dp).background(Red, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("PROFESSIONAL LIVE STREAMING", fontSize = 9.sp, color = Red,
                        fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(18.dp))

            // Logo
            Text("JENIX", fontSize = 62.sp, fontWeight = FontWeight.Black,
                color = Cyan, letterSpacing = 8.sp)
            Text("STREAM", fontSize = 13.sp, fontWeight = FontWeight.Light,
                color = TextSecondary, letterSpacing = 16.sp)
            Spacer(Modifier.height(10.dp))
            Text("Turn Any Camera Into a Live Broadcast",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = TextDim, textAlign = TextAlign.Center)

            Spacer(Modifier.height(24.dp))

            // Use cases
            Surface(
                color = BgSurface2, shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("TRUSTED BY PROFESSIONALS",
                        fontSize = 9.sp, color = Cyan.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    val useCases = listOf(
                        "🛕" to "Temple & Religious Events",
                        "💒" to "Weddings & Celebrations",
                        "🎤" to "Rallies & Public Meetings",
                        "🎪" to "Event Management",
                        "📢" to "Demonstrations",
                        "🏛" to "Corporate Conferences"
                    )
                    useCases.chunked(2).forEach { pair ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            pair.forEach { (icon, label) ->
                                Row(modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(icon, fontSize = 14.sp)
                                    Spacer(Modifier.width(5.dp))
                                    Text(label, fontSize = 10.sp, color = TextSecondary,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Get Started card
            Surface(
                color = BgSurface, shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Get Started", fontWeight = FontWeight.Bold,
                        fontSize = 18.sp, color = TextPrimary)
                    Text("Enter your email to continue",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = TextDim, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))

                    JTextField(
                        value = email, onValueChange = { email = it },
                        label = "EMAIL ADDRESS",
                        hint = if (email.isNotEmpty() && !isValidEmail) "Enter a valid email"
                               else "No password required"
                    )

                    Spacer(Modifier.height(20.dp))

                    // Device requirements
                    Text("DEVICE REQUIREMENTS",
                        fontSize = 9.sp, color = TextDim,
                        fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
                    Spacer(Modifier.height(8.dp))

                    compatResult?.let { compat ->
                        CompatRow("Android 8.0+",    compat.androidVersion, compat.androidOk)
                        CompatRow("RAM  2 GB+",       compat.ramLabel,       compat.ramOk)
                        CompatRow("Storage 100 MB+",  compat.storageLabel,   compat.storageOk)
                        CompatRow("Network",          compat.networkLabel,   compat.networkOk)
                    } ?: Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Cyan,
                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }

                    Spacer(Modifier.height(16.dp))

                    val canEnter = isValidEmail && isCompatible
                    Button(
                        onClick = {
                            if (compatResult != null && !isCompatible) {
                                coroutineScope.launch {
                                    val msg = "⚠ Not supported: ${compatResult?.failReasons}"
                                    repeat(3) {
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        delay(3500)
                                    }
                                }
                            } else if (canEnter) {
                                viewModel.login(email.lowercase().trim())
                                onLoggedIn()
                            }
                        },
                        enabled = canEnter,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Cyan, contentColor = Color.Black,
                            disabledContainerColor = if (compatResult != null && !isCompatible)
                                RedDim else BgSurface2
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        val btnText = when {
                            compatResult == null  -> "CHECKING DEVICE..."
                            !isCompatible         -> "✗  DEVICE NOT SUPPORTED"
                            else                  -> "▶  ENTER APP"
                        }
                        Text(btnText, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                            color = when {
                                compatResult == null -> TextDim
                                !isCompatible        -> Red
                                canEnter             -> Color.Black
                                else                 -> TextDim
                            })
                    }

                    if (compatResult != null && !isCompatible) {
                        Spacer(Modifier.height(8.dp))
                        Text("⚠  This device does not meet the minimum requirements for live streaming.",
                            fontSize = 10.sp, color = Red, fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth())
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("🔒  Your data stays on this device only",
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextDim,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Jenix Stream v${AppConstants.APP_VERSION} · By ${AppConstants.DEVELOPER}",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextDim)
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SCHEDULE HELPER — computes "Today", "Tomorrow", or day name for next fire
// ════════════════════════════════════════════════════════════════════════════
private fun scheduleNextFireLabel(schedule: Schedule): String {
    val abbr = mapOf(
        1 to "sun", 2 to "mon", 3 to "tue", 4 to "wed", 5 to "thu", 6 to "fri", 7 to "sat"
    )
    val displayName = mapOf(
        "mon" to "Monday", "tue" to "Tuesday", "wed" to "Wednesday",
        "thu" to "Thursday", "fri" to "Friday", "sat" to "Saturday", "sun" to "Sunday"
    )
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, schedule.startHour)
        set(Calendar.MINUTE, schedule.startMinute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)

    val days = schedule.daysList
    if (days.isNotEmpty()) {
        for (i in 0..6) {
            if (days.contains(abbr[cal.get(Calendar.DAY_OF_WEEK)])) break
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    val todayMidnight = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val diffDays = ((cal.timeInMillis - todayMidnight.timeInMillis) / 86_400_000L).toInt()
    val dayKey = abbr[cal.get(Calendar.DAY_OF_WEEK)] ?: ""
    return when (diffDays) {
        0 -> "Today at ${schedule.startFormatted}"
        1 -> "Tomorrow (${displayName[dayKey] ?: dayKey}) at ${schedule.startFormatted}"
        else -> "${displayName[dayKey] ?: dayKey} at ${schedule.startFormatted}"
    }
}

// ════════════════════════════════════════════════════════════════════════════
// STREAM SCREEN
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun StreamScreen(viewModel: StreamViewModel) {
    val stats by viewModel.streamStats.collectAsState()
    val config by viewModel.streamConfig.collectAsState()
    val logs by viewModel.logLines.collectAsState()
    val probeResult by viewModel.probeResult.collectAsState()
    val isProbing by viewModel.isProbing.collectAsState()
    val scheduledByLabel by viewModel.scheduledByLabel.collectAsState()
    val nextSchedule by viewModel.nextSchedule.collectAsState()
    var localConfig by remember(config) { mutableStateOf(config) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // START / STOP buttons
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StartStreamButton(streaming = stats.isRunning,
                    onClick = { viewModel.saveConfig(localConfig); viewModel.startStream() },
                    modifier = Modifier.weight(1f))
                StopStreamButton(streaming = stats.isRunning,
                    onClick = { viewModel.stopStream() },
                    modifier = Modifier.weight(1f))
            }
        }

        // ── Schedule started banner (visible while streaming via schedule) ──
        if (stats.isRunning && scheduledByLabel.isNotBlank()) {
            item {
                Surface(
                    color = Color(0xFF0A1A0A),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Green.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⏰", fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
                        Column {
                            Text("STREAMING AS PER SCHEDULE",
                                fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                                color = Green.copy(alpha = 0.7f), letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.Bold)
                            Text("\"$scheduledByLabel\"",
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                color = Green, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ── Upcoming schedule banner (visible when idle with an active schedule) ──
        if (!stats.isRunning && nextSchedule != null) {
            item {
                val sched = nextSchedule!!
                val whenLabel = remember(sched) { scheduleNextFireLabel(sched) }
                Surface(
                    color = Cyan.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Cyan.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⏰", fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("NEXT SCHEDULED STREAM",
                                fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                                color = Cyan.copy(alpha = 0.7f), letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.Bold)
                            if (sched.label.isNotBlank()) {
                                Text("\"${sched.label}\"",
                                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                    color = Cyan, fontWeight = FontWeight.Bold)
                            }
                            Text("${sched.startFormatted} → ${sched.stopFormatted}  ·  ${sched.repeatPattern.uppercase()}",
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = TextSecondary)
                            Text(whenLabel,
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = Amber)
                        }
                    }
                }
            }
        }

        // Stats
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox(stats.durationFormatted, "DURATION", stats.isRunning, Modifier.weight(1f))
                StatBox("${stats.currentKbps.toInt()}", "KBPS", stats.isRunning, Modifier.weight(1f))
                StatBox("${stats.currentFps.toInt()}", "FPS", stats.isRunning, Modifier.weight(1f))
            }
        }

        // Codec info (when streaming)
        if (stats.isRunning && stats.videoCodec.isNotBlank()) {
            item {
                Surface(color = GreenDim, shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Green.copy(0.3f))) {
                    Row(modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        Text("📹 ${stats.videoCodec.uppercase()} ${stats.resolution}",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Green)
                        Text("🔊 ${stats.audioCodec.uppercase()}",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Green)
                    }
                }
            }
        }

        // RTSP source
        item {
            JCard("SOURCE — RTSP CAMERA") {
                JTextField(value = localConfig.rtspUrl,
                    onValueChange = { localConfig = localConfig.copy(rtspUrl = it) },
                    label = "RTSP URL",
                    hint = if (localConfig.rtspUrl.isNotBlank() && !localConfig.rtspUrl.startsWith("rtsp://"))
                        "Must start with rtsp://" else "rtsp://admin:pass@192.168.1.x:554/stream1")

                Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.saveConfig(localConfig)
                            viewModel.probeStream(localConfig.rtspUrl, localConfig.rtspTransport)
                        },
                        enabled = localConfig.rtspUrl.isNotBlank() && !isProbing,
                        border = BorderStroke(1.dp, if (!isProbing) Cyan else BorderColor),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(if (isProbing) "⏳ Probing..." else "🔍 PROBE",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (!isProbing) Cyan else TextDim)
                    }
                    OutlinedButton(
                        onClick = { viewModel.saveConfig(localConfig) },
                        border = BorderStroke(1.dp, BorderColor),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("SAVE", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary)
                    }
                }

                // Probe result
                probeResult?.let { pr ->
                    Spacer(Modifier.height(10.dp))
                    if (pr.success) {
                        Surface(color = BgSurface2, shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Green.copy(0.3f))) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("✓ Stream reachable", fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp, color = Green)
                                Spacer(Modifier.height(4.dp))
                                val codecLabel = when {
                                    pr.videoCodec.contains("h264", true) || pr.videoCodec.contains("avc", true) -> "H.264 (AVC)"
                                    pr.videoCodec.contains("hevc", true) || pr.videoCodec.contains("h265", true) || pr.videoCodec.contains("265", true) -> "H.265 (HEVC)"
                                    pr.videoCodec.contains("mjpeg", true) -> "MJPEG"
                                    pr.videoCodec.isBlank() -> "UNKNOWN"
                                    else -> "PRIVATE (${pr.videoCodec.uppercase()})"
                                }
                                val codecColor = when {
                                    pr.videoCodec.contains("h264", true) || pr.videoCodec.contains("avc", true) -> Green
                                    pr.videoCodec.contains("hevc", true) || pr.videoCodec.contains("h265", true) -> Amber
                                    else -> Red
                                }
                                Text("📹 $codecLabel  ${pr.width}×${pr.height} @ ${pr.fps.toInt()}fps",
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = codecColor)
                                Text("🔊 ${pr.audioCodec.uppercase()} ${pr.sampleRate}Hz",
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        // Compat grid
                        if (pr.compatIssues.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                pr.compatIssues.forEach { item ->
                                    val (bg, border, tc) = when (item.status) {
                                        CompatStatus.OK -> Triple(GreenDim, Green.copy(0.4f), Green)
                                        CompatStatus.WARN -> Triple(Color(0xFF1A0F00), Amber.copy(0.4f), Amber)
                                        CompatStatus.ERROR -> Triple(RedDim, Red.copy(0.4f), Red)
                                    }
                                    Surface(color = bg, shape = RoundedCornerShape(4.dp),
                                        border = BorderStroke(1.dp, border),
                                        modifier = Modifier.weight(1f)) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(item.label, fontFamily = FontFamily.Monospace,
                                                fontSize = 8.sp, color = TextDim, letterSpacing = 1.sp)
                                            Text(item.value, fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp, color = tc, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            // Suggestions
                            if (pr.suggestions.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Surface(color = Color(0xFF1A0F00), shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, Amber.copy(0.3f))) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                        Text("⚠ CAMERA SETTINGS TO FIX", fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp, color = Amber, letterSpacing = 1.sp)
                                        pr.suggestions.forEach { s ->
                                            Text("→ $s", fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp, color = TextSecondary,
                                                modifier = Modifier.padding(top = 4.dp))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Surface(color = RedDim, shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Red.copy(0.3f))) {
                            Text("✗ ${pr.errorMessage}", fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp, color = Red,
                                modifier = Modifier.fillMaxWidth().padding(12.dp))
                        }
                    }
                }
            }
        }

        // YouTube
        item {
            JCard("YOUTUBE LIVE", accentColor = Color(0xFFFF0000)) {
                ToggleRow("Enable YouTube", localConfig.ytEnabled) {
                    localConfig = localConfig.copy(ytEnabled = it)
                    viewModel.saveConfig(localConfig)
                }
                if (localConfig.ytEnabled) {
                    Spacer(Modifier.height(8.dp))
                    JTextField(value = localConfig.ytUrl,
                        onValueChange = { localConfig = localConfig.copy(ytUrl = it).also { c -> viewModel.saveConfig(c) } },
                        label = "RTMP URL")
                    Spacer(Modifier.height(8.dp))
                    JTextField(value = localConfig.ytKey, isPassword = true,
                        onValueChange = { localConfig = localConfig.copy(ytKey = it).also { c -> viewModel.saveConfig(c) } },
                        label = "STREAM KEY",
                        hint = "YouTube Studio → Go Live → Stream key")
                }
            }
        }

        // Facebook
        item {
            JCard("FACEBOOK LIVE (SIMULTANEOUS)", accentColor = Color(0xFF1877F2)) {
                ToggleRow("Stream to Facebook simultaneously", localConfig.fbEnabled) {
                    localConfig = localConfig.copy(fbEnabled = it)
                    viewModel.saveConfig(localConfig)
                }
                if (localConfig.fbEnabled) {
                    Spacer(Modifier.height(8.dp))
                    JTextField(value = localConfig.fbUrl,
                        onValueChange = { localConfig = localConfig.copy(fbUrl = it).also { c -> viewModel.saveConfig(c) } },
                        label = "FACEBOOK STREAM URL",
                        hint = "Facebook Live Producer → Use Stream Key → Copy URL")
                }
            }
        }

        // Log
        item {
            JCard("STREAM LOG", accentColor = TextSecondary) {
                LogPanel(lines = logs, modifier = Modifier.height(200.dp))
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.clearLogs() },
                        border = BorderStroke(1.dp, BorderColor),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("CLEAR", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextDim)
                    }
                    Button(
                        onClick = { viewModel.shareStreamLog() },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan.copy(alpha = 0.15f)),
                        border = BorderStroke(1.dp, Cyan.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("📤 SHARE LOG", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Cyan)
                    }
                }
                Text(
                    "Log includes user & device info for diagnosis",
                    fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                    color = TextDim, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// CAMERA SCREEN
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun CameraScreen(viewModel: StreamViewModel, onNavigateToStream: () -> Unit = {}) {
    val cameras by viewModel.cameras.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanMessage by viewModel.scanMessage.collectAsState()
    val config by viewModel.streamConfig.collectAsState()
    var selected by remember { mutableStateOf<Camera?>(null) }
    var manualIp by remember { mutableStateOf("") }
    var camUser by remember { mutableStateOf("admin") }
    var camPass by remember { mutableStateOf("") }
    var camPort by remember { mutableStateOf("554") }
    var selectedBrand by remember { mutableStateOf(CameraTemplates.MANUAL) }
    var camPath by remember { mutableStateOf(CameraTemplates.forBrand(CameraTemplates.MANUAL).paths.first().first) }
    val listState = rememberLazyListState()

    fun buildUrl(): String {
        val ip = selected?.ip ?: manualIp
        if (ip.isBlank()) return ""
        val creds = when {
            camPass.isNotBlank() -> "$camUser:$camPass@"
            camUser.isNotBlank() -> "$camUser@"
            else -> ""
        }
        return "rtsp://$creds$ip:$camPort$camPath"
    }

    fun applyBrand(brand: String) {
        selectedBrand = brand
        val t = CameraTemplates.forBrand(brand)
        camPath = t.paths.first().first
        camPort = t.defaultPort
    }

    // Auto-scroll to BUILD RTSP URL card when camera is selected.
    // Layout: 0=discovery, [1=header, 2..N+1=cameras], last=builder
    LaunchedEffect(selected) {
        if (selected != null && cameras.isNotEmpty()) {
            listState.animateScrollToItem(cameras.size + 2)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── ONVIF Discovery ──────────────────────────────────────────────────
        item {
            JCard("ONVIF CAMERA DISCOVERY") {
                Text("Scans WiFi network using ONVIF WS-Discovery + TCP port scan.",
                    style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Text("Camera must be on the same WiFi network.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))

                if (isScanning) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        color = Cyan, trackColor = BgSurface2
                    )
                }

                Button(
                    onClick = { viewModel.startCameraScan() },
                    enabled = !isScanning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(if (isScanning) "⏳ SCANNING..." else "📡 SCAN NETWORK FOR CAMERAS",
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }

                if (scanMessage.isNotBlank()) {
                    Text(scanMessage, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = if (scanMessage.contains("found")) Green else Amber,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        // ── Found devices ────────────────────────────────────────────────────
        if (cameras.isNotEmpty()) {
            item {
                Text("FOUND DEVICES", fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = TextDim, letterSpacing = 2.sp)
            }
            items(cameras) { cam ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        selected = cam
                        manualIp = cam.ip
                        // Auto-match brand if the camera reports a recognisable brand name
                        val matched = CameraTemplates.all.find { t ->
                            t.brand != CameraTemplates.MANUAL &&
                            (cam.brand.contains(t.brand.substringBefore(" "), ignoreCase = true) ||
                             t.brand.contains(cam.brand.substringBefore(" "), ignoreCase = true))
                        }
                        if (matched != null && cam.brand.isNotBlank()) applyBrand(matched.brand)
                    },
                    color = if (selected?.ip == cam.ip) Cyan.copy(0.06f) else BgSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (selected?.ip == cam.ip) Cyan else BorderColor)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("📷 ${cam.ip}", fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp, color = Cyan, fontWeight = FontWeight.Bold)
                            Text("${cam.name} · Port ${cam.port}", fontSize = 11.sp, color = TextDim)
                            if (cam.brand.isNotBlank())
                                Text(cam.brand, fontSize = 10.sp, color = Amber)
                        }
                        Surface(color = Cyan.copy(0.1f), shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Cyan.copy(0.4f))) {
                            Text("ONVIF",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Cyan)
                        }
                    }
                }
            }
        }

        // ── RTSP URL Builder (always visible) ────────────────────────────────
        item {
            JCard("BUILD RTSP URL") {

                // If a camera was selected via ONVIF, show its details
                if (selected != null) {
                    Surface(color = Cyan.copy(0.06f), shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Cyan.copy(0.3f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Text("📷 ${selected!!.name} — ${selected!!.ip}",
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Cyan,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }

                // Camera IP + RTSP Port
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it; selected = null },
                        label = "CAMERA IP",
                        hint = "192.168.1.x",
                        modifier = Modifier.weight(1f)
                    )
                    JTextField(
                        value = camPort,
                        onValueChange = { camPort = it },
                        label = "PORT",
                        modifier = Modifier.weight(0.38f)
                    )
                }
                Spacer(Modifier.height(10.dp))

                // Brand selector
                val brandOptions = CameraTemplates.all.map { it.brand to it.brand }
                JDropdown(
                    value = selectedBrand,
                    options = brandOptions,
                    label = "CAMERA BRAND",
                    onSelect = { applyBrand(it) }
                )
                Spacer(Modifier.height(8.dp))

                // Stream path (brand-specific options)
                val pathOptions = CameraTemplates.forBrand(selectedBrand).paths
                JDropdown(
                    value = camPath,
                    options = pathOptions,
                    label = "STREAM PATH",
                    onSelect = { camPath = it }
                )
                Spacer(Modifier.height(10.dp))

                // Credentials
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JTextField(value = camUser, onValueChange = { camUser = it },
                        label = "USERNAME", modifier = Modifier.weight(1f))
                    JTextField(value = camPass, onValueChange = { camPass = it },
                        label = "PASSWORD", isPassword = true, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))

                // Built URL preview
                val builtUrl = buildUrl()
                Text("BUILT URL", style = MaterialTheme.typography.labelSmall,
                    color = TextDim, modifier = Modifier.padding(bottom = 4.dp))
                Surface(
                    color = BgSurface2, shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, if (builtUrl.isNotBlank()) Green.copy(0.4f) else BorderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = builtUrl.ifBlank { "rtsp://username:password@192.168.1.x:554/stream1" },
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = if (builtUrl.isNotBlank()) Green else TextDim,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                if (builtUrl.isNotBlank()) {
                    val clipboard = LocalClipboardManager.current
                    Row(modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                viewModel.saveConfig(config.copy(rtspUrl = builtUrl))
                                onNavigateToStream()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("✓ USE URL", fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(builtUrl)) },
                            border = BorderStroke(1.dp, BorderColor),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("📋 COPY", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextDim) }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SCHEDULE SCREEN
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun ScheduleScreen(viewModel: StreamViewModel, onScheduleAdded: () -> Unit = {}) {
    val schedules by viewModel.schedules.collectAsState()
    var scheduleName by remember { mutableStateOf("") }
    var startHour by remember { mutableStateOf(6) }
    var startMin by remember { mutableStateOf(0) }
    var stopHour by remember { mutableStateOf(8) }
    var stopMin by remember { mutableStateOf(0) }
    val allDays = listOf("mon","tue","wed","thu","fri","sat","sun")
    var selectedDays by remember { mutableStateOf(allDays.toSet()) }
    var repeatPattern by remember { mutableStateOf("everyday") }
    var duplicateError by remember { mutableStateOf("") }
    var highlightedId by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Clear the highlight border after 3 seconds
    LaunchedEffect(highlightedId) {
        if (highlightedId != -1) {
            delay(3000)
            highlightedId = -1
        }
    }

    fun findDuplicate(): Schedule? = schedules.firstOrNull { s ->
        s.startHour == startHour && s.startMinute == startMin &&
        s.stopHour == stopHour && s.stopMinute == stopMin &&
        s.days.split(",").filter { it.isNotBlank() }.toSet() == selectedDays
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            JCard("ADD SCHEDULE") {
                JTextField(
                    value = scheduleName,
                    onValueChange = { scheduleName = it },
                    label = "SCHEDULE NAME",
                    hint = "e.g. Morning Puja, Wedding Live, Sunday Rally"
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("START TIME", style = MaterialTheme.typography.labelSmall,
                            color = TextDim, modifier = Modifier.padding(bottom = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            JDropdown(startHour, (0..23).map { it to "%02d".format(it) },
                                "", { startHour = it }, Modifier.weight(1f))
                            JDropdown(startMin, listOf(0,15,30,45).map { it to "%02d".format(it) },
                                "", { startMin = it }, Modifier.weight(1f))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("STOP TIME", style = MaterialTheme.typography.labelSmall,
                            color = TextDim, modifier = Modifier.padding(bottom = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            JDropdown(stopHour, (0..23).map { it to "%02d".format(it) },
                                "", { stopHour = it }, Modifier.weight(1f))
                            JDropdown(stopMin, listOf(0,15,30,45).map { it to "%02d".format(it) },
                                "", { stopMin = it }, Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Text("REPEAT", style = MaterialTheme.typography.labelSmall,
                    color = TextDim, modifier = Modifier.padding(bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("everyday","weekdays","weekends","once").forEach { p ->
                        Surface(
                            modifier = Modifier.clickable {
                                repeatPattern = p
                                selectedDays = when(p) {
                                    "everyday" -> allDays.toSet()
                                    "weekdays" -> setOf("mon","tue","wed","thu","fri")
                                    "weekends" -> setOf("sat","sun")
                                    else -> emptySet()
                                }
                            },
                            color = if (repeatPattern==p) Cyan.copy(0.12f) else BgSurface2,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, if (repeatPattern==p) Cyan else BorderColor)
                        ) {
                            Text(p.uppercase(), modifier = Modifier.padding(horizontal=8.dp, vertical=5.dp),
                                fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                                color = if (repeatPattern==p) Cyan else TextDim)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                Text("DAYS", style = MaterialTheme.typography.labelSmall,
                    color = TextDim, modifier = Modifier.padding(bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    allDays.forEach { d ->
                        DayChip(d, selectedDays.contains(d)) {
                            selectedDays = if (selectedDays.contains(d))
                                selectedDays - d else selectedDays + d
                            repeatPattern = "custom"
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        val dup = findDuplicate()
                        if (dup != null) {
                            // Duplicate found — show error and scroll to it
                            duplicateError = "Schedule already exists for this time slot!"
                            highlightedId = dup.id
                            val idx = schedules.indexOfFirst { it.id == dup.id }
                            if (idx >= 0) {
                                coroutineScope.launch {
                                    // index 0=ADD card, 1=ACTIVE SCHEDULES header, 2+=schedule items
                                    listState.animateScrollToItem(idx + 2)
                                }
                            }
                        } else {
                            viewModel.addSchedule(Schedule(
                                startHour=startHour, startMinute=startMin,
                                stopHour=stopHour, stopMinute=stopMin,
                                days=selectedDays.joinToString(","),
                                repeatPattern=repeatPattern,
                                label=scheduleName.trim()
                            ))
                            scheduleName = ""
                            duplicateError = ""
                            onScheduleAdded()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black),
                    shape = RoundedCornerShape(6.dp)
                ) { Text("+ ADD SCHEDULE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }

                // Duplicate error banner
                if (duplicateError.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = RedDim, shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Red.copy(0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("✗  $duplicateError  →  Scroll down to see it highlighted",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Red,
                            modifier = Modifier.padding(10.dp))
                    }
                }
            }
        }

        item {
            Text("ACTIVE SCHEDULES", fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                color = TextDim, letterSpacing = 2.sp)
        }

        if (schedules.isEmpty()) {
            item {
                Surface(color = BgSurface, shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.fillMaxWidth()) {
                    Text("No schedules yet. Add one above.",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextDim,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                }
            }
        }

        items(schedules, key = { it.id }) { schedule ->
            val isHighlighted = schedule.id == highlightedId
            Surface(
                color = if (isHighlighted) RedDim else BgSurface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    if (isHighlighted) 2.dp else 1.dp,
                    when {
                        isHighlighted          -> Red
                        schedule.enabled       -> Cyan.copy(0.2f)
                        else                   -> BorderColor
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Schedule name label (if set)
                    if (schedule.label.isNotBlank()) {
                        Text(schedule.label, fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp, color = if (schedule.enabled) Amber else TextDim,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Row(horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("⏰ ${schedule.startFormatted} → ${schedule.stopFormatted}",
                            fontFamily = FontFamily.Monospace, fontSize = 18.sp,
                            color = if (schedule.enabled) Cyan else TextDim,
                            fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = schedule.enabled,
                                onCheckedChange = { viewModel.toggleSchedule(schedule, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Green, checkedTrackColor = GreenDim,
                                    uncheckedThumbColor = TextDim, uncheckedTrackColor = BgSurface2))
                            IconButton(onClick = { viewModel.deleteSchedule(schedule) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete",
                                    tint = Red.copy(0.7f))
                            }
                        }
                    }
                    Text(schedule.repeatPattern.uppercase(),
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextDim,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        allDays.forEach { d ->
                            DayChip(d, schedule.daysList.contains(d)) {}
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SETTINGS SCREEN
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun SettingsScreen(viewModel: StreamViewModel) {
    val config by viewModel.streamConfig.collectAsState()
    var local by remember(config) { mutableStateOf(config) }
    val clipboard = LocalClipboardManager.current

    val ffmpegCmd = buildString {
        append("ffmpeg -rtsp_transport ${local.rtspTransport} \\\n")
        append("  -i '[RTSP_URL]' \\\n")
        append("  -fflags +nobuffer -flags low_delay \\\n")
        if (local.vcodec == "copy") append("  -c:v copy \\\n")
        else append("  -c:v ${local.vcodec} -preset ${local.preset} -b:v ${local.bitrate} \\\n")
        if (local.acodec == "copy") append("  -c:a copy \\\n")
        else append("  -c:a aac -b:a 128k -ar 44100 \\\n")
        append("  -f flv '[RTMP_URL]'")
    }

    fun save() = viewModel.saveConfig(local)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            JCard("ENCODING — LOW MEMORY OPTIMIZED") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JDropdown(local.vcodec, listOf(
                        "copy" to "Copy (zero CPU) ★",
                        "libx264" to "H.264 libx264",
                        "h264_mediacodec" to "H.264 HW (Android)"
                    ), "VIDEO CODEC", { local = local.copy(vcodec = it); save() },
                        Modifier.weight(1f))
                    JDropdown(local.acodec, listOf(
                        "copy" to "Copy audio",
                        "aac" to "AAC ★ (YouTube)"
                    ), "AUDIO CODEC", { local = local.copy(acodec = it); save() },
                        Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JDropdown(local.bitrate, listOf(
                        "500k" to "500 Kbps", "1000k" to "1000 Kbps",
                        "2500k" to "2500 Kbps", "4500k" to "4500 Kbps (HD)"
                    ), "TARGET BITRATE", { local = local.copy(bitrate = it); save() },
                        Modifier.weight(1f))
                    JDropdown(local.rtspTransport, listOf(
                        "tcp" to "TCP (stable)", "udp" to "UDP (fast)"
                    ), "RTSP TRANSPORT", { local = local.copy(rtspTransport = it); save() },
                        Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                JDropdown(local.resolution, listOf(
                    "source" to "Source (no scale)",
                    "1920:1080" to "1920×1080 (1080p)",
                    "1280:720" to "1280×720 (720p)",
                    "854:480" to "854×480 (480p)"
                ), "OUTPUT RESOLUTION", { local = local.copy(resolution = it); save() })
            }
        }

        item {
            JCard("FFMPEG COMMAND PREVIEW", accentColor = TextSecondary) {
                Surface(color = Color(0xFF050709), shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, BorderColor), modifier = Modifier.fillMaxWidth()) {
                    Text(ffmpegCmd, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        color = Green, modifier = Modifier.padding(10.dp))
                }
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(ffmpegCmd)) },
                    modifier = Modifier.padding(top = 8.dp),
                    border = BorderStroke(1.dp, BorderColor),
                    shape = RoundedCornerShape(6.dp)
                ) { Text("📋 COPY COMMAND", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextDim) }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// PROFILE SCREEN
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun ProfileScreen(viewModel: StreamViewModel, onLogout: () -> Unit) {
    val user by viewModel.userProfile.collectAsState()
    var name by remember(user) { mutableStateOf(user?.name ?: "") }
    var mobile by remember(user) { mutableStateOf(user?.mobile ?: "") }
    var city by remember(user) { mutableStateOf(user?.city ?: "") }
    var saved by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Import backup: file picker → read JSON → apply settings
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val json = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()
            if (json != null) viewModel.importSettingsJson(json)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Avatar
        item {
            Surface(color = BgSurface, shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    val initials = name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }
                        .take(2).joinToString("").ifBlank { "?" }
                    Box(modifier = Modifier.size(80.dp).clip(CircleShape)
                        .background(GreenDim).border(2.dp, Green, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text(initials, fontSize = 32.sp, fontWeight = FontWeight.Black, color = Green)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(name.ifBlank { "Set your name" }, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(user?.email ?: "", fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp, color = TextDim, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // Profile form — collapses to a compact card after saving
        item {
            if (saved) {
                // Compact saved view
                Surface(
                    color = BgSurface, shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Green.copy(0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val initials = name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }
                            .take(2).joinToString("").ifBlank { "?" }
                        Box(
                            modifier = Modifier.size(50.dp).clip(CircleShape)
                                .background(GreenDim).border(2.dp, Green, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(initials, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Green)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name.ifBlank { "—" }, fontSize = 17.sp,
                                fontWeight = FontWeight.Bold, color = TextPrimary)
                            if (mobile.isNotBlank())
                                Text(mobile, fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp, color = TextDim)
                            if (city.isNotBlank())
                                Text(city, fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp, color = TextDim)
                        }
                        OutlinedButton(
                            onClick = { saved = false },
                            border = BorderStroke(1.dp, Cyan.copy(0.6f)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("EDIT", fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp, color = Cyan)
                        }
                    }
                }
            } else {
                JCard("MY DETAILS") {
                    JTextField(value = name, onValueChange = { name = it }, label = "FULL NAME",
                        hint = "Your name for display")
                    Spacer(Modifier.height(8.dp))
                    JTextField(value = mobile, onValueChange = { mobile = it }, label = "MOBILE NUMBER",
                        hint = "+91 XXXXX XXXXX")
                    Spacer(Modifier.height(8.dp))
                    JTextField(value = city, onValueChange = { city = it }, label = "CITY")
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            viewModel.updateProfile(name, mobile, city)
                            saved = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black),
                        shape = RoundedCornerShape(6.dp)
                    ) { Text("SAVE PROFILE", fontWeight = FontWeight.Bold) }
                    Text("All data stays on this device. Nothing is transmitted.",
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextDim,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        // Share / Import settings
        item {
            JCard("BACKUP & RESTORE") {
                Text("Share your stream settings with another device, or import a backup from someone else.",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary,
                    modifier = Modifier.padding(bottom = 14.dp))

                // Share button
                Button(
                    onClick = {
                        val json = viewModel.exportSettingsJson()
                        val file = File(context.cacheDir, "jenixstream_backup.json")
                        file.writeText(json)
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.provider", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Jenix Stream — Settings Backup")
                            putExtra(Intent.EXTRA_TEXT,
                                "Here are my Jenix Stream settings. Open with Jenix Stream app to apply instantly.")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Settings via..."))
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("📤  SHARE SETTINGS", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }

                Spacer(Modifier.height(8.dp))

                // Import button
                OutlinedButton(
                    onClick = { importLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    border = BorderStroke(1.dp, Green),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("📥  IMPORT BACKUP", fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp, color = Green, letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold)
                }

                Text("Shared file: jenixstream_backup.json · Import applies all settings instantly",
                    fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextDim,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }

        // App info
        item {
            JCard("APP INFORMATION", accentColor = TextDim) {
                listOf(
                    "App" to AppConstants.APP_NAME,
                    "Version" to "v${AppConstants.APP_VERSION} (Build ${AppConstants.BUILD_NUMBER})",
                    "Developer" to AppConstants.DEVELOPER,
                    "Contact" to AppConstants.CONTACT_EMAIL,
                    "Package" to AppConstants.PACKAGE_ID
                ).forEach { (k, v) ->
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Text("$k: ", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextDim)
                        Text(v, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Cyan)
                    }
                }
            }
        }

        // Sign out
        item {
            OutlinedButton(
                onClick = { viewModel.logout(); onLogout() },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Red.copy(0.5f)),
                shape = RoundedCornerShape(8.dp)
            ) { Text("SIGN OUT", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = Red, letterSpacing = 2.sp) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
