package com.jenix.stream.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jenix.stream.ui.theme.*

// ── Section Card ──────────────────────────────────────────────────────────────
@Composable
fun JCard(
    title: String,
    accentColor: Color = Cyan,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = BgSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                Box(modifier = Modifier
                    .size(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(accentColor))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.labelSmall,
                    color = accentColor, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

// ── Start Stream Button ───────────────────────────────────────────────────────
@Composable
fun StartStreamButton(streaming: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alpha"
    )
    val borderColor = if (streaming) Green.copy(alpha = pulseAlpha) else Green.copy(0.3f)
    val bgColor = if (streaming) GreenDim else Color(0xFF0D1A12)
    val textColor = if (streaming) Green else Green.copy(0.4f)

    Button(
        onClick = { if (!streaming) onClick() },
        enabled = !streaming,
        modifier = modifier.fillMaxWidth().height(72.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = bgColor,
            disabledContainerColor = Color(0xFF0D1A12)
        ),
        border = BorderStroke(2.dp, borderColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("▶", fontSize = 24.sp, color = if (!streaming) Green else Green.copy(0.3f))
            Text("START", fontSize = 13.sp, fontWeight = FontWeight.Black,
                letterSpacing = 3.sp, color = if (!streaming) Green else Green.copy(0.3f))
        }
    }
}

// ── Stop Stream Button ────────────────────────────────────────────────────────
@Composable
fun StopStreamButton(streaming: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "redpulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "redalpha"
    )
    val borderColor = if (streaming) Red.copy(alpha = pulseAlpha) else Red.copy(0.15f)
    val bgColor = if (streaming) RedDim else Color(0xFF100505)
    val textColor = if (streaming) Red else Red.copy(0.3f)

    Button(
        onClick = { if (streaming) onClick() },
        enabled = streaming,
        modifier = modifier.fillMaxWidth().height(72.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = bgColor,
            disabledContainerColor = Color(0xFF100505)
        ),
        border = BorderStroke(2.dp, borderColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⏹", fontSize = 24.sp, color = textColor)
            Text("STOP", fontSize = 13.sp, fontWeight = FontWeight.Black,
                letterSpacing = 3.sp, color = textColor)
        }
    }
}

// ── Stat Box ──────────────────────────────────────────────────────────────────
@Composable
fun StatBox(value: String, label: String, active: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = BgSurface2,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (active) Cyan.copy(0.3f) else BorderColor)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontFamily = FontFamily.Monospace, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, color = if (active) Cyan else TextDim)
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                color = TextDim, letterSpacing = 1.sp)
        }
    }
}

// ── Form Field ────────────────────────────────────────────────────────────────
@Composable
fun JTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String = "",
    hintColor: Color = TextDim,
    isPassword: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = TextDim, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan,
                unfocusedBorderColor = BorderColor,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Cyan,
                focusedContainerColor = BgSurface2,
                unfocusedContainerColor = BgSurface2
            ),
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary
            ),
            visualTransformation = if (isPassword)
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            singleLine = true,
            shape = RoundedCornerShape(6.dp)
        )
        if (hint.isNotBlank()) {
            Text(hint, style = MaterialTheme.typography.bodySmall,
                color = hintColor, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

// ── Dropdown Selector ─────────────────────────────────────────────────────────
@Composable
fun <T> JDropdown(
    value: T,
    options: List<Pair<T, String>>,
    label: String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == value }?.second ?: value.toString()

    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = TextDim, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                color = BgSurface2,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, BorderColor)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(selectedLabel, fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, color = TextPrimary)
                    Text("▾", color = TextDim, fontSize = 12.sp)
                }
            }
            DropdownMenu(
                expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.background(BgSurface)
            ) {
                options.forEach { (opt, lbl) ->
                    DropdownMenuItem(
                        text = { Text(lbl, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary) },
                        onClick = { onSelect(opt); expanded = false },
                        modifier = Modifier.background(if (opt == value) Cyan.copy(0.1f) else Color.Transparent)
                    )
                }
            }
        }
    }
}

// ── Toggle Row ────────────────────────────────────────────────────────────────
@Composable
fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Switch(
            checked = checked, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Green,
                checkedTrackColor = GreenDim,
                uncheckedThumbColor = TextDim,
                uncheckedTrackColor = BgSurface2
            )
        )
    }
}

// ── Live Badge ────────────────────────────────────────────────────────────────
@Composable
fun LiveBadge(isLive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "blinkalpha"
    )
    Surface(
        color = if (isLive) RedDim else BgSurface2,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (isLive) Red.copy(alpha) else BorderColor)
    ) {
        Text(
            if (isLive) "● LIVE" else "IDLE",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            color = if (isLive) Red.copy(alpha) else TextDim,
            letterSpacing = 1.sp
        )
    }
}

// ── Log Panel ─────────────────────────────────────────────────────────────────
@Composable
fun LogPanel(lines: List<String>, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(lines.size) { scrollState.animateScrollTo(scrollState.maxValue) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF050709),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(
            modifier = Modifier.padding(10.dp).verticalScroll(scrollState)
        ) {
            if (lines.isEmpty()) {
                Text("Waiting for activity...", fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, color = TextDim)
            }
            lines.forEach { line ->
                val color = when {
                    line.contains("[OK]") || line.contains("OK:") -> Green
                    line.contains("[ERROR]") || line.contains("ERROR:") -> Red
                    line.contains("[WARN]") || line.contains("WARN:") -> Amber
                    else -> Cyan.copy(0.7f)
                }
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = color,
                    lineHeight = 16.sp)
            }
        }
    }
}

// ── Day Chip ──────────────────────────────────────────────────────────────────
@Composable
fun DayChip(day: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (selected) Cyan.copy(0.12f) else BgSurface2,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, if (selected) Cyan else BorderColor)
    ) {
        Text(
            day.uppercase(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            color = if (selected) Cyan else TextDim
        )
    }
}
