package com.bitchat.android.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.groups.P2PGroup
import com.bitchat.android.groups.P2PGroupManager
import com.bitchat.android.groups.P2PGroupStyle
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.ClassicColors

/**
 * Dialog for creating a 100% P2P Encrypted Group (choosing Stream format or Channel format)
 * or joining an existing P2P Group via an encrypted invite code.
 */
@Composable
fun CreateOrJoinGroupDialog(
    myNickname: String,
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    onGroupReady: (P2PGroup) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var modeTab by remember { mutableIntStateOf(0) } // 0 = Create, 1 = Join by Invite Code

    var groupName by remember { mutableStateOf("") }
    var groupDescription by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableStateOf(P2PGroupStyle.STREAM) }
    var screenshotBlocked by remember { mutableStateOf(false) }
    val selectedMembers = remember { mutableStateListOf<String>() }

    var inviteCodeInput by remember { mutableStateOf("") }

    val candidateMembers = remember(connectedPeers, peerNicknames) {
        val favs = runCatching {
            FavoritesPersistenceService.shared.getOurFavorites().map { rel ->
                ContactIdentityResolver.contactConversationIdForNoiseKey(rel.peerNoisePublicKey) to rel.peerNickname
            }
        }.getOrDefault(emptyList())
        val nearby = connectedPeers.map { pid -> pid to (peerNicknames[pid] ?: pid.take(8)) }
        (favs + nearby).distinctBy { it.first }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "完全P2P暗号化グループ",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    fontFamily = BitchatFontFamily
                )
                Spacer(modifier = Modifier.height(8.dp))
                TabRow(selectedTabIndex = modeTab) {
                    Tab(
                        selected = modeTab == 0,
                        onClick = { modeTab = 0 },
                        text = { Text("新規作成", fontSize = 13.sp) }
                    )
                    Tab(
                        selected = modeTab == 1,
                        onClick = { modeTab = 1 },
                        text = { Text("招待コードで参加", fontSize = 13.sp) }
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (modeTab == 0) {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("グループ名") },
                        placeholder = { Text("例: 防災ネットワーク / 開発サーバー") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = groupDescription,
                        onValueChange = { groupDescription = it },
                        label = { Text("説明・初期お知らせ (任意)") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "グループUIスタイルを選択（作成後も切替可能）",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )

                    // Style 1: Stream Message Style
                    GroupStyleOptionCard(
                        selected = selectedStyle == P2PGroupStyle.STREAM,
                        accent = Color(0xFF2481CC),
                        icon = Icons.Filled.ChatBubbleOutline,
                        title = "ストリーム形式（単一メッセージライン）",
                        subtitle = "シンプルな単一タイムラインの吹き出しチャット。ピン留めお知らせ・返信・高速共有に最適",
                        onClick = { selectedStyle = P2PGroupStyle.STREAM }
                    )

                    // Style 2: Topic & Channels Style
                    GroupStyleOptionCard(
                        selected = selectedStyle == P2PGroupStyle.CHANNELS,
                        accent = Color(0xFF5865F2),
                        icon = Icons.Filled.Forum,
                        title = "チャンネル形式（トピック別・複数チャンネル）",
                        subtitle = "#一般・#お知らせ・#写真など話題ごとのトピックとロール(OWNER/MEMBER)で綺麗に整理",
                        onClick = { selectedStyle = P2PGroupStyle.CHANNELS }
                    )

                    // Mutual Screenshot Protection Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { screenshotBlocked = !screenshotBlocked }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ScreenLockPortrait,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("スクショ禁止（参加者全員に適用）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "ONにすると参加者全員の画面キャプチャを禁止します",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = screenshotBlocked, onCheckedChange = { screenshotBlocked = it })
                    }

                    if (candidateMembers.isNotEmpty()) {
                        Text(
                            text = "初期招待メンバー (${selectedMembers.size}人選択中)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        candidateMembers.forEach { (peerId, name) ->
                            val checked = selectedMembers.contains(peerId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (checked) selectedMembers.remove(peerId)
                                        else selectedMembers.add(peerId)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        if (isChecked) selectedMembers.add(peerId)
                                        else selectedMembers.remove(peerId)
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(name, fontSize = 14.sp)
                            }
                        }
                    }
                } else {
                    Text(
                        text = "相手から受け取ったP2Pグループ招待コード (nemix://group?d=...) を貼り付けてください。中央サーバーを使わずに直接グループ暗号鍵を共有して参加します。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = inviteCodeInput,
                        onValueChange = { inviteCodeInput = it },
                        label = { Text("P2P招待コード") },
                        placeholder = { Text("nemix://group?d=...") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (modeTab == 0) {
                Button(
                    onClick = {
                        if (groupName.trim().isEmpty()) {
                            Toast.makeText(context, "グループ名を入力してください", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val created = P2PGroupManager.createGroup(
                            context = context,
                            name = groupName,
                            description = groupDescription,
                            style = selectedStyle,
                            screenshotBlocked = screenshotBlocked,
                            myNickname = myNickname,
                            initialPeerTargets = selectedMembers.toList()
                        )
                        onGroupReady(created)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.AccentGreen)
                ) {
                    Text("P2Pグループを作成", color = Color.White)
                }
            } else {
                Button(
                    onClick = {
                        val res = P2PGroupManager.joinGroupFromInviteCode(
                            context = context,
                            rawInvite = inviteCodeInput,
                            myNickname = myNickname
                        )
                        res.onSuccess { joined ->
                            Toast.makeText(context, "「${joined.name}」に参加しました", Toast.LENGTH_SHORT).show()
                            onGroupReady(joined)
                        }.onFailure { err ->
                            Toast.makeText(context, err.message ?: "参加できませんでした", Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text("参加する")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@Composable
private fun GroupStyleOptionCard(
    selected: Boolean,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) accent else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp).padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

