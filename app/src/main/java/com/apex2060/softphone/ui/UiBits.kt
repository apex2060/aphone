package com.apex2060.softphone.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialKey(digit: Char, letters: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digit.toString(), fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurface)
            if (letters.isNotEmpty()) Text(
                letters, fontSize = 10.sp, letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RoundButton(
    @DrawableRes icon: Int,
    color: Color,
    size: Int = 64,
    enabled: Boolean = true,
    contentDescription: String? = null,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (enabled) color else color.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), contentDescription, tint = tint,
            modifier = Modifier.size((size * 0.42f).dp),
        )
    }
}

@Composable
fun StatusLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
