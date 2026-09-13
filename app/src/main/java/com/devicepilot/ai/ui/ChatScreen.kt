package com.devicepilot.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devicepilot.ai.llm.ModelState
import com.devicepilot.ai.shizuku.ShizukuState
import com.devicepilot.ai.ui.theme.AccentCyan
import com.devicepilot.ai.ui.theme.AccentIndigo
import com.devicepilot.ai.ui.theme.BotBubbleBg
import com.devicepilot.ai.ui.theme.DarkSlateBackground
import com.devicepilot.ai.ui.theme.DarkSlateBorder
import com.devicepilot.ai.ui.theme.DarkSlateSurface
import com.devicepilot.ai.ui.theme.DarkSlateSurfaceVariant
import com.devicepilot.ai.ui.theme.StatusAmber
import com.devicepilot.ai.ui.theme.StatusAmberBg
import com.devicepilot.ai.ui.theme.StatusGreen
import com.devicepilot.ai.ui.theme.StatusGreenBg
import com.devicepilot.ai.ui.theme.StatusRose
import com.devicepilot.ai.ui.theme.StatusRoseBg
import com.devicepilot.ai.ui.theme.TextPrimary
import com.devicepilot.ai.ui.theme.TextSecondary
import com.devicepilot.ai.ui.theme.TextTertiary
import com.devicepilot.ai.ui.theme.UserBubbleBg

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsState()
    val shizukuState by viewModel.shizukuState.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll when messages change or update
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .background(DarkSlateBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        color = DarkSlateBackground
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top App Bar & Status Badges
            TopStatusHeader(
                shizukuState = shizukuState,
                modelState = modelState,
                onRequestShizuku = { viewModel.requestShizukuPermission() },
                onRefreshShizuku = { viewModel.refreshShizukuStatus() },
                onClearChat = { viewModel.clearChat() }
            )

            // Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatMessageItem(message = message)
                }
            }

            // Quick suggestion chips
            QuickActionChips(
                onSelectSuggestion = { suggestion ->
                    viewModel.sendMessage(suggestion)
                }
            )

            // Input Row
            MessageInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                },
                isGenerating = isGenerating
            )
        }
    }
}

@Composable
fun TopStatusHeader(
    shizukuState: ShizukuState,
    modelState: ModelState,
    onRequestShizuku: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onClearChat: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlateSurface)
            .border(width = 1.dp, color = DarkSlateBorder)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentCyan.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Logo",
                        tint = AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "DevicePilot AI",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Autonomous On-Device System Assistant",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Row {
                IconButton(onClick = onRefreshShizuku, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Status",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onClearChat, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear Chat",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Floating Badges Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Shizuku Badge
            when (shizukuState) {
                is ShizukuState.Connected -> {
                    StatusChip(
                        title = "Shizuku: Active (ADB)",
                        icon = Icons.Default.CheckCircle,
                        textColor = StatusGreen,
                        bgColor = StatusGreenBg,
                        borderColor = StatusGreen.copy(alpha = 0.5f)
                    )
                }
                is ShizukuState.Unauthorized -> {
                    StatusChip(
                        title = "Shizuku: Tap to Authorize",
                        icon = Icons.Default.Security,
                        textColor = StatusAmber,
                        bgColor = StatusAmberBg,
                        borderColor = StatusAmber.copy(alpha = 0.5f),
                        onClick = onRequestShizuku
                    )
                }
                is ShizukuState.NotRunning -> {
                    StatusChip(
                        title = "Shizuku: Not Running",
                        icon = Icons.Default.Error,
                        textColor = StatusRose,
                        bgColor = StatusRoseBg,
                        borderColor = StatusRose.copy(alpha = 0.5f),
                        onClick = onRefreshShizuku
                    )
                }
                is ShizukuState.Unsupported -> {
                    StatusChip(
                        title = "Shizuku: Unsupported",
                        icon = Icons.Default.Error,
                        textColor = StatusRose,
                        bgColor = StatusRoseBg,
                        borderColor = StatusRose.copy(alpha = 0.5f)
                    )
                }
            }

            // Model Badge
            when (modelState) {
                is ModelState.Loaded -> {
                    val sizeMb = modelState.memoryBytes / (1024 * 1024)
                    StatusChip(
                        title = "Qwen2.5-0.5B (${sizeMb}MB RAM)",
                        icon = Icons.Default.Memory,
                        textColor = AccentCyan,
                        bgColor = DarkSlateSurfaceVariant,
                        borderColor = AccentCyan.copy(alpha = 0.5f)
                    )
                }
                is ModelState.FallbackMode -> {
                    StatusChip(
                        title = "AI Engine: Ready (Simulated)",
                        icon = Icons.Default.Memory,
                        textColor = AccentIndigo,
                        bgColor = DarkSlateSurfaceVariant,
                        borderColor = AccentIndigo.copy(alpha = 0.5f)
                    )
                }
                is ModelState.Loading -> {
                    StatusChip(
                        title = "Model Loading...",
                        icon = Icons.Default.Refresh,
                        textColor = StatusAmber,
                        bgColor = StatusAmberBg,
                        borderColor = StatusAmber.copy(alpha = 0.5f)
                    )
                }
                is ModelState.Unloaded -> {
                    StatusChip(
                        title = "Model Unloaded",
                        icon = Icons.Default.Memory,
                        textColor = TextTertiary,
                        bgColor = DarkSlateSurfaceVariant,
                        borderColor = DarkSlateBorder
                    )
                }
            }
        }
    }
}

