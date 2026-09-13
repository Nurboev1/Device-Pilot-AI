package com.devicepilot.ai.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.devicepilot.ai.llm.ModelState
import com.devicepilot.ai.model.BrainMode
import com.devicepilot.ai.shizuku.ShizukuState
import com.devicepilot.ai.ui.components.MessageBubble
import com.devicepilot.ai.ui.screens.ModelSheet
import com.devicepilot.ai.ui.theme.*

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val shizukuState by viewModel.shizukuState.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val installed by viewModel.installedModels.collectAsState()
    val autoConfirm by viewModel.autoConfirm.collectAsState()
    val brainMode by viewModel.brainMode.collectAsState()
    val cloudConfig by viewModel.cloudConfig.collectAsState()
    val brainReady by viewModel.brainReady.collectAsState()

    var input by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Ask for notification permission once (needed for the foreground-service notification on 13+)
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasOverlay = Settings.canDrawOverlays(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.steps?.size, last?.thinking?.length, last?.text?.length, last?.pendingConfirmation, last?.pendingQuestion) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val awaitingAnswer = last?.pendingQuestion != null
    val modelReady = brainReady

    Surface(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), color = DarkSlateBackground) {
        Column(Modifier.fillMaxSize()) {
            Header(
                shizukuState = shizukuState,
                modelState = modelState,
                brainMode = brainMode,
                cloudLabel = "${cloudConfig.providerName.substringBefore(" (")} · ${cloudConfig.model.substringAfterLast('/')}",
                cloudReady = brainReady,
                onShizukuClick = { if (shizukuState is ShizukuState.Unauthorized) viewModel.requestShizuku() else viewModel.refreshShizuku() },
                onModelClick = { showModels = true },
                onClear = { viewModel.clearChat() }
            )

            if (!hasOverlay) {
                InfoBanner("Allow \"Display over other apps\" so you get a floating Stop button while the agent works.") {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages, key = { it.id }) { m ->
                    MessageBubble(
                        message = m,
                        onConfirm = { viewModel.confirm(true) },
                        onDeny = { viewModel.confirm(false) }
                    )
                }
            }

            if (!isRunning) {
                Suggestions { viewModel.send(it) }
            }

            InputBar(
                text = input,
                onTextChange = { input = it },
                placeholder = when {
                    awaitingAnswer -> "Answer the agent…"
                    !modelReady && brainMode == BrainMode.CLOUD -> "Add an API key in ⚙ to start"
                    !modelReady -> "Model not ready"
                    else -> "Tell DevicePilot what to do…"
                },
                enabled = (modelReady && !isRunning) || awaitingAnswer,
                isRunning = isRunning && !awaitingAnswer,
                onSend = { if (input.isNotBlank()) { viewModel.send(input); input = "" } },
                onStop = { viewModel.stop() }
            )
        }
    }

    if (showModels) {
        ModelSheet(
            brainMode = brainMode,
            cloudConfig = cloudConfig,
            onBrainMode = { viewModel.setBrainMode(it) },
            onProvider = { viewModel.setProvider(it) },
            onCloudUpdate = { b, m, k -> viewModel.updateCloud(b, m, k) },
            selected = selectedModel,
            installed = installed,
            modelState = modelState,
            autoConfirm = autoConfirm,
            isRunning = isRunning,
            onSelect = { viewModel.selectModel(it) },
            onRetry = { viewModel.retryModel() },
            onDelete = { viewModel.deleteModel(it) },
            onAutoConfirm = { viewModel.setAutoConfirm(it) },
            onDismiss = { showModels = false }
        )
    }
}

