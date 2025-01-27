package com.solita.pulse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.solita.pulse.models.MessageType

@Composable
fun ChatBubble(message: String, messageType: MessageType) {
    // Determine the bubble color based on the message type
    val bubbleColor = when (messageType) {
        MessageType.Chat -> MaterialTheme.colorScheme.primary // Blue for Chat
        MessageType.Record -> MaterialTheme.colorScheme.error // Red for Record
        MessageType.Server -> MaterialTheme.colorScheme.surfaceVariant // Grey for Server
    }

    // Determine alignment based on message type
    val alignment = when (messageType) {
        MessageType.Chat, MessageType.Record -> Alignment.CenterEnd // Align chat and record messages to the right
        MessageType.Server -> Alignment.CenterStart // Align server messages to the left
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .wrapContentSize(alignment)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(8.dp),
            shape = RoundedCornerShape(12.dp),
            color = bubbleColor
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (messageType == MessageType.Chat || messageType == MessageType.Record)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}