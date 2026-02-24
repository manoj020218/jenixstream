package com.jenix.stream

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jenix.stream.ui.screens.*
import com.jenix.stream.ui.theme.*
import com.jenix.stream.viewmodel.StreamViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: StreamViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled gracefully in features */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request permissions
        requestPermissions()

        setContent {
            JenixStreamTheme {
                JenixApp(viewModel)
            }
        }
    }

    private fun requestPermissions() {
        val needed = mutableListOf(
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }
}

// ════════════════════════════════════════════════════════════════════════════
// ROOT APP COMPOSABLE
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun JenixApp(viewModel: StreamViewModel) {
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val streamStats by viewModel.streamStats.collectAsState()
    val snack by viewModel.snackMessage.collectAsState()
    var currentTab by remember { mutableStateOf("stream") }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snack) {
        snack?.let { snackbarHostState.showSnackbar(it); viewModel.clearSnack() }
    }

    if (!isLoggedIn) {
        LoginScreen(viewModel) { /* isLoggedIn flow will update */ }
        return
    }

    Scaffold(
        containerColor = BgDeep,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(color = BgSurface, border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("JENIX", fontWeight = FontWeight.Black, fontSize = 22.sp,
                            color = Cyan, letterSpacing = 4.sp)
                        Text(" · ${tabTitle(currentTab)}", fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp, color = TextDim, letterSpacing = 2.sp)
                    }
                    LiveBadge(isLive = streamStats.isRunning)
                }
            }
        },
        bottomBar = {
            Surface(color = BgSurface, border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth()) {
                NavigationBar(containerColor = Color.Transparent,
                    modifier = Modifier.navigationBarsPadding()) {
                    listOf(
                        Triple("stream", "▶", "STREAM"),
                        Triple("camera", "📡", "CAMERA"),
                        Triple("schedule", "⏰", "SCHED"),
                        Triple("settings", "⚙", "SETTINGS"),
                        Triple("profile", "👤", "PROFILE")
                    ).forEach { (tab, icon, label) ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = {
                                Text(icon, fontSize = if (currentTab==tab) 22.sp else 18.sp,
                                    color = if (currentTab==tab) Cyan else TextDim)
                            },
                            label = {
                                Text(label, fontFamily = FontFamily.Monospace, fontSize = 8.sp,
                                    color = if (currentTab==tab) Cyan else TextDim)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Cyan.copy(0.08f)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            AnimatedContent(targetState = currentTab, transitionSpec = {
                fadeIn() togetherWith fadeOut()
            }, label = "tab") { tab ->
                when (tab) {
                    "stream" -> StreamScreen(viewModel)
                    "camera" -> CameraScreen(viewModel, onNavigateToStream = { currentTab = "stream" })
                    "schedule" -> ScheduleScreen(viewModel, onScheduleAdded = { currentTab = "stream" })
                    "settings" -> SettingsScreen(viewModel)
                    "profile" -> ProfileScreen(viewModel) { currentTab = "stream" }
                }
            }
        }
    }
}

private fun tabTitle(tab: String) = mapOf(
    "stream" to "STREAM CONTROL",
    "camera" to "CAMERA DISCOVERY",
    "schedule" to "SCHEDULER",
    "settings" to "ENCODING SETTINGS",
    "profile" to "MY PROFILE"
)[tab] ?: ""

@Composable
fun LiveBadge(isLive: Boolean) {
    com.jenix.stream.ui.components.LiveBadge(isLive)
}
