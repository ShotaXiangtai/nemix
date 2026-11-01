package com.bitchat.android.call

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.profile.UserProfileManager
import com.bitchat.android.ui.ClassicAvatar
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.ClassicColors

/**
 * Dialog shown when user taps the Call button in a 1-on-1 chat, allowing them to choose
 * between Local P2P Wireless (BLE/Wi-Fi Aware) and Internet Relay E2EE calling.
 */
@Composable
fun CallTransportPickerDialog(
    peerName: String,
    hasLocalRoute: Boolean,
    hasInternetRoute: Boolean,
    onSelectMode: (CallTransportMode) -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "$peerName に無線発信",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                fontFamily = BitchatFontFamily
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "通信経路を選択してください（すべてエンドツーエンド暗号化されます）:",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant,
                    fontFamily = BitchatFontFamily
                )

                // Option 1: Local Wireless P2P
                TransportOptionCard(
                    icon = Icons.Filled.WifiTethering,
                    title = "近距離無線通話 (ローカル P2P)",
                    subtitle = if (hasLocalRoute) {
                        "利用可能 · サーバー不要で直接接続中"
                    } else {
                        "圏外 · 相手が近くにいません"
                    },
                    available = hasLocalRoute,
                    accentColor = ClassicColors.Teal,
                    onClick = { onSelectMode(CallTransportMode.LOCAL_WIRELESS) }
                )

                // Option 2: Internet Relay E2EE
                TransportOptionCard(
                    icon = Icons.Filled.Public,
                    title = "ネット経由通話 (オンライン暗号化)",
                    subtitle = if (hasInternetRoute) {
                        "利用可能 · リレー / Tor経由で遠距離通話"
                    } else {
                        "鍵未交換 · QRコード読取またはお気に入り登録で有効化"
                    },
                    available = hasInternetRoute,
                    accentColor = Color(0xFF1976D2),
                    onClick = { onSelectMode(CallTransportMode.INTERNET_RELAY) }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル", fontFamily = BitchatFontFamily)
            }
        }
    )
}

@Composable
private fun TransportOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    available: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (available) accentColor.copy(alpha = 0.12f) else colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = available, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (available) accentColor else Color.Gray.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = if (available) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                    fontFamily = BitchatFontFamily
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = if (available) accentColor else colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontFamily = BitchatFontFamily
                )
            }
            if (available) {
                Icon(
                    imageVector = Icons.Filled.Call,
                    contentDescription = null,
                    tint = accentColor
                )
            }
        }
    }
}

/**
 * Full-screen Classic-format Wireless Voice Call Overlay rendered whenever a call is ringing or active.
 */
@Composable
fun WirelessCallOverlay(myNickname: String) {
    val context = LocalContext.current
    val state by WirelessCallManager.uiState.collectAsStateWithLifecycle()
    val peerAvatars by UserProfileManager.peerAvatars.collectAsStateWithLifecycle()

    if (state.phase == WirelessCallPhase.IDLE) return

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            WirelessCallManager.acceptIncomingCall(context, myNickname)
        } else {
            Toast.makeText(context, "通話にはマイク権限が必要です", Toast.LENGTH_SHORT).show()
            WirelessCallManager.endCall(context)
        }
    }

    val peerAvatar = remember(peerAvatars, state.peerID, state.peerName) {
        UserProfileManager.getPeerAvatar(peerAvatars, state.peerID, state.peerName)
    }

    Dialog(
        onDismissRequest = { /* Keep call screen active until explicit hangup */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF073B34),
                            Color(0xFF0B141A)
                        )
                    )
                )
                .systemBarsPadding()
                .padding(24.dp)
        ) {
            // Top Info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = ClassicColors.AccentGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "E2EE暗号化 · ${state.transportMode.displayLabel}",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontFamily = BitchatFontFamily
                    )
                }

                Spacer(modifier = Modifier.height(36.dp))

                ClassicAvatar(
                    name = state.peerName,
                    size = 112.dp,
                    backgroundColor = ClassicColors.Teal,
                    avatarBitmap = peerAvatar
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = state.peerName.ifBlank { state.peerID.take(12) },
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = BitchatFontFamily
                )

                Spacer(modifier = Modifier.height(8.dp))

                val statusText = when (state.phase) {
                    WirelessCallPhase.OUTGOING_RINGING -> "呼び出し中..."
                    WirelessCallPhase.INCOMING_RINGING -> "着信中..."
                    WirelessCallPhase.ACTIVE -> {
                        val m = state.durationSeconds / 60
                        val s = state.durationSeconds % 60
                        String.format("%02d:%02d", m, s)
                    }
                    WirelessCallPhase.IDLE -> ""
                }
                Text(
                    text = statusText,
                    color = ClassicColors.AccentGreen,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = BitchatFontFamily
                )
            }

            // Bottom Call Controls
            Surface(
                color = Color(0xFF182229),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                if (state.phase == WirelessCallPhase.INCOMING_RINGING) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 22.dp, horizontal = 32.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Decline
                        CallCircleButton(
                            icon = Icons.Filled.CallEnd,
                            label = "拒否",
                            backgroundColor = Color(0xFFE53935),
                            onClick = { WirelessCallManager.endCall(context) }
                        )
                        // Accept
                        CallCircleButton(
                            icon = Icons.Filled.Call,
                            label = "応答",
                            backgroundColor = ClassicColors.AccentGreen,
                            onClick = {
                                if (WirelessCallManager.hasAudioPermission(context)) {
                                    WirelessCallManager.acceptIncomingCall(context, myNickname)
                                } else {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp, horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Speakerphone
                        CallCircleButton(
                            icon = if (state.isSpeakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                            label = "スピーカー",
                            backgroundColor = if (state.isSpeakerOn) Color.White else Color.White.copy(alpha = 0.14f),
                            iconTint = if (state.isSpeakerOn) Color.Black else Color.White,
                            sizeDp = 54,
                            onClick = { WirelessCallManager.toggleSpeaker(context) }
                        )
                        // Mute
                        CallCircleButton(
                            icon = if (state.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            label = if (state.isMuted) "消音中" else "消音",
                            backgroundColor = if (state.isMuted) Color.White else Color.White.copy(alpha = 0.14f),
                            iconTint = if (state.isMuted) Color.Black else Color.White,
                            sizeDp = 54,
                            onClick = { WirelessCallManager.toggleMute() }
                        )
                        // Hangup
                        CallCircleButton(
                            icon = Icons.Filled.CallEnd,
                            label = "終了",
                            backgroundColor = Color(0xFFE53935),
                            sizeDp = 62,
                            onClick = { WirelessCallManager.endCall(context) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallCircleButton(
    icon: ImageVector,
    label: String,
    backgroundColor: Color,
    iconTint: Color = Color.White,
    sizeDp: Int = 64,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(backgroundColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size((sizeDp * 0.45f).dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 11.sp,
            fontFamily = BitchatFontFamily
        )
    }
}

