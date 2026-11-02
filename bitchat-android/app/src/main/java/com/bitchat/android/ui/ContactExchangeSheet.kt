package com.bitchat.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.contact.ContactExchangeManager
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.ClassicColors
import java.text.SimpleDateFormat
import java.util.*

/**
 * 100% P2P Serverless Contact Exchange Sheet via QR Code & URL:
 * - Permanent QR/URL (with instant revocation & reissue button)
 * - Expiring QR/URL (10 min / 1 hr / 24 hr)
 * - Deep Link URI (nemix://contact/add) & Web Share URL (https://nemi-x.com/c)
 * - QR Scanner & URL manual input importer
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactExchangeSheet(
    onDismiss: () -> Unit,
    onContactAdded: (peerId: String) -> Unit = {},
    initialInviteUrl: String? = null
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    var activeTab by remember { mutableStateOf(if (initialInviteUrl != null) 1 else 0) } // 0: Share My Contact, 1: Import Contact
    var selectedType by remember { mutableStateOf(ContactExchangeManager.InviteType.PERMANENT) }
    var selectedDurationMs by remember { mutableStateOf(3600_000L) } // 1 hour default
    var showRevokeConfirmDialog by remember { mutableStateOf(false) }

    // Manual input state
    var manualInputUrl by remember { mutableStateOf(initialInviteUrl.orEmpty()) }
    var parsedPayload by remember { mutableStateOf<ContactExchangeManager.ContactInvitePayload?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }

    fun validateManualInput() {
        when (val result = ContactExchangeManager.validateAndParse(manualInputUrl)) {
            is ContactExchangeManager.ValidationResult.Valid -> {
                parsedPayload = result.payload
                parseError = null
            }
            is ContactExchangeManager.ValidationResult.Expired -> {
                val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                parseError = "この招待リンクは有効期限が切れています (${sdf.format(Date(result.expiredAtMs))})"
                parsedPayload = null
            }
            is ContactExchangeManager.ValidationResult.Revoked -> {
                parseError = "この招待リンクは相手側による再発行のため無効化されています"
                parsedPayload = null
            }
            is ContactExchangeManager.ValidationResult.Error -> {
                parseError = result.message
                parsedPayload = null
            }
        }
    }

    // A tapped invite link opens this sheet pre-filled: validate it right away.
    LaunchedEffect(initialInviteUrl) {
        if (!initialInviteUrl.isNullOrBlank()) validateManualInput()
    }

    val permanentSalt by ContactExchangeManager.permanentSalt.collectAsStateWithLifecycle()

    // Regenerate current invite when type, duration, or salt changes
    val currentInvite = remember(selectedType, selectedDurationMs, permanentSalt) {
        ContactExchangeManager.createInvite(context, selectedType, selectedDurationMs)
    }

    val nemixUri = remember(currentInvite) {
        ContactExchangeManager.payloadToNemixUri(currentInvite)
    }

    val webUrl = remember(currentInvite) {
        ContactExchangeManager.payloadToWebUrl(currentInvite)
    }

    val qrBitmap = remember(nemixUri) {
        ContactExchangeManager.generateQrBitmap(nemixUri, sizePx = 480)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "QR・URLで連絡先交換",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface,
                    fontFamily = BitchatFontFamily
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "閉じる")
                }
            }

            // Top Segmented Bar: 自分のQR・URL / 相手のQR・URLを追加
            TabRow(
                selectedTabIndex = activeTab,
                containerColor = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = ClassicColors.Teal,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = {
                        Text(
                            text = "自分のQR・URL",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = BitchatFontFamily
                        )
                    }
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = {
                        Text(
                            text = "相手を追加",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = BitchatFontFamily
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (activeTab == 0) {
                // Mode 0: Share My Contact
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedType == ContactExchangeManager.InviteType.PERMANENT,
                        onClick = { selectedType = ContactExchangeManager.InviteType.PERMANENT },
                        label = { Text("永久リンク (再発行可)", fontSize = 12.sp, fontFamily = BitchatFontFamily) },
                        leadingIcon = {
                            if (selectedType == ContactExchangeManager.InviteType.PERMANENT) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ClassicColors.Teal.copy(alpha = 0.15f),
                            selectedLabelColor = ClassicColors.Teal
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedType == ContactExchangeManager.InviteType.EXPIRING,
                        onClick = { selectedType = ContactExchangeManager.InviteType.EXPIRING },
                        label = { Text("時間制限付き", fontSize = 12.sp, fontFamily = BitchatFontFamily) },
                        leadingIcon = {
                            if (selectedType == ContactExchangeManager.InviteType.EXPIRING) {
                                Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ClassicColors.Teal.copy(alpha = 0.15f),
                            selectedLabelColor = ClassicColors.Teal
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (selectedType == ContactExchangeManager.InviteType.EXPIRING) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            600_000L to "10分",
                            3600_000L to "1時間",
                            86400_000L to "24時間"
                        ).forEach { (ms, label) ->
                            SuggestionChip(
                                onClick = { selectedDurationMs = ms },
                                label = { Text(label, fontSize = 11.sp, fontFamily = BitchatFontFamily) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = if (selectedDurationMs == ms) ClassicColors.Teal.copy(alpha = 0.2f) else colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    labelColor = if (selectedDurationMs == ms) ClassicColors.Teal else colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // QR Code Frame
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .size(230.dp)
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "連絡先QRコード",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                            )
                        } else {
                            CircularProgressIndicator(color = ClassicColors.Teal)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Validity Status Badge
                Surface(
                    color = if (selectedType == ContactExchangeManager.InviteType.PERMANENT) {
                        ClassicColors.Teal.copy(alpha = 0.12f)
                    } else {
                        Color(0xFFE65100).copy(alpha = 0.12f)
                    },
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (selectedType == ContactExchangeManager.InviteType.PERMANENT) Icons.Filled.Lock else Icons.Filled.AccessTime,
                            contentDescription = null,
                            tint = if (selectedType == ContactExchangeManager.InviteType.PERMANENT) ClassicColors.Teal else Color(0xFFE65100),
                            modifier = Modifier.size(13.dp)
                        )
                        val statusText = if (selectedType == ContactExchangeManager.InviteType.PERMANENT) {
                            "永久有効（いつでも再発行で無効化可能）"
                        } else {
                            val sdf = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
                            "有効期限: ${sdf.format(Date(currentInvite.expiresAtMs))} まで"
                        }
                        Text(
                            text = statusText,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selectedType == ContactExchangeManager.InviteType.PERMANENT) ClassicColors.Teal else Color(0xFFE65100),
                            fontFamily = BitchatFontFamily
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Copy & Share URL Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("nemix Invite", webUrl))
                            Toast.makeText(context, "招待URLをコピーしました", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ClassicColors.Teal)
                    ) {
                        Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("URLをコピー", fontSize = 12.sp, fontFamily = BitchatFontFamily)
                    }

                    Button(
                        onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, "nemixで連絡先を追加しましょう！\n$webUrl")
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "nemix 連絡先URLを共有"))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.Teal)
                    ) {
                        Icon(imageVector = Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("URLを共有", fontSize = 12.sp, fontFamily = BitchatFontFamily)
                    }
                }

                if (selectedType == ContactExchangeManager.InviteType.PERMANENT) {
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(
                        onClick = { showRevokeConfirmDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error)
                    ) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("リンク・QRコードを再発行する（以前のリンクを無効化）", fontSize = 11.5.sp, fontFamily = BitchatFontFamily)
                    }
                }
            } else {
                // Mode 1: Import Contact from Friend's QR or URL
                Surface(
                    color = colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "友人の招待コード / URLを入力",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = colorScheme.onSurface,
                            fontFamily = BitchatFontFamily
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualInputUrl,
                            onValueChange = {
                                manualInputUrl = it
                                parseError = null
                                parsedPayload = null
                            },
                            placeholder = { Text("nemix://contact/add?... または https://nemi-x.com/c#...", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ClassicColors.Teal,
                                focusedLabelColor = ClassicColors.Teal,
                                cursorColor = ClassicColors.Teal
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { validateManualInput() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.Teal),
                            enabled = manualInputUrl.isNotBlank()
                        ) {
                            Text("URLを確認して検証", fontSize = 13.sp, fontFamily = BitchatFontFamily)
                        }
                    }
                }

                if (parseError != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = colorScheme.errorContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = colorScheme.error)
                            Text(
                                text = parseError ?: "",
                                color = colorScheme.onErrorContainer,
                                fontSize = 12.sp,
                                fontFamily = BitchatFontFamily
                            )
                        }
                    }
                }

                if (parsedPayload != null) {
                    val p = parsedPayload!!
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        color = ClassicColors.Teal.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ClassicColors.Teal.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("連絡先が見つかりました", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = ClassicColors.Teal)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("名前: ${p.nickname}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            if (p.uuid.isNotBlank()) {
                                Text("UUID: ${p.uuid.take(8)}...${p.uuid.takeLast(4)}", fontSize = 11.5.sp, color = colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    val success = ContactExchangeManager.registerContactFromPayload(context, p)
                                    if (success) {
                                        Toast.makeText(context, "${p.nickname} を連絡先に追加しました", Toast.LENGTH_SHORT).show()
                                        val peerId = p.edPublicKeyHex.ifBlank { p.nostrPubkeyHex }
                                        onContactAdded(peerId)
                                        onDismiss()
                                    } else {
                                        Toast.makeText(context, "追加に失敗しました", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.Teal)
                            ) {
                                Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("連絡先に追加してチャットを開始", fontSize = 13.sp, fontFamily = BitchatFontFamily)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Confirmation dialog for reissuing permanent invite
    if (showRevokeConfirmDialog) {
        KeyboardSafeAlertDialog(
            onDismissRequest = { showRevokeConfirmDialog = false },
            title = { Text("永久リンクを再発行しますか？", fontWeight = FontWeight.Bold, fontFamily = BitchatFontFamily) },
            text = {
                Text(
                    text = "再発行すると、これまでに共有したQRコードや招待URLはすべて即座に無効化されます。\n新しく発行されたURL・QRコードのみ追加可能になります。",
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    fontFamily = BitchatFontFamily
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        ContactExchangeManager.regeneratePermanentInvite(context)
                        showRevokeConfirmDialog = false
                        Toast.makeText(context, "新しい永久リンクを発行しました（旧リンクは無効化）", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error)
                ) {
                    Text("再発行する", fontFamily = BitchatFontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirmDialog = false }) {
                    Text("キャンセル", fontFamily = BitchatFontFamily)
                }
            }
        )
    }
}

