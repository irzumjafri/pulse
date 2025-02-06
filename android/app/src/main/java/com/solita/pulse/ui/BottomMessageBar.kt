package com.solita.pulse.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send

@Composable
fun BottomMessageBar(
    isChatActive: Boolean,
    customMessage: String,
    onMessageChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onToggleChatMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Toggle Button to Switch Between /chat and /record
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (isChatActive) "Chat Mode" else "Record Mode")
            Spacer(modifier = Modifier.width(16.dp))
            Switch(checked = isChatActive, onCheckedChange = { onToggleChatMode() })
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
                label = { Text(if (isChatActive) "Ask Pulse" else "Record with Pulse") },
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
