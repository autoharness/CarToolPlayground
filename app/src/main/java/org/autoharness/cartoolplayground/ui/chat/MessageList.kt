package org.autoharness.cartoolplayground.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    showDebugInfo: Boolean,
    modifier: Modifier = Modifier,
) {
    val filteredMessages = if (showDebugInfo) {
        messages
    } else {
        messages.filter { it.type != ChatMessage.MessageType.DEBUG }
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        reverseLayout = true,
    ) {
        itemsIndexed(filteredMessages.asReversed()) { _, message ->
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = when (message.type) {
                        ChatMessage.MessageType.USER -> {
                            Arrangement.End
                        }
                        ChatMessage.MessageType.MODEL -> {
                            Arrangement.Start
                        }
                        else -> {
                            Arrangement.Center
                        }
                    },
                ) {
                    val configuration = LocalConfiguration.current
                    val screenWidth = configuration.screenWidthDp.dp
                    MessageBubble(
                        message = message,
                        modifier = Modifier.widthIn(max = screenWidth * 0.8f),
                    )
                }
            }
        }
    }
}
