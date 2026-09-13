package com.devicepilot.ai.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devicepilot.ai.agent.AgentStep
import com.devicepilot.ai.agent.ChatMessage
import com.devicepilot.ai.agent.Sender
import com.devicepilot.ai.agent.StepStatus
import com.devicepilot.ai.tools.Risk
import com.devicepilot.ai.ui.theme.*

@Composable
fun MessageBubble(message: ChatMessage, onConfirm: () -> Unit, onDeny: () -> Unit) {
    val isUser = message.sender == Sender.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (isUser) 16.dp else 4.dp, bottomEnd = if (isUser) 4.dp else 16.dp))
                .background(if (isUser) UserBubbleBg else BotBubbleBg)
                .border(1.dp, if (isUser) Color.Transparent else DarkSlateBorder.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            if (!isUser) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DevicePilot", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    if (message.isActive) { Spacer(Modifier.width(6.dp)); Pulse() }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (message.steps.isNotEmpty()) {
                message.steps.sortedBy { it.index }.forEach { StepCard(it) ; Spacer(Modifier.height(6.dp)) }
            }

            message.pendingConfirmation?.let { ConfirmCard(it, onConfirm, onDeny); Spacer(Modifier.height(6.dp)) }

            message.pendingQuestion?.let {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AccentIndigo.copy(alpha = 0.15f)).border(1.dp, AccentIndigo.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Text("Question", color = AccentIndigo, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(it, color = TextPrimary, fontSize = 14.sp)
                    Text("Reply in the input box below.", color = TextTertiary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
            }

            if (!message.thinking.isNullOrBlank() && message.isActive) {
                Text(
                    message.thinking.replace(Regex("<tool_call>|</tool_call>"), "").trim().takeLast(220),
                    color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 4
                )
            } else if (message.isActive && message.text.isBlank() && message.pendingConfirmation == null && message.pendingQuestion == null) {
                Text("Thinking…", color = TextTertiary, fontSize = 12.sp)
            }

            if (message.text.isNotBlank()) {
                if (message.steps.isNotEmpty()) Spacer(Modifier.height(2.dp))
                Text(message.text, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun Pulse() {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(7.dp).alpha(a).clip(RoundedCornerShape(50)).background(AccentCyan))
}

@Composable
fun StepCard(step: AgentStep) {
    var expanded by remember(step.index) { mutableStateOf(false) }
    val (color, label) = when (step.status) {
        StepStatus.THINKING -> TextTertiary to "…"
        StepStatus.AWAITING_CONFIRMATION -> StatusAmber to "WAITING"
        StepStatus.EXECUTING -> StatusAmber to "RUNNING"
        StepStatus.SUCCESS -> StatusGreen to "OK"
        StepStatus.FAILED -> StatusRose to "FAILED"
        StepStatus.DENIED -> TextTertiary to "DENIED"
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DarkSlateBackground)
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).clickable { expanded = !expanded }.padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${step.index + 1}", color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(18.dp))
            Icon(iconFor(step.toolName), null, tint = AccentCyan, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(step.summary, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            if (step.status == StepStatus.EXECUTING) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = StatusAmber)
            } else {
                Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        if (!step.thought.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(step.thought, color = TextSecondary, fontSize = 11.sp, maxLines = if (expanded) 10 else 2, modifier = Modifier.padding(start = 18.dp))
        }
        if (step.output.isNotBlank() && (expanded || step.status == StepStatus.FAILED)) {
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(DarkSlateSurfaceVariant.copy(alpha = 0.4f)).padding(8.dp)) {
                step.command?.let { Text("$ $it", color = TextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 2); Spacer(Modifier.height(3.dp)) }
                Text(if (expanded) step.output.take(4000) else step.output.take(300), color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        } else if (step.output.isNotBlank()) {
            Text(step.output.lineSequence().first().take(90), color = TextTertiary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 18.dp, top = 3.dp))
        }
        step.artifactPath?.let { path ->
            val bmp = remember(path) { runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull() }
            bmp?.let {
                Spacer(Modifier.height(6.dp))
                Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun ConfirmCard(step: AgentStep, onConfirm: () -> Unit, onDeny: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(StatusAmberBg.copy(alpha = 0.35f))
            .border(1.dp, StatusAmber.copy(alpha = 0.7f), RoundedCornerShape(10.dp)).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = StatusAmber, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Confirm action", color = StatusAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(step.summary, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(step.argsJson, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 4)
        if (!step.thought.isNullOrBlank()) Text(step.thought, color = TextTertiary, fontSize = 11.sp, maxLines = 3)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = StatusAmber, contentColor = DarkSlateBackground), modifier = Modifier.weight(1f)) { Text("Allow") }
            OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Deny", color = TextPrimary) }
        }
    }
}

private fun iconFor(tool: String) = when {
    tool == "get_screen" || tool == "screenshot" -> Icons.Default.Visibility
    tool.startsWith("tap") || tool == "long_press" -> Icons.Default.TouchApp
    tool == "type_text" -> Icons.Default.Keyboard
    tool == "swipe" -> Icons.Default.SwipeVertical
    tool == "press_key" -> Icons.AutoMirrored.Filled.KeyboardReturn
    tool == "open_app" || tool == "list_apps" || tool.contains("app") -> Icons.Default.Apps
    tool.startsWith("set_") -> Icons.Default.Tune
    tool == "shell" -> Icons.Default.Terminal
    tool == "wait" -> Icons.Default.HourglassEmpty
    tool == "get_device_state" || tool == "get_notifications" -> Icons.Default.Info
    else -> Icons.Default.Code
}
