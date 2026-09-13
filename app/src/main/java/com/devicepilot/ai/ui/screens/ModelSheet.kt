package com.devicepilot.ai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devicepilot.ai.llm.ModelState
import com.devicepilot.ai.model.*
import com.devicepilot.ai.ui.theme.*

@Composable
fun ModelSheet(
    brainMode: BrainMode,
    cloudConfig: CloudConfig,
    onBrainMode: (BrainMode) -> Unit,
    onProvider: (CloudProvider) -> Unit,
    onCloudUpdate: (baseUrl: String?, model: String?, apiKey: String?) -> Unit,
    selected: ModelSpec,
    installed: Set<String>,
    modelState: ModelState,
    autoConfirm: Boolean,
    isRunning: Boolean,
    onSelect: (ModelSpec) -> Unit,
    onRetry: () -> Unit,
    onDelete: (ModelSpec) -> Unit,
    onAutoConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DarkSlateSurface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 32.dp)) {
            Text("AI brain", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))

            // ---- Mode toggle -------------------------------------------------
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DarkSlateBackground).padding(4.dp)) {
                ModeTab("Cloud", "Fast & smart · needs internet", Icons.Default.Cloud, brainMode == BrainMode.CLOUD, Modifier.weight(1f)) { if (!isRunning) onBrainMode(BrainMode.CLOUD) }
                Spacer(Modifier.width(4.dp))
                ModeTab("Offline", "Private · runs on this phone", Icons.Default.Memory, brainMode == BrainMode.LOCAL, Modifier.weight(1f)) { if (!isRunning) onBrainMode(BrainMode.LOCAL) }
            }
            Spacer(Modifier.height(16.dp))

            if (brainMode == BrainMode.CLOUD) {
                CloudSection(cloudConfig, onProvider, onCloudUpdate)
            } else {
                LocalSection(selected, installed, modelState, isRunning, onSelect, onRetry, onDelete)
            }

            Spacer(Modifier.height(18.dp))
            Text("Safety", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-approve risky actions", color = TextPrimary, fontSize = 14.sp)
                    Text("Skip confirmation for uninstall, clear data, shell, reboot, etc. Not recommended.", color = TextTertiary, fontSize = 11.sp)
                }
                Switch(checked = autoConfirm, onCheckedChange = onAutoConfirm, colors = SwitchDefaults.colors(checkedThumbColor = StatusRose, checkedTrackColor = StatusRoseBg))
            }
        }
    }
}