@Composable
fun StatusChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    textColor: Color,
    bgColor: Color,
    borderColor: Color,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor.copy(alpha = 0.35f))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage) {
    val isUser = message.sender == MessageSender.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.82f else 0.95f)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 2.dp,
                        bottomEnd = if (isUser) 2.dp else 16.dp
                    )
                )
                .background(if (isUser) UserBubbleBg else BotBubbleBg)
                .border(
                    width = 1.dp,
                    color = if (isUser) UserBubbleBg.copy(alpha = 0.7f) else DarkSlateBorder,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 2.dp,
                        bottomEnd = if (isUser) 2.dp else 16.dp
                    )
                )
                .padding(12.dp)
        ) {
            Column {
                if (message.text.isNotEmpty()) {
                    Text(
                        text = message.text,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }

                // Streaming cursor animation
                if (message.isStreaming) {
                    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.1f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(450, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "cursorAlpha"
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .size(width = 8.dp, height = 14.dp)
                            .alpha(alpha)
                            .background(AccentCyan)
                    )
                }

                // Embedded Tool Execution Card
                message.toolCall?.let { tool ->
                    Spacer(modifier = Modifier.height(8.dp))
                    ToolExecutionCard(toolCall = tool)
                }
            }
        }
    }
}

@Composable
fun ToolExecutionCard(toolCall: ToolCallInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSlateBackground)
            .border(1.dp, if (toolCall.isSuccess) StatusGreen.copy(alpha = 0.6f) else StatusRose.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        // Tool header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = toolCall.toolName,
                    color = AccentCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Execution status badge
            if (toolCall.isExecuting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = StatusAmber
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "EXECUTING",
                        color = StatusAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (toolCall.isSuccess) {
                Text(
                    text = "SUCCESS",
                    color = StatusGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(StatusGreenBg.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            } else {
                Text(
                    text = "FAILED",
                    color = StatusRose,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(StatusRoseBg.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Command details
        Text(
            text = "Command: ${toolCall.command}",
            color = TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        // Args
        if (toolCall.args.isNotEmpty()) {
            Text(
                text = "Args: ${toolCall.args}",
                color = TextTertiary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Stdout / output
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(DarkSlateSurfaceVariant.copy(alpha = 0.5f))
                .padding(8.dp)
        ) {
            Text(
                text = toolCall.output,
                color = TextPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun QuickActionChips(
    onSelectSuggestion: (String) -> Unit
) {
    val suggestions = listOf(
        "Set brightness to 180",
        "Turn off Wi-Fi",
        "Set media volume to 8",
        "List installed 3rd party apps",
        "Set brightness to max",
        "Turn on Wi-Fi"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        suggestions.forEach { suggestion ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkSlateSurface)
                    .border(1.dp, DarkSlateBorder, RoundedCornerShape(14.dp))
                    .clickable { onSelectSuggestion(suggestion) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = suggestion,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlateSurface)
            .border(1.dp, DarkSlateBorder)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = {
                Text(
                    text = "Instruct DevicePilot (e.g. Set brightness to 70%)...",
                    color = TextTertiary,
                    fontSize = 13.sp
                )
            },
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DarkSlateBackground,
                unfocusedContainerColor = DarkSlateBackground,
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = DarkSlateBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentCyan
            ),
            shape = RoundedCornerShape(20.dp),
            maxLines = 4
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onSend,
            enabled = text.isNotBlank() && !isGenerating,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (text.isNotBlank() && !isGenerating) AccentCyan else DarkSlateSurfaceVariant)
        ) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = TextSecondary
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank()) DarkSlateBackground else TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
