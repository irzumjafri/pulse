package com.solita.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun BottomAssistantBar(
    onChatClick: () -> Unit,
    onRecordClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minWidth = 100.dp)
                .clip(RoundedCornerShape(50)) // Pill shape
                .background(MaterialTheme.colorScheme.secondaryContainer) // Light gray background
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
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Chat",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.width(32.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minWidth = 100.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.secondaryContainer) // Light gray background
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
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Record",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

    }
}
