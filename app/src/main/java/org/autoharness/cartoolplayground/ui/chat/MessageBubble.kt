package org.autoharness.cartoolplayground.ui.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MessageBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    val bubbleColor = when (message.type) {
        ChatMessage.MessageType.USER -> {
            MaterialTheme.colorScheme.primary
        }

        ChatMessage.MessageType.MODEL -> {
            MaterialTheme.colorScheme.secondaryContainer
        }

        ChatMessage.MessageType.DEBUG -> {
            MaterialTheme.colorScheme.tertiaryContainer
        }

        ChatMessage.MessageType.WARNING -> {
            MaterialTheme.colorScheme.errorContainer
        }
    }

    val textColor = when (message.type) {
        ChatMessage.MessageType.USER -> {
            MaterialTheme.colorScheme.onPrimary
        }

        ChatMessage.MessageType.MODEL -> {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

        ChatMessage.MessageType.DEBUG -> {
            MaterialTheme.colorScheme.onTertiaryContainer
        }

        ChatMessage.MessageType.WARNING -> {
            MaterialTheme.colorScheme.onErrorContainer
        }
    }

    Surface(
        color = bubbleColor,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Text(
            text = message.text,
            color = textColor,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
