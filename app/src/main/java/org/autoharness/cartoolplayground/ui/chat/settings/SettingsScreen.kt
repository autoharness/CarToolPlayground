package org.autoharness.cartoolplayground.ui.chat.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.io.File

@Composable
fun SettingsScreen(
    currentSettings: LlmSettings,
    onSave: (LlmSettings) -> Unit,
    onCancel: () -> Unit,
) {
    var engine by remember { mutableStateOf(currentSettings.engine) }
    var modelPath by remember { mutableStateOf(currentSettings.modelPath) }
    var maxTokens by remember { mutableStateOf(currentSettings.maxTokens.toString()) }
    var topK by remember { mutableIntStateOf(currentSettings.topK) }
    var topP by remember { mutableFloatStateOf(currentSettings.topP) }
    var temperature by remember { mutableFloatStateOf(currentSettings.temperature) }
    var thinkingMode by remember { mutableStateOf(currentSettings.thinkingMode) }
    var showDebugInfo by remember { mutableStateOf(currentSettings.showDebugInfo) }
    var showFileSelector by remember { mutableStateOf(false) }

    if (showFileSelector) {
        FileSelectorDialog(
            onFileSelected = {
                modelPath = it.absolutePath
                showFileSelector = false
            },
            onDismiss = { showFileSelector = false },
        )
    }

    /* ==== The settings screen layout. ==== */
    Dialog(onDismissRequest = onCancel) {
        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .padding(16.dp)
                .heightIn(max = 700.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Settings", style = MaterialTheme.typography.titleLarge)

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Engine Selection.
                    item {
                        SettingsEnumDropdown(
                            label = "Inference Engine",
                            items = InferenceEngine.entries,
                            selectedItem = engine,
                            onItemSelected = { engine = it },
                        )
                    }

                    // Model Path.
                    if (engine == InferenceEngine.LITE_RT_LM) {
                        item {
                            SettingsItem(label = "Model File", value = File(modelPath).name) {
                                showFileSelector = true
                            }
                        }
                    }

                    // Max Tokens input.
                    item {
                        OutlinedTextField(
                            value = maxTokens,
                            onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
                            label = { Text("Max Tokens") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Sliders: Top-K, Top-P, Temperature.
                    item {
                        SliderSettingsItem("Top-K", topK.toFloat(), 1f..50f, 0) {
                            topK = it.toInt()
                        }
                    }
                    item {
                        SliderSettingsItem("Top-P", topP, 0f..1f, 1) { topP = it }
                    }
                    item {
                        SliderSettingsItem("Temperature", temperature, 0f..2f, 1) {
                            temperature = it
                        }
                    }
                    // Checkbox: Show debug info, enable think mode.
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDebugInfo = !showDebugInfo },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = showDebugInfo,
                                onCheckedChange = { showDebugInfo = it },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Show Debug Info in Conversation (e.g., function call procedures)")
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { thinkingMode = !thinkingMode },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = thinkingMode,
                                onCheckedChange = { thinkingMode = it },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Thinking Mode")
                        }
                    }
                }

                // Action Buttons.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val newSettings = LlmSettings(
                            engine = engine,
                            modelPath = modelPath,
                            maxTokens = maxTokens.toIntOrNull() ?: currentSettings.maxTokens,
                            topK = topK,
                            topP = topP,
                            temperature = temperature,
                            thinkingMode = thinkingMode,
                            showDebugInfo = showDebugInfo,
                        )
                        onSave(newSettings)
                    }) {
                        Text("OK")
                    }
                }
            }
        }
    }
}
