package com.solita.pulse.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun BottomMessageBar(
    isChatActive: Boolean,
    customMessage: String,
    onMessageChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onToggleChatMode: () -> Unit,
    selectedLocale: String // e.g., "en" or "fi"
) {
    // Determine display texts based on locale and mode
    val modeText = when {
        isChatActive && selectedLocale == "fi" -> "Chat-tila"
        isChatActive && selectedLocale != "fi" -> "Chat Mode"
        !isChatActive && selectedLocale == "fi" -> "Kirjoitustila"
        else -> "Record Mode"
    }

    val textFieldLabel = when {
        isChatActive && selectedLocale == "fi" -> "Kysy Pulssilta"
        isChatActive && selectedLocale != "fi" -> "Ask Pulse"
        !isChatActive && selectedLocale == "fi" -> "Kirjoita Pulssilla"
        else -> "Record with Pulse"
    }

    // Determine Switch colors based on dark mode and state
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.primary,
        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        // Use distinct colors for the "Record" (unchecked) state
        uncheckedThumbColor = if (isSystemInDarkTheme()) Color(0xFF5A202F) else Color(0xFFFF7A90), // Dark Pink / Light Pink
        uncheckedTrackColor = (if (isSystemInDarkTheme()) Color(0xFF5A202F) else Color(0xFFFF7A90)).copy(alpha = 0.7f)
    )

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { // Added bottom padding
        // Toggle Button Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Reduced padding for toggle row to make it less spaced out
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = modeText,
                style = MaterialTheme.typography.labelMedium, // Slightly smaller label
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp)) // Reduced spacer
            Switch(
                checked = isChatActive,
                onCheckedChange = { onToggleChatMode() }, // No change needed here
                colors = switchColors
                // Add thumbContent for icons if desired
            )
        }

        // Text Box and Send Button Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp), // Adjusted padding
            verticalAlignment = Alignment.CenterVertically // Align items vertically
        ) {
            TextField(
                value = customMessage,
                onValueChange = onMessageChange,
                label = { Text(textFieldLabel) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), // Subtle background
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                    focusedIndicatorColor = Color.Transparent, // Hide indicator line
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .weight(1f)
                // Removed border, using background color and shape instead
                // .border(1.dp, MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                ,
                shape = RoundedCornerShape(8.dp), // Apply shape
                singleLine = true // Make it a single line input
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Send Button
            IconButton(
                onClick = onSendMessage,
                enabled = customMessage.isNotBlank(), // Enable based on text
                modifier = Modifier.size(48.dp) // Ensure consistent button size
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (selectedLocale == "fi") "Lähetä" else "Send", // Content description
                    tint = if (customMessage.isNotBlank()) {
                        MaterialTheme.colorScheme.primary // Use primary color when enabled
                    } else {
                        MaterialTheme.colorScheme.onSurface // Use disabled alpha
                    }
                )
            }
        }
    }
}