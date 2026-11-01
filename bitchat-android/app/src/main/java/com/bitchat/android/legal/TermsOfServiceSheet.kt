package com.bitchat.android.legal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.ClassicColors

/**
 * Full-screen dialog displaying nemix Purpose Declaration, 3 Core Challenges,
 * Terms of Service, and Privacy Policy.
 */
@Composable
fun TermsOfServiceDialog(
    onDismiss: () -> Unit,
    showAcceptButton: Boolean = false,
    onAccept: (() -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            color = colorScheme.background,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ClassicColors.Teal)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Gavel,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "nemix 利用規約・プライバシーポリシー",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        fontFamily = BitchatFontFamily,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "閉じる",
                            tint = Color.White
                        )
                    }
                }

                // Body
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Short Declaration Card
                    item {
                        Surface(
                            color = ClassicColors.Teal.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Shield,
                                        contentDescription = null,
                                        tint = ClassicColors.Teal,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "アプリのミッション（基本宣言）",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = ClassicColors.Teal,
                                        fontFamily = BitchatFontFamily
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = TermsOfServiceManager.SHORT_DECLARATION,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = colorScheme.onSurface,
                                    fontFamily = BitchatFontFamily
                                )
                            }
                        }
                    }

                    // 3 Core Challenges
                    item {
                        Surface(
                            color = colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "本プロジェクトが解決する3つの課題",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = colorScheme.primary,
                                    fontFamily = BitchatFontFamily
                                )
                                TermsOfServiceManager.THREE_CORE_CHALLENGES.forEach { challenge ->
                                    Column {
                                        Text(
                                            text = challenge.title,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = colorScheme.onSurface,
                                            fontFamily = BitchatFontFamily
                                        )
                                        Text(
                                            text = challenge.description,
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp,
                                            color = colorScheme.onSurfaceVariant,
                                            fontFamily = BitchatFontFamily
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Articles
                    items(TermsOfServiceManager.ARTICLES) { article ->
                        Surface(
                            color = colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = article.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = colorScheme.onSurface,
                                    fontFamily = BitchatFontFamily
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = article.body,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = colorScheme.onSurfaceVariant,
                                    fontFamily = BitchatFontFamily
                                )
                            }
                        }
                    }
                }

                // Footer button
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (showAcceptButton && onAccept != null) {
                        TextButton(onClick = onDismiss) {
                            Text("閉じる", fontFamily = BitchatFontFamily)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onAccept,
                            colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.Teal)
                        ) {
                            Text("同意して閉じる", color = Color.White, fontFamily = BitchatFontFamily)
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.Teal)
                        ) {
                            Text("閉じる", color = Color.White, fontFamily = BitchatFontFamily)
                        }
                    }
                }
            }
        }
    }
}

