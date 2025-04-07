package com.solita.pulse.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
// Removed unused animateDpAsState import
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.* // Keep wildcard or import specific ones
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha // Import alpha modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp


@Composable
fun BottomAssistantBar(
    // Rename parameter and keep type Int
    listeningState: Int, // 0 = Idle, 1 = Chat Listening, 2 = Record Listening
    // Add enabled parameter
    enabled: Boolean,
    onChatClick: () -> Unit,
    onRecordClick: () -> Unit,
    onStopClick: () -> Unit,
    selectedLocale: String
) {
    // Determine states based on listeningState
    val isIdle = listeningState == 0
    val isChatListening = listeningState == 1

    // Adjust alpha based on enabled state for visual feedback
    val contentAlpha = if (enabled) 1f else 0.5f

    if (isIdle) { // Show Chat/Record buttons when idle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // No border/background animation needed when idle, apply directly
            val chatButtonColor = MaterialTheme.colorScheme.primary
            val chatBorderColor = Color.Transparent // No border when idle
            val recordButtonColor = if (isSystemInDarkTheme()) Color(0xFF5A202F) else Color(0xFFFF7A90)
            val recordBorderColor = Color.Transparent // No border when idle
            val recordContentColor = if (isSystemInDarkTheme()) Color(0xFFFFD9DE) else Color(0xFF5A202F)

            // Chat Button Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 100.dp)
                    // Apply alpha based on enabled state
                    .alpha(contentAlpha)
                    .border(width = 2.dp, color = chatBorderColor, shape = RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(chatButtonColor)
                    .clickable(enabled = enabled) { if (enabled) onChatClick() } // Use enabled parameter
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = "Voice Input",
                        modifier = Modifier.size(36.dp).padding(8.dp),
                        tint = MaterialTheme.colorScheme.onPrimary // Ensure contrast
                    )
                    Text(
                        if (selectedLocale == "fi") "Keskustelu" else "Chat",
                        color = MaterialTheme.colorScheme.onPrimary // Ensure contrast
                    )
                }
            } // End Chat Button Box

            Spacer(modifier = Modifier.width(32.dp))

            // Record Button Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 100.dp)
                    // Apply alpha based on enabled state
                    .alpha(contentAlpha)
                    .clip(RoundedCornerShape(50))
                    .border(width = 2.dp, color = recordBorderColor, shape = RoundedCornerShape(50))
                    .background(recordButtonColor)
                    .clickable(enabled = enabled) { if (enabled) onRecordClick() } // Use enabled parameter
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.StickyNote2,
                        contentDescription = "Record Input",
                        modifier = Modifier.size(36.dp).padding(8.dp),
                        tint = recordContentColor
                    )
                    Text(
                        if (selectedLocale == "fi") "Kirjoittaa" else "Record",
                        color = recordContentColor
                    )
                }
            } // End Record Button Box
        } // End Idle Row
    } else { // Show Stop button when listening (listeningState is 1 or 2)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center, // Center the stop button
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Use animated colors based on which mode is active (Chat or Record)
            val animatedStopColor by animateColorAsState(
                targetValue = if (isChatListening) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else Color(0xFFB58392).copy(alpha = 0.8f), // Use chat or record color base
                animationSpec = tween(durationMillis = 500),
                label = "stopBackground"
            )
            val animatedStopBorderColor by animateColorAsState(
                targetValue = if (isChatListening) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f) else Color(0xFFFFD9DE).copy(alpha = 0.8f), // Use chat or record container/accent
                animationSpec = tween(durationMillis = 500),
                label = "stopBorder"
            )
            val stopContentColor = if (isChatListening) MaterialTheme.colorScheme.onPrimary else Color(0xFF5A202F)


            // Stop Button Box
            Box(
                modifier = Modifier
                    // Adjust weight or remove if centering a single button
                    .fillMaxWidth()
                    .defaultMinSize(minWidth = 100.dp) // Make stop button maybe wider
                    // Apply alpha based on enabled state
                    .alpha(contentAlpha)
                    .border(width = 2.dp, color = animatedStopBorderColor, shape = RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(animatedStopColor)
                    .clickable(enabled = enabled) { if (enabled) onStopClick() } // Use enabled parameter
                    .padding(vertical = 8.dp, horizontal = 16.dp), // Adjust padding
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Block,
                        contentDescription = "Stop Listening",
                        modifier = Modifier.size(36.dp).padding(bottom = 4.dp), // Adjust padding
                        tint = stopContentColor // Use appropriate content color
                    )
                    Text(
                        if (selectedLocale == "fi") "Lopeta kuunteleminen" else "Stop Listening", // Shorter text
                        color = stopContentColor // Use appropriate content color
                    )
                }
            } // End Stop Button Box
        } // End Listening Row
    }
}