package com.bitchat.android.profile

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.legal.TermsOfServiceDialog
import com.bitchat.android.legal.TermsOfServiceManager
import com.bitchat.android.net.InternetRelayPreferences
import com.bitchat.android.service.MeshServicePreferences
import com.bitchat.android.ui.theme.AppUiStyle
import com.bitchat.android.ui.theme.AppUiStyleManager
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.ClassicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-launch & editable Profile & Experience Setup Dialog for nemix:
 * - Pick & preview tightly compressed Avatar Icon (~2-5 KB WebP, EXIF stripped)
 * - Set Display Name
 * - Select UI Style: クラシック形式 vs チャンネル形式
 * - Select Nearby Visibility: 周辺に自分を公開する vs 公開しない（QR・UUID追加のみ）
 * - Review & accept Terms of Service on first launch
 */
@Composable
fun ProfileSetupDialog(
    initialNickname: String,
    isFirstLaunch: Boolean,
    onSaveProfile: (newNickname: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    val existingBitmap by UserProfileManager.myAvatarBitmap.collectAsStateWithLifecycle()
    val existingSize by UserProfileManager.myAvatarSizeBytes.collectAsStateWithLifecycle()
    val hasAcceptedTosAlready by TermsOfServiceManager.acceptedFlow.collectAsStateWithLifecycle()
    val currentUiStyle by AppUiStyleManager.styleFlow.collectAsStateWithLifecycle()
    val currentNearbyVisible by MeshServicePreferences.nearbyVisibleFlow.collectAsStateWithLifecycle()
    val currentMeshBridge by InternetRelayPreferences.meshBridgeEnabled.collectAsStateWithLifecycle()

    var nameInput by remember(initialNickname) {
        mutableStateOf(
            if (initialNickname.startsWith("anon", ignoreCase = true) && isFirstLaunch) ""
            else initialNickname
        )
    }
    var selectedUiStyle by remember(currentUiStyle) { mutableStateOf(currentUiStyle) }
    var selectedNearbyVisible by remember(currentNearbyVisible) { mutableStateOf(currentNearbyVisible) }
    var selectedMeshBridge by remember(currentMeshBridge) { mutableStateOf(currentMeshBridge) }
    var pendingCompressedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var avatarCleared by remember { mutableStateOf(false) }
    var isCompressing by remember { mutableStateOf(false) }
    var tosChecked by remember(hasAcceptedTosAlready) { mutableStateOf(hasAcceptedTosAlready) }
    var showTosDialog by remember { mutableStateOf(false) }

    val previewBitmap = remember(pendingCompressedBytes, existingBitmap, avatarCleared) {
        when {
            avatarCleared -> null
            pendingCompressedBytes != null -> BitmapFactory.decodeByteArray(
                pendingCompressedBytes,
                0,
                pendingCompressedBytes!!.size
            )
            else -> existingBitmap
        }
    }

    val previewBytesCount = when {
        avatarCleared -> 0
        pendingCompressedBytes != null -> pendingCompressedBytes!!.size
        else -> existingSize
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            isCompressing = true
            scope.launch {
                val compressed = withContext(Dispatchers.IO) {
                    UserProfileManager.compressAvatarFromUri(context, uri)
                }
                isCompressing = false
                if (compressed != null) {
                    pendingCompressedBytes = compressed
                    avatarCleared = false
                } else {
                    Toast.makeText(context, "画像の読み込みに失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showTosDialog) {
        TermsOfServiceDialog(
            onDismiss = { showTosDialog = false },
            showAcceptButton = !tosChecked,
            onAccept = {
                tosChecked = true
                showTosDialog = false
            }
        )
    }

    Dialog(
        onDismissRequest = { if (!isFirstLaunch) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isFirstLaunch,
            dismissOnClickOutside = !isFirstLaunch,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            shape = RoundedCornerShape(22.dp),
            color = colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isFirstLaunch) "nemix 初期セットアップ" else "プロフィール・表示設定",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface,
                    fontFamily = BitchatFontFamily
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (isFirstLaunch) {
                        "表示名・アイコン・UIデザイン・周辺への公開設定を選びましょう（後からいつでも変更できます）。"
                    } else {
                        "名前・アイコン・UIデザイン・周辺への公開範囲をいつでも変更できます。"
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    fontFamily = BitchatFontFamily
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Circular Avatar Picker
                Box(
                    modifier = Modifier.size(92.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(86.dp)
                            .clip(CircleShape)
                            .background(ClassicColors.Teal)
                            .border(2.dp, ClassicColors.Teal.copy(alpha = 0.4f), CircleShape)
                            .clickable { imagePickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isCompressing -> {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(26.dp),
                                    strokeWidth = 2.5.dp
                                )
                            }
                            previewBitmap != null -> {
                                Image(
                                    bitmap = previewBitmap.asImageBitmap(),
                                    contentDescription = "プロフィールアイコン",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            else -> {
                                val initial = nameInput.trim().take(1).uppercase()
                                if (initial.isNotEmpty()) {
                                    Text(
                                        text = initial,
                                        color = Color.White,
                                        fontSize = 34.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = BitchatFontFamily
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Person,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(ClassicColors.AccentGreen)
                            .border(2.dp, colorScheme.surface, CircleShape)
                            .clickable { imagePickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = "画像を選択",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (previewBitmap != null && previewBytesCount > 0) {
                    val kbText = "%.1f KB".format(previewBytesCount / 1024f)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = ClassicColors.Teal.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "軽量圧縮済: $kbText",
                                fontSize = 11.sp,
                                color = ClassicColors.Teal,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                fontFamily = BitchatFontFamily
                            )
                        }
                        IconButton(
                            onClick = {
                                pendingCompressedBytes = null
                                avatarCleared = true
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "アイコンを削除",
                                tint = colorScheme.error,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                } else {
                    TextButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "アイコン画像を選択（任意）",
                            fontSize = 12.sp,
                            color = ClassicColors.Teal,
                            fontFamily = BitchatFontFamily
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Display Name input
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { if (it.length <= 24) nameInput = it },
                    label = { Text("表示名（ニックネーム）", fontFamily = BitchatFontFamily) },
                    placeholder = { Text("例: たろう", fontFamily = BitchatFontFamily) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ClassicColors.Teal,
                        focusedLabelColor = ClassicColors.Teal,
                        cursorColor = ClassicColors.Teal
                    )
                )

                Spacer(modifier = Modifier.height(14.dp))

                // P2P Gateway / Relay Node Setup (With Clear Pros & Cons)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "P2P 遠隔リレー / メッシュ中継ノード機能",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface,
                        fontFamily = BitchatFontFamily
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "中央サーバーを介さず、インターネット接続時にオフライン端末同士の暗号化パケットを遠隔中継する自律ノード機能です。",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = colorScheme.onSurfaceVariant,
                        fontFamily = BitchatFontFamily
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SetupChoiceCard(
                            icon = Icons.Filled.Share,
                            title = "リレー参加 (ON)",
                            subtitle = "遠隔中継を有効化（推奨）",
                            selected = selectedMeshBridge,
                            accentColor = ClassicColors.Teal,
                            onClick = { selectedMeshBridge = true },
                            modifier = Modifier.weight(1f)
                        )
                        SetupChoiceCard(
                            icon = Icons.Filled.Close,
                            title = "通常モード (OFF)",
                            subtitle = "中継を行わず自端末のみ利用",
                            selected = !selectedMeshBridge,
                            accentColor = Color(0xFF78909C),
                            onClick = { selectedMeshBridge = false },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Pros & Cons Explanatory Card
                    Surface(
                        color = colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "【メリット】",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ClassicColors.Teal,
                                    fontFamily = BitchatFontFamily
                                )
                            }
                            Text(
                                text = "・近くにいなくても届く: Bluetoothが届かない遠方の相手ともネット経由で安全にP2P通信可能\n・ネットワーク貢献: 被災時など周囲のオフライン端末をゼロ知識で支援し分散網を強化",
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp,
                                color = colorScheme.onSurface,
                                fontFamily = BitchatFontFamily
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "【留意点（デメリット）】",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFD32F2F),
                                    fontFamily = BitchatFontFamily
                                )
                            }
                            Text(
                                text = "・バッテリー消費: P2P接続の維持により通常時よりわずかにバッテリーを消費\n・通信量: Wi-Fi未接続時に微量のモバイル通信を消費（設定でWi-Fi時限定も可能）",
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp,
                                color = colorScheme.onSurfaceVariant,
                                fontFamily = BitchatFontFamily
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Nearby Visibility Picker: 周辺に公開 vs 公開しない (QR / UUIDのみ)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "周辺への公開設定",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface,
                        fontFamily = BitchatFontFamily
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SetupChoiceCard(
                            icon = Icons.Filled.Visibility,
                            title = "周辺に公開する",
                            subtitle = "近くの端末と自動で発見・メッシュ接続",
                            selected = selectedNearbyVisible,
                            accentColor = ClassicColors.Teal,
                            onClick = { selectedNearbyVisible = true },
                            modifier = Modifier.weight(1f)
                        )
                        SetupChoiceCard(
                            icon = Icons.Filled.VisibilityOff,
                            title = "公開しない",
                            subtitle = "ステルス（QR・UUID追加のみ）",
                            selected = !selectedNearbyVisible,
                            accentColor = Color(0xFF455A64),
                            onClick = { selectedNearbyVisible = false },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Terms of Service Acceptance on First Launch
                if (isFirstLaunch || !hasAcceptedTosAlready) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = TermsOfServiceManager.SHORT_DECLARATION,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = colorScheme.onSurfaceVariant,
                                fontFamily = BitchatFontFamily
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = tosChecked,
                                    onCheckedChange = { tosChecked = it },
                                    colors = CheckboxDefaults.colors(checkedColor = ClassicColors.Teal)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "利用規約・プライバシーポリシーに同意する",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colorScheme.onSurface,
                                        fontFamily = BitchatFontFamily
                                    )
                                    Text(
                                        text = "全文を確認する（電波法・航空法・禁止事項・免責）",
                                        fontSize = 11.sp,
                                        color = ClassicColors.Teal,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clickable { showTosDialog = true },
                                        fontFamily = BitchatFontFamily
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val finalName = nameInput.trim().ifEmpty {
                    initialNickname.ifEmpty { "nemix_${(1000..9999).random()}" }
                }
                val canSubmit = !isCompressing && (tosChecked || (!isFirstLaunch && hasAcceptedTosAlready))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (!isFirstLaunch) {
                        TextButton(onClick = onDismiss) {
                            Text("キャンセル", fontFamily = BitchatFontFamily)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Button(
                        onClick = {
                            if (avatarCleared) {
                                UserProfileManager.saveMyAvatar(context, null)
                            } else if (pendingCompressedBytes != null) {
                                UserProfileManager.saveMyAvatar(context, pendingCompressedBytes)
                            }
                            AppUiStyleManager.set(context, selectedUiStyle)
                            MeshServicePreferences.setNearbyVisible(selectedNearbyVisible)
                            InternetRelayPreferences.setMeshBridgeEnabled(context, selectedMeshBridge)
                            UserProfileManager.notifyNicknameChanged()
                            UserProfileManager.markProfileSetupCompleted(context)
                            TermsOfServiceManager.acceptTerms(context)
                            onSaveProfile(finalName)
                        },
                        enabled = canSubmit,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedUiStyle == AppUiStyle.CHANNELS) Color(0xFF5865F2) else ClassicColors.Teal
                        ),
                        modifier = if (isFirstLaunch) Modifier.fillMaxWidth() else Modifier
                    ) {
                        Text(
                            text = if (isFirstLaunch) "nemix をはじめる" else "保存する",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = BitchatFontFamily
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupChoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accentColor.copy(alpha = 0.14f) else colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = if (selected) accentColor else colorScheme.outline.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) accentColor else colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) accentColor else colorScheme.onSurface,
                    fontFamily = BitchatFontFamily
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = colorScheme.onSurfaceVariant,
                fontFamily = BitchatFontFamily
            )
        }
    }
}
