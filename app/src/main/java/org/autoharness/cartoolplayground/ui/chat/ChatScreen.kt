package org.autoharness.cartoolplayground.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.autoharness.cartoolplayground.ui.chat.settings.SettingsScreen
import org.autoharness.cartoolplayground.ui.chat.settings.SettingsViewModel

@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val uiState by chatViewModel.uiState.collectAsState()
    val partner by chatViewModel.currentModelTag.collectAsState()
    val settings by settingsViewModel.settings.collectAsState()
    val isAutoPlaying by chatViewModel.isAutoPlaying.collectAsState()

    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }

    if (showSettingsDialog) {
        SettingsScreen(
            currentSettings = settings,
            onSave = { newSettings ->
                if (newSettings != settings) {
                    settingsViewModel.updateSettings(newSettings)
                    chatViewModel.updateSettings(newSettings)
                }
                showSettingsDialog = false
            },
            onCancel = {
                showSettingsDialog = false
            },
        )
    }
    /* ==== The chat screen layout. ==== */
    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            ChatTopBar(
                partnerName = partner,
                onSettingsClick = { showSettingsDialog = true },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
        ) {
            when (uiState) {
                is ChatViewModel.State.Uninitialized -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 248.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Button(
                            onClick = { chatViewModel.startChat() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "Free Chat")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { chatViewModel.startAutoPlay() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "Auto Play")
                        }
                    }
                }

                is ChatViewModel.State.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is ChatViewModel.State.Error -> {
                    val errorMessage = (uiState as ChatViewModel.State.Error).message
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Error",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Oops, something went wrong",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> {
                    MessageList(
                        messages = chatViewModel.messages,
                        showDebugInfo = settings.showDebugInfo,
                        modifier = Modifier.weight(1f),
                    )
                    if (isAutoPlaying) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Button(
                                onClick = { chatViewModel.stopAutoPlay() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                                modifier = Modifier.fillMaxWidth(0.2f),
                            ) {
                                Text(text = "Stop")
                            }
                        }
                    } else {
                        MessageInput(
                            isEnabled = uiState is ChatViewModel.State.Loaded,
                            onSendMessage = { text ->
                                chatViewModel.sendMessage(text)
                            },
                        )
                    }
                }
            }
        }
    }
}