@Composable
private fun ModeTab(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) AccentCyan.copy(alpha = 0.15f) else Color.Transparent)
            .border(1.dp, if (selected) AccentCyan else Color.Transparent, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (selected) AccentCyan else TextTertiary, modifier = Modifier.size(20.dp))
        Text(title, color = if (selected) TextPrimary else TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(subtitle, color = TextTertiary, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun CloudSection(cfg: CloudConfig, onProvider: (CloudProvider) -> Unit, onUpdate: (String?, String?, String?) -> Unit) {
    val context = LocalContext.current
    val provider = CloudProviders.byId(cfg.providerId)
    var key by remember(cfg.providerId) { mutableStateOf(cfg.apiKey) }
    var model by remember(cfg.providerId) { mutableStateOf(cfg.model) }
    var baseUrl by remember(cfg.providerId) { mutableStateOf(cfg.baseUrl) }
    var showKey by remember { mutableStateOf(false) }

    Text("Provider", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CloudProviders.all.forEach { p ->
            val sel = p.id == cfg.providerId
            Text(
                p.name.substringBefore(" ("), color = if (sel) DarkSlateBackground else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (sel) AccentCyan else DarkSlateBackground)
                    .border(1.dp, if (sel) AccentCyan else DarkSlateBorder.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .clickable { onProvider(p) }.padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = key, onValueChange = { key = it; onUpdate(null, null, it) },
        label = { Text("API key", color = TextTertiary) }, singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = TextTertiary) } },
        modifier = Modifier.fillMaxWidth(), colors = fieldColors()
    )
    if (provider.keyUrl.isNotBlank()) {
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, contentPadding = PaddingValues(0.dp)) {
            Text("Get a ${provider.name.substringBefore(" (")} key →", color = AccentCyan, fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(6.dp))

    Text("Model", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    if (provider.models.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            provider.models.forEach { m ->
                val sel = m == model
                Text(
                    m.substringAfterLast('/'), color = if (sel) AccentIndigo else TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(DarkSlateBackground)
                        .border(1.dp, if (sel) AccentIndigo else DarkSlateBorder.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .clickable { model = m; onUpdate(null, m, null) }.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(value = model, onValueChange = { model = it; onUpdate(null, it, null) }, label = { Text("Model id", color = TextTertiary) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())

    if (provider.id == "custom") {
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it; onUpdate(it, null, null) }, label = { Text("Base URL (…/v1)", color = TextTertiary) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        Text("Works with Ollama, LM Studio, llama-server, vLLM on your LAN. No key needed for local servers.", color = TextTertiary, fontSize = 11.sp)
    }
    Spacer(Modifier.height(8.dp))
    Text("Your key is stored only on this device and sent only to the provider you chose. Screen contents (app names, button labels) are sent to the model while it works.", color = TextTertiary, fontSize = 11.sp)
}

@Composable
private fun LocalSection(
    selected: ModelSpec, installed: Set<String>, modelState: ModelState, isRunning: Boolean,
    onSelect: (ModelSpec) -> Unit, onRetry: () -> Unit, onDelete: (ModelSpec) -> Unit
) {
    Text("Offline models run fully on-device. Hammer models are tuned for tool calling and are much more reliable than general chat models at this size.", color = TextTertiary, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    ModelCatalog.all.forEach { spec ->
        val isSel = spec.id == selected.id
        val isInstalled = spec.id in installed
        val busy = isSel && (modelState is ModelState.Downloading || modelState is ModelState.Loading)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (isSel) AccentCyan.copy(alpha = 0.08f) else DarkSlateBackground)
                .border(1.dp, if (isSel) AccentCyan.copy(alpha = 0.6f) else DarkSlateBorder.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .clickable(enabled = !isRunning && !isSel) { onSelect(spec) }.padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("${spec.approxBytes / 1_000_000} MB · ${spec.minRamGb}+ GB RAM", color = TextTertiary, fontSize = 11.sp)
                }
                when {
                    busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentCyan)
                    isSel && modelState is ModelState.Loaded -> Icon(Icons.Default.CheckCircle, null, tint = StatusGreen)
                    isInstalled -> Text("installed", color = StatusGreen, fontSize = 11.sp)
                    else -> Icon(Icons.Default.Download, null, tint = TextSecondary)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(spec.description, color = TextSecondary, fontSize = 12.sp)
            if (isSel && modelState is ModelState.Downloading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { modelState.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = AccentCyan, trackColor = DarkSlateSurfaceVariant)
                Text("${modelState.downloadedBytes / 1_000_000} / ${modelState.totalBytes / 1_000_000} MB", color = TextTertiary, fontSize = 11.sp)
            }
            if (isSel && modelState is ModelState.Error) {
                Spacer(Modifier.height(6.dp))
                Text(modelState.message, color = StatusRose, fontSize = 12.sp)
                TextButton(onClick = onRetry) { Text("Retry", color = AccentCyan) }
            }
            if (isInstalled && !isRunning && !busy) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onDelete(spec) }) {
                        Icon(Icons.Default.Delete, null, tint = TextTertiary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                        Text("Delete file", color = TextTertiary, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
    focusedBorderColor = AccentCyan, unfocusedBorderColor = DarkSlateBorder,
    cursorColor = AccentCyan, focusedContainerColor = DarkSlateBackground, unfocusedContainerColor = DarkSlateBackground
)
