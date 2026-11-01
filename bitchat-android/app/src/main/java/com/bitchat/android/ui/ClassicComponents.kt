package com.bitchat.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.ClassicColors

/**
 * Classic-format Avatar with optional compressed profile photo and online green indicator badge
 */
@Composable
fun ClassicAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    backgroundColor: Color = ClassicColors.Teal,
    isOnline: Boolean = false,
    avatarBitmap: Bitmap? = null,
    content: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            when {
                content != null -> content()
                avatarBitmap != null -> {
                    Image(
                        bitmap = avatarBitmap.asImageBitmap(),
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    val initial = name.trim().take(1).uppercase().ifEmpty { "?" }
                    Text(
                        text = initial,
                        color = Color.White,
                        fontSize = (size.value * 0.42f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = BitchatFontFamily
                    )
                }
            }
        }

        if (isOnline) {
            Box(
                modifier = Modifier
                    .size(size * 0.28f)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(ClassicColors.OnlineDot)
            )
        }
    }
}

/**
 * Classic-format Status Checkmarks (clock, single check, grey double check, blue double check)
 */
@Composable
fun ClassicCheckmark(
    status: DeliveryStatus?,
    modifier: Modifier = Modifier,
    isOutgoing: Boolean = true
) {
    if (!isOutgoing || status == null) return

    val iconSize = 15.dp
    when (status) {
        is DeliveryStatus.Sending -> {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = "Sending",
                modifier = modifier.size(iconSize),
                tint = ClassicColors.CheckmarkGrey
            )
        }
        is DeliveryStatus.Sent -> {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Sent",
                modifier = modifier.size(iconSize),
                tint = ClassicColors.CheckmarkGrey
            )
        }
        is DeliveryStatus.Delivered, is DeliveryStatus.PartiallyDelivered -> {
            Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "Delivered",
                modifier = modifier.size(iconSize),
                tint = ClassicColors.CheckmarkGrey
            )
        }
        is DeliveryStatus.Read -> {
            Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "Read",
                modifier = modifier.size(iconSize),
                tint = ClassicColors.CheckmarkBlue
            )
        }
        is DeliveryStatus.Failed -> {
            Text(
                text = "!",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Classic Date Separator Pill ("TODAY", "YESTERDAY", Date)
 */
@Composable
fun ClassicDatePill(
    dateText: String,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isDark) Color(0xFF182229) else Color(0xFFFFFFFF).copy(alpha = 0.92f),
            shadowElevation = 1.dp
        ) {
            Text(
                text = dateText.uppercase(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (isDark) Color(0xFF8696A0) else Color(0xFF54656F),
                fontFamily = BitchatFontFamily
            )
        }
    }
}

/**
 * Classic Unread Count Badge (solid green round pill)
 */
@Composable
fun ClassicUnreadBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    if (count <= 0) return

    val text = if (count > 99) "99+" else count.toString()
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .clip(CircleShape)
            .background(ClassicColors.UnreadBadge)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = BitchatFontFamily
        )
    }
}
