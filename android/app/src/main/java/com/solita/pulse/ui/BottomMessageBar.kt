package com.solita.pulse.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color

@Composable
fun BottomMessageBar(
    isChatActive: Boolean,
    customMessage: String,
    onMessageChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onToggleChatMode: () -> Unit,
    selectedLocale: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Toggle Button to Switch Between /chat and /record
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (isChatActive) if (selectedLocale == "fi") "Chat-tila" else "Chat Mode" else if (selectedLocale == "fi") "Kirjoitustila" else "Record Mode")
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = isChatActive,
                onCheckedChange = { onToggleChatMode() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(0.7f),
                    uncheckedThumbColor = if (isSystemInDarkTheme()) Color(0xFFFF7A90) else Color(0xFF5A202F),
                    uncheckedTrackColor = if (isSystemInDarkTheme()) Color(0xFF5A202F) else Color(0xFFFF7A90)
                )
            )
        }

        // Text Box and Send Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextField(
                value = customMessage,
                onValueChange = onMessageChange,
                label = { Text(if (isChatActive) if (selectedLocale == "fi") "Kysy Pulssilta" else "Ask Pulse" else if (selectedLocale == "fi") "Kirjoita Pulssilla" else "Record with Pulse") },
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                    disabledIndicatorColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier
                    .weight(1f)
                    .border(
                        1.dp, MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)
                    ),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onSendMessage) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    "send icon",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
