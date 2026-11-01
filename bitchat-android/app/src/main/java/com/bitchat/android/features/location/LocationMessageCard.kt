package com.bitchat.android.features.location

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.ClassicColors
import java.util.Locale

/**
 * Zero-SDK, zero-network-leak Compose Canvas location card rendered inside classic-format bubbles.
 * Opens the OS standard map application via `geo:` Intent on tap.
 */
@Composable
fun LocationMessageCard(
    location: LocationShareHelper.SharedLocation,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapBg = if (isDark) Color(0xFF13262E) else Color(0xFFE5F3EE)
    val gridColor = if (isDark) Color(0xFF234754) else Color(0xFFBBDAD0)
    val ringColor = ClassicColors.Teal.copy(alpha = 0.25f)
    val textPrimary = if (isDark) Color.White else Color(0xFF111B21)
    val textSecondary = if (isDark) Color(0xFF9EB0B8) else Color(0xFF54656F)

    Column(
        modifier = modifier
            .width(248.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isDark) Color(0xFF1A282F) else Color(0xFFF6FAF8))
            .clickable { LocationShareHelper.openInOsMap(context, location) }
    ) {
        // Lightweight OS Canvas Coordinate Radar (zero external tile requests!)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .background(mapBg),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = 22.dp.toPx()
                var x = step
                while (x < size.width) {
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f
                    )
                    x += step
                }
                var y = step
                while (y < size.height) {
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    y += step
                }

                val center = Offset(size.width / 2f, size.height / 2f)
                // Crosshair
                drawLine(
                    color = ClassicColors.Teal.copy(alpha = 0.45f),
                    start = Offset(0f, center.y),
                    end = Offset(size.width, center.y),
                    strokeWidth = 1.2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
                drawLine(
                    color = ClassicColors.Teal.copy(alpha = 0.45f),
                    start = Offset(center.x, 0f),
                    end = Offset(center.x, size.height),
                    strokeWidth = 1.2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
                // Accuracy circles
                drawCircle(color = ringColor, radius = 34.dp.toPx(), center = center)
                drawCircle(
                    color = ClassicColors.Teal.copy(alpha = 0.6f),
                    radius = 34.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.5f)
                )
            }

            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = "位置ピン",
                tint = Color(0xFFE53935),
                modifier = Modifier
                    .size(36.dp)
                    .offset(y = (-8).dp)
            )

            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                Text(
                    text = "OS測位 · 追跡なし",
                    color = Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontFamily = BitchatFontFamily
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (location.label.isNotBlank()) {
                Text(
                    text = location.label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = textPrimary,
                    fontFamily = BitchatFontFamily
                )
            }
            val coordsText = String.format(
                Locale.US,
                "%.5f, %.5f (±%dm)",
                location.latitude,
                location.longitude,
                location.accuracyMeters
            )
            Text(
                text = coordsText,
                fontSize = 11.sp,
                color = textSecondary,
                fontFamily = BitchatFontFamily
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(ClassicColors.Teal.copy(alpha = 0.14f))
                    .padding(vertical = 5.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = null,
                    tint = ClassicColors.Teal,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "標準マップアプリで開く",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ClassicColors.Teal,
                    fontFamily = BitchatFontFamily
                )
            }
        }
    }
}

