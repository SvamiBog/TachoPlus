package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DriverActivity
import com.example.ui.theme.ColorAvailable
import com.example.ui.theme.ColorDriving
import com.example.ui.theme.ColorRest
import com.example.ui.theme.ColorWork

fun activityColor(activity: DriverActivity?): Color = when (activity) {
    DriverActivity.DRIVING -> ColorDriving
    DriverActivity.WORK -> ColorWork
    DriverActivity.AVAILABLE -> ColorAvailable
    DriverActivity.REST -> ColorRest
    null -> Color(0xFF64748B)
}

fun activityIcon(activity: DriverActivity): ImageVector = when (activity) {
    DriverActivity.DRIVING -> Icons.Default.LocalShipping
    DriverActivity.WORK -> Icons.Default.Build
    DriverActivity.AVAILABLE -> Icons.Default.HourglassBottom
    DriverActivity.REST -> Icons.Default.Hotel
}

/**
 * Driver activity buttons. When [enabled] is false the tachograph reports the activity and the buttons only
 * show it.
 */
@Composable
fun ActivitySelector(
    currentActivity: DriverActivity?,
    sinceText: String?,
    enabled: Boolean,
    hint: String,
    onSelectActivity: (DriverActivity) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "РЕЖИМ ВОДИТЕЛЯ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = (currentActivity?.titleRu ?: "не задан") + (sinceText?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = activityColor(currentActivity),
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DriverActivity.entries.forEach { activity ->
                ActivityButton(
                    activity = activity,
                    isSelected = currentActivity == activity,
                    enabled = enabled,
                    onSelect = { onSelectActivity(activity) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = hint, fontSize = 11.sp, color = Color(0xFF94A3B8), lineHeight = 15.sp)
    }
}

@Composable
private fun ActivityButton(
    activity: DriverActivity,
    isSelected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = activityColor(activity)
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) accent.copy(alpha = 0.22f) else Color(0xFF0F172A),
        label = "ActivityBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) accent else Color(0xFF334155),
        label = "ActivityBorder"
    )
    Column(
        modifier = modifier
            .testTag("activity_button_${activity.name.lowercase()}")
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onSelect)
            .alpha(if (enabled || isSelected) 1f else 0.5f)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = activityIcon(activity),
            contentDescription = activity.titleRu,
            tint = if (isSelected) accent else Color(0xFF94A3B8),
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = activity.titleRu,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color.White else Color(0xFF94A3B8),
            maxLines = 1
        )
    }
}