@Composable
private fun Header(
    shizukuState: ShizukuState,
    modelState: ModelState,
    brainMode: BrainMode,
    cloudLabel: String,
    cloudReady: Boolean,
    onShizukuClick: () -> Unit,
    onModelClick: () -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(DarkSlateSurface).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DevicePilot", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("On-device agent · Shizuku", color = TextTertiary, fontSize = 11.sp)
            }
            IconButton(onClick = onModelClick) { Icon(Icons.Default.Settings, null, tint = TextSecondary) }
            IconButton(onClick = onClear) { Icon(Icons.Default.DeleteSweep, null, tint = TextSecondary) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val (sLabel, sColor, sBg) = when (shizukuState) {
                ShizukuState.Connected -> Triple("Shizuku ready", StatusGreen, StatusGreenBg)
                ShizukuState.Unauthorized -> Triple("Shizuku: tap to authorize", StatusAmber, StatusAmberBg)
                ShizukuState.NotRunning -> Triple("Shizuku not running", StatusRose, StatusRoseBg)
                is ShizukuState.Unsupported -> Triple("Shizuku unsupported", StatusRose, StatusRoseBg)
            }
            StatusChip(Icons.Default.Security, sLabel, sColor, sBg, onShizukuClick)

            val (mLabel, mColor, mBg) = if (brainMode == BrainMode.CLOUD) {
                if (cloudReady) Triple(cloudLabel, StatusGreen, StatusGreenBg) else Triple("Cloud: add API key", StatusAmber, StatusAmberBg)
            } else when (modelState) {
                is ModelState.Loaded -> Triple(modelState.spec.displayName, StatusGreen, StatusGreenBg)
                is ModelState.Loading -> Triple("Loading ${modelState.spec.displayName}…", StatusAmber, StatusAmberBg)
                is ModelState.Downloading -> Triple("Downloading ${(modelState.fraction * 100).toInt()}%", AccentCyan, DarkSlateSurfaceVariant)
                is ModelState.Error -> Triple("Model error — tap", StatusRose, StatusRoseBg)
                ModelState.Unloaded -> Triple("No model", TextSecondary, DarkSlateSurfaceVariant)
            }
            StatusChip(if (brainMode == BrainMode.CLOUD) Icons.Default.Cloud else Icons.Default.Memory, mLabel, mColor, mBg, onModelClick)
        }
        if (brainMode == BrainMode.LOCAL && modelState is ModelState.Downloading) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { modelState.fraction }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)), color = AccentCyan, trackColor = DarkSlateSurfaceVariant)
        }
    }
}

@Composable
private fun StatusChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, bg: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(bg.copy(alpha = 0.55f)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun InfoBanner(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(StatusAmberBg.copy(alpha = 0.35f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = StatusAmber, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("Grant", color = StatusAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Suggestions(onPick: (String) -> Unit) {
    val items = listOf(
        "Turn on dark mode", "Set brightness to 40%", "Which apps have camera permission?",
        "Open Settings and go to Bluetooth", "Read my notifications", "Open YouTube and search for lo-fi"
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { s ->
            Text(
                s, color = AccentIndigo, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(DarkSlateSurface).border(1.dp, DarkSlateBorder.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    .clickable { onPick(s) }.padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun InputBar(
    text: String, onTextChange: (String) -> Unit, placeholder: String,
    enabled: Boolean, isRunning: Boolean, onSend: () -> Unit, onStop: () -> Unit
) {
    Row(Modifier.fillMaxWidth().background(DarkSlateSurface).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = onTextChange, enabled = enabled,
            placeholder = { Text(placeholder, color = TextTertiary) },
            modifier = Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, disabledTextColor = TextTertiary,
                focusedBorderColor = AccentCyan, unfocusedBorderColor = DarkSlateBorder, disabledBorderColor = DarkSlateBorder.copy(alpha = 0.4f),
                cursorColor = AccentCyan, focusedContainerColor = DarkSlateBackground, unfocusedContainerColor = DarkSlateBackground, disabledContainerColor = DarkSlateBackground
            )
        )
        Spacer(Modifier.width(8.dp))
        if (isRunning) {
            FilledIconButton(onClick = onStop, colors = IconButtonDefaults.filledIconButtonColors(containerColor = StatusRose)) { Icon(Icons.Default.Stop, "Stop", tint = Color.White) }
        } else {
            FilledIconButton(onClick = onSend, enabled = enabled && text.isNotBlank(), colors = IconButtonDefaults.filledIconButtonColors(containerColor = AccentCyan, disabledContainerColor = DarkSlateSurfaceVariant)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = DarkSlateBackground)
            }
        }
    }
}
