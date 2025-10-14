package org.autoharness.cartoolplayground

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.autoharness.cartoolplayground.ui.chat.ChatScreen
import org.autoharness.cartoolplayground.ui.chat.ChatViewModel
import org.autoharness.cartoolplayground.ui.chat.settings.SettingsViewModel
import org.autoharness.cartoolplayground.ui.property.CarPropertyScreen
import org.autoharness.cartoolplayground.ui.property.CarPropertyViewModel
import org.autoharness.cartoolplayground.ui.theme.CarToolPlaygroundTheme

class CarToolPlaygroundActivity : ComponentActivity() {
    private val chatViewModel: ChatViewModel by viewModels()
    private val carPropertyViewModel: CarPropertyViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        CarConnectionRepository.connect(this)

        carPropertyViewModel.initialize(
            CarConnectionRepository.carPropertyManager,
            chatViewModel.carPropertyProfiles,
        )

        setContent {
            CarToolPlaygroundTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Left pane.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            CarPropertyScreen(carPropertyViewModel = carPropertyViewModel)
                        }

                        VerticalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp),
                            color = Color.Gray.copy(alpha = 0.5f),
                        )

                        // Right Pane.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            ChatScreen(
                                chatViewModel = chatViewModel,
                                settingsViewModel = settingsViewModel,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CarConnectionRepository.disconnect()
    }
}
