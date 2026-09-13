package com.devicepilot.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devicepilot.ai.llm.ModelState
import com.devicepilot.ai.model.ModelCatalog
import com.devicepilot.ai.model.ModelSpec
import com.devicepilot.ai.ui.theme.*

@Composable
fun ModelSheet(
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
            Text("Model", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Switching downloads the model on first use and loads it into memory.", color = TextTertiary, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))

            ModelCatalog.all.forEach { spec ->
                val isSel = spec.id == selected.id
                val isInstalled = spec.id in installed
                val busy = isSel && (modelState is ModelState.Downloading || modelState is ModelState.Loading)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (isSel) AccentCyan.copy(alpha = 0.08f) else DarkSlateBackground)
                        .border(1.dp, if (isSel) AccentCyan.copy(alpha = 0.6f) else DarkSlateBorder.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .clickable(enabled = !isRunning && !isSel) { onSelect(spec) }
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(spec.displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("${spec.approxBytes / 1_000_000} MB · ${spec.minRamGb}+ GB RAM · ctx ${spec.contextSize}", color = TextTertiary, fontSize = 11.sp)
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

            Spacer(Modifier.height(8.dp))
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
