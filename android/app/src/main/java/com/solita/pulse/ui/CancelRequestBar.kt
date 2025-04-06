package com.solita.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun CancelRequestBar(
    modifier: Modifier = Modifier,
    onCancelClick: () -> Unit,
    selectedLocale: String
) {
        Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 8.dp), // Match padding of other bars
        horizontalArrangement = Arrangement.SpaceAround, // Center the button
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minWidth = 100.dp)
                // Apply alpha based on enabled state
                .border(width = 2.dp, color = MaterialTheme.colorScheme.error, shape = RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable(enabled = true) { onCancelClick() }
                .padding(8.dp),
            contentAlignment = Alignment.Center
        )
        {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            )
            {
                Icon(
                    Icons.Outlined.Cancel,
                    contentDescription = "Cancel Request",
                    modifier = Modifier.size(36.dp).padding(8.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                if (selectedLocale == "fi") {
                    Text("Peruuta Pyyntö")
                }
                else {
                    Text("Cancel Request")
                }
            }
        }

    }
}