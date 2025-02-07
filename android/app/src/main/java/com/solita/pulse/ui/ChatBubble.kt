package com.solita.pulse.ui

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.solita.pulse.models.MessageType

@Composable
fun ChatBubble(message: String, messageType: MessageType) {
    // Define light and dark mode colors for each message type
    val (lightBubbleColor, darkBubbleColor) = when (messageType) {
        MessageType.Chat -> Pair(MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.primaryContainer)

        MessageType.Record -> Pair(
            Color(0xFFFF7A90), Color(0xFF5A202F)
        ) // Light: #FF7A90, Dark: #5A202F
        MessageType.Server -> Pair(
            Color(0xFFE0E0E0), Color(0xFF333333)
        ) // Light: #E0E0E0, Dark: #333333
    }

    // Determine the bubble color based on the message type and dark mode
    val bubbleColor: Color = if (isSystemInDarkTheme()) {
        darkBubbleColor
    } else {
        lightBubbleColor
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
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(12.dp),
            color = bubbleColor
        ) {

            Text(
                text = message,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (messageType == MessageType.Chat || messageType == MessageType.Record) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}