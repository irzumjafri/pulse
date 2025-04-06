package com.solita.pulse.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.* // Import Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator // Import CircularProgressIndicator
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
fun ChatBubble(
    message: String,
    messageType: MessageType,
    // Add new optional parameters
    isPartial: Boolean = false,
    isLoading: Boolean = false
) {
    // Define light and dark mode colors for each message type
    val (lightBubbleColor, darkBubbleColor) = when (messageType) {
        MessageType.Chat -> Pair(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.primaryContainer
        )
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

    // Use slightly different color/alpha for partial or loading messages if desired
    val finalBubbleColor = if (isLoading) bubbleColor.copy(alpha = 0.8f) else bubbleColor

    // Determine text color (ensure good contrast) - Consider adjusting based on bubbleColor
    val textColor: Color = when {
        messageType == MessageType.Record && !isSystemInDarkTheme() -> Color(0xFF5A202F) // Dark text on light pink
        messageType == MessageType.Record && isSystemInDarkTheme() -> Color(0xFFFFD9DE) // Light text on dark pink
        messageType == MessageType.Server && !isSystemInDarkTheme() -> Color.Black.copy(alpha = 0.87f) // Dark text on light gray
        messageType == MessageType.Server && isSystemInDarkTheme() -> Color.White.copy(alpha = 0.87f) // Light text on dark gray
        else -> MaterialTheme.colorScheme.onPrimaryContainer // Default for Chat, or adjust as needed
    }.let { baseColor ->
        //Partial text Color
        if (isPartial) baseColor.copy(alpha = 0.7f) else baseColor
    }


    // Determine alignment based on message type
    val alignment = when (messageType) {
        MessageType.Chat, MessageType.Record -> Alignment.CenterEnd
        MessageType.Server -> Alignment.CenterStart
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
            color = finalBubbleColor
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message,
                    // Removed modifier here, padding is on Row/Surface
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
                // Conditionally show loading indicator
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).padding(start = 12.dp),
                        color = textColor, // Use text color for indicator
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}