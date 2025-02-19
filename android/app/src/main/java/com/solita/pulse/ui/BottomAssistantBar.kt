package com.solita.pulse.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp


@Composable
fun BottomAssistantBar(
    isListening: Int,
    onChatClick: () -> Unit,
    onRecordClick: () -> Unit,
    onStopClick: () -> Unit,
    selectedLocale: String
) {
    if (isListening == 0){
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {

            val animatedChatColor by animateColorAsState(
                targetValue = if (isListening == 1) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary,
                animationSpec = tween(
                    durationMillis = 500
                ),

                label = "background"
            )

            val animatedChatBorderColor by animateColorAsState(
                targetValue = if (isListening == 1) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f) else Color.Transparent,
                animationSpec = tween(
                    durationMillis = 500
                ),

                label = "border"
            )
            val animatedRecordColor by animateColorAsState(
                targetValue = if (isListening == 2) if (isSystemInDarkTheme()) Color(0xFF5A202F).copy(0.8f) else Color(0xFFFF7A90).copy(0.8f) else if (isSystemInDarkTheme()) Color(0xFF5A202F) else Color(0xFFFF7A90),
                animationSpec = tween(
                    durationMillis = 500
                ),

                label = "background"
            )

            val animatedRecordBorderColor by animateColorAsState(
                targetValue = if (isListening == 2) if (isSystemInDarkTheme()) Color(0xFFFF7A90).copy(0.7f) else Color(0xFF5A202F).copy(0.8f) else (Color.Transparent),
                animationSpec = tween(
                    durationMillis = 500
                ),

                label = "background"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 100.dp)
                    .border(width = 2.dp, color = animatedChatBorderColor, shape = RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(animatedChatColor)
                    .padding(8.dp)
                    .clickable { onChatClick() },

                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                )
                {
                    // Voice Input Button
                    Icon(
                        imageVector = Icons.Outlined.Mic,// Replace with your mic icon
                        contentDescription = "Voice Input",
                        modifier = Modifier
                            .size(36.dp)
                            .padding(8.dp),
                        tint = MaterialTheme.colorScheme.primaryContainer
                    )
                    Text(
                        if (selectedLocale == "fi") "Keskustelu" else "Chat",
                        color = MaterialTheme.colorScheme.primaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(32.dp))
            Box(
                modifier = Modifier

                    .weight(1f)
                    .defaultMinSize(minWidth = 100.dp)
                    .clip(RoundedCornerShape(50))
                    .border(width = 2.dp, color = animatedRecordBorderColor, shape = RoundedCornerShape(50))
                    .background(animatedRecordColor)
                    .padding(8.dp)

                    .clickable { onRecordClick() },

                contentAlignment = Alignment.Center

            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                )
                {
                    // Voice Input Button

                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.StickyNote2,// Replace with your mic icon
                        contentDescription = "Voice Input",
                        modifier = Modifier
                            .size(36.dp)
                            .padding(8.dp),
                        tint = if (isSystemInDarkTheme()) Color(0xFFFF7A90) else Color(0xFF5A202F)
                    )


                    Text(
                        if (selectedLocale == "fi") "Kirjoittaa" else "Record",
                        color = if (isSystemInDarkTheme()) Color(0xFFFF7A90) else Color(0xFF5A202F)
                    )
                }

            }

        }
    }
    else{
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {

            val animatedStopColor by animateColorAsState(
                targetValue = if (isListening == 1) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else MaterialTheme.colorScheme.error,
                animationSpec = tween(
                    durationMillis = 500
                ),

                label = "background"
            )

            val animatedStopBorderColor by animateColorAsState(
                targetValue = if (isListening == 1) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.errorContainer,
                animationSpec = tween(
                    durationMillis = 500
                ),

                label = "border"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 100.dp)
                    .border(width = 2.dp, color = animatedStopBorderColor, shape = RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(animatedStopColor)
                    .padding(8.dp)
                    .clickable { onStopClick() },

                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                )
                {
                    // Voice Input Button
                    Icon(
                        imageVector = Icons.Outlined.Block,
                        contentDescription = "Stop Listening",
                        modifier = Modifier
                            .size(36.dp)
                            .padding(8.dp),
                        tint = MaterialTheme.colorScheme.errorContainer
                    )
                    Text(
                        if (selectedLocale == "fi") "Lopeta kuunteleminen" else "Stop Listening",
                        color = MaterialTheme.colorScheme.errorContainer
                    )
                }
            }
        }
    }
}
