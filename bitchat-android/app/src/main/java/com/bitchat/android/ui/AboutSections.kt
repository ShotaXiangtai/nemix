package com.bitchat.android.ui

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.legal.TermsOfServiceManager
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette

/**
 * Building blocks for the redesigned About sheet.
 *
 * Kept in a separate file from [AboutSheet] because the sheet itself is mostly wiring for
 * preferences, whereas these are pure presentation.
 */

/** Horizontal inset shared by every About section, so cards and labels align to one grid. */
internal val AboutHorizontalPadding = 20.dp

/** Card corner radius for grouped rows. */
internal val AboutCardShape = RoundedCornerShape(16.dp)

/** Leading icon column in settings-style sheet rows. */
internal val SheetRowLeadingSlot = 22.dp
internal val SheetRowLeadingGutter = 16.dp
internal val SheetRowHorizontal = 16.dp
internal val SheetRowVertical = 13.dp

/**
 * Exact height of a people-list row.
 */
internal val SheetRowHeight = SheetRowLeadingSlot + SheetRowVertical * 2
internal val SheetRowDividerInset = SheetRowHorizontal + SheetRowLeadingSlot + SheetRowLeadingGutter
/** Selection indicator sized for [SheetRowLeadingSlot]. */
internal val SheetRowSelectedDot = 12.dp

internal const val DEVELOPER_GITHUB_URL = "https://github.com/ShotaXiangtai"

/**
 * Two top-level views of the sheet: what the app is and how to drive it, versus the knobs.
 */
enum class AboutTab {
    Info,
    Settings,
}

/**
 * Small uppercase section label, e.g. `SETTINGS`.
 */
@Composable
internal fun AboutSectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    Text(
        text = text.uppercase(),
        fontFamily = BitchatFontFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = palette.textTertiary,
        modifier = modifier.padding(start = AboutHorizontalPadding, top = 24.dp, bottom = 8.dp)
    )
}

/**
 * Icon + title on one line, optional short subtitle beneath. Used by location / network sheets.
 */
@Composable
internal fun SheetIconSectionHeader(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                fontSize = 17.sp,
                fontFamily = BitchatFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.primary
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = BitchatFontFamily,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Inset divider used inside grouped sheet cards (aligns with text column after the leading slot). */
@Composable
internal fun SheetCardDivider() {
    val colorScheme = MaterialTheme.colorScheme
    HorizontalDivider(
        modifier = Modifier.padding(start = SheetRowDividerInset),
        thickness = 1.dp,
        color = colorScheme.outlineVariant
    )
}

/**
 * Centered app identity block: logo, wordmark, tagline, version.
 */
@Composable
internal fun AboutHero(
    versionName: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.ic_nemix_logo),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.app_name),
            fontFamily = BitchatFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            letterSpacing = (-0.5).sp,
            color = colorScheme.primary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "100% P2P · サーバーレス暗号化SNS",
            fontFamily = BitchatFontFamily,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.version_prefix, versionName),
            fontFamily = BitchatFontFamily,
            fontSize = 12.sp,
            color = palette.textTertiary
        )
    }
}

/**
 * Two-up tab bar with a sliding underline indicator.
 */
@Composable
internal fun AboutTabBar(
    selected: AboutTab,
    onSelect: (AboutTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current

    var rowWidth by remember { mutableStateOf(0.dp) }
    val tabWidth = rowWidth / 2
    val indicatorOffset by animateDpAsState(
        targetValue = if (selected == AboutTab.Info) 0.dp else tabWidth,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "aboutTabIndicator"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding)
            .onSizeChanged { size ->
                rowWidth = with(density) { size.width.toDp() }
            }
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            AboutTabLabel(
                text = stringResource(R.string.about_tab_info),
                isSelected = selected == AboutTab.Info,
                onClick = { onSelect(AboutTab.Info) },
                modifier = Modifier.weight(1f)
            )
            AboutTabLabel(
                text = stringResource(R.string.about_tab_settings),
                isSelected = selected == AboutTab.Settings,
                onClick = { onSelect(AboutTab.Settings) },
                modifier = Modifier.weight(1f)
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)
            Box(
                modifier = Modifier
                    .offset { IntOffset(x = indicatorOffset.roundToPx(), y = 0) }
                    .width(tabWidth)
                    .height(2.dp)
                    .background(colorScheme.primary)
            )
        }
    }
}

@Composable
private fun AboutTabLabel(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val color by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant,
        animationSpec = tween(BitchatMotion.QUICK_MS, easing = FastOutSlowInEasing),
        label = "aboutTabLabelColor"
    )

    Box(
        modifier = modifier
            .height(44.dp)
            .clickable(onClickLabel = text) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            fontFamily = BitchatFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            color = color
        )
    }
}

/** One line of the "How To Use" list: an icon plus a single instruction. */
@Composable
private fun AboutInstructionRow(
    @DrawableRes iconRes: Int,
    text: String
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(22.dp)
        )
        Text(
            text = text,
            fontFamily = BitchatFontFamily,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = colorScheme.onSurface
        )
    }
}

/**
 * The "How To Use" section: scannable guide for nemix features.
 */
@Composable
internal fun AboutHowToUseSection(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "完全P2Pの主な機能と使い方",
            fontFamily = BitchatFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.primary,
            modifier = Modifier.padding(
                start = AboutHorizontalPadding,
                top = 20.dp,
                bottom = 8.dp
            )
        )

        AboutInstructionRow(
            iconRes = R.drawable.ic_spec_people,
            text = "P2Pグループ機能: ストリーム形式（単一スレッド）とチャンネル形式（トピック・カテゴリ別）を自由に選んで100% P2P暗号化通信ができます"
        )
        AboutInstructionRow(
            iconRes = R.drawable.ic_spec_person,
            text = "ランダムUUID検索: ネット経由でUUID文字列から相手を検索し、同時に相互連絡先登録・チャット開始が可能です"
        )
        AboutInstructionRow(
            iconRes = R.drawable.ic_spec_lock,
            text = "双方スクショ禁止 & 履歴削除: チャットやグループごとに自分・相手側の画面キャプチャを禁止でき、双方同時のメッセージ・履歴削除にも対応しています"
        )
        AboutInstructionRow(
            iconRes = R.drawable.ic_spec_globe,
            text = "スマホ単体完結 & 周辺非公開モード: スマホ1台で災害時オフライン通信からネットP2Pまで100%完結。周辺に公開しないステルス設定も可能です"
        )
        AboutInstructionRow(
            iconRes = R.drawable.ic_spec_waveform,
            text = "無線通話・画像転送・位置情報共有: 近距離無線とネット経由の両方でE2EE音声通話やEXIF除去画像転送、OS標準の現在地共有が可能です"
        )
    }
}

/**
 * nemix Mission Short Declaration, 3 Core Challenges, Terms of Service & Developer GitHub card.
 */
@Composable
internal fun AboutFeatureCard(
    onOpenTerms: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        // Short Declaration Banner
        Surface(
            color = colorScheme.primary.copy(alpha = 0.10f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AboutHorizontalPadding, vertical = 6.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "本アプリの目的（完全P2P基本宣言）",
                        fontFamily = BitchatFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = TermsOfServiceManager.SHORT_DECLARATION,
                    fontFamily = BitchatFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = colorScheme.onSurface
                )
            }
        }

        Text(
            text = "本プロジェクトが解決する3つの課題",
            fontFamily = BitchatFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.primary,
            modifier = Modifier.padding(
                start = AboutHorizontalPadding,
                top = 14.dp,
                bottom = 4.dp
            )
        )

        AboutFeatureRow(
            iconRes = R.drawable.ic_spec_wifi_off,
            title = "オフライン通信の確保",
            subtitle = "インターネットインフラが遮断された災害時や航空機内での連絡手段を提供します。"
        )
        AboutFeatureRow(
            iconRes = R.drawable.ic_spec_globe,
            title = "プラットフォーム非依存の実現",
            subtitle = "中央サーバーを廃し、一企業の規約変更やサービスダウンに左右されない自由なインフラを作ります。"
        )
        AboutFeatureRow(
            iconRes = R.drawable.ic_spec_lock,
            title = "トラフィック負荷の分散",
            subtitle = "エンドツーエンドの直接通信により、グローバルな通信帯域やサーバーリソースの消費を抑えます。"
        )

        if (onOpenTerms != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                onClick = onOpenTerms,
                shape = RoundedCornerShape(12.dp),
                color = colorScheme.surface,
                border = BorderStroke(1.dp, colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AboutHorizontalPadding)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Gavel,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "利用規約・プライバシーポリシー",
                            fontFamily = BitchatFontFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = "電波法・航空法遵守、免責事項、Google Play規約準拠",
                            fontFamily = BitchatFontFamily,
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Developer GitHub Card (https://github.com/ShotaXiangtai)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(DEVELOPER_GITHUB_URL))
                    )
                }
            },
            shape = RoundedCornerShape(12.dp),
            color = colorScheme.surface,
            border = BorderStroke(1.dp, colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AboutHorizontalPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Code,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "開発者 GitHub (ShotaXiangtai)",
                        fontFamily = BitchatFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = DEVELOPER_GITHUB_URL,
                        fontFamily = BitchatFontFamily,
                        fontSize = 11.sp,
                        color = colorScheme.primary
                    )
                }
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun AboutFeatureRow(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(22.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                fontFamily = BitchatFontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
                color = colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Small uppercase pill, e.g. the `RECOMMENDED` badge beside the Tor routing toggle.
 */
@Composable
internal fun BitchatBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .background(colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = text.uppercase(),
            fontFamily = BitchatFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = colorScheme.primary
        )
    }
}

// MARK: - Shared bottom-sheet primitives

/** Horizontal inset for sheet content. Cards inset from here; labels align to the same edge. */
internal val SheetHorizontalPadding = 16.dp

/**
 * Section divider label used across the sheets, e.g. `BOOKMARKED`, `NEARBY`, `ON LOCATION`.
 */
@Composable
internal fun SheetSectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    Text(
        text = text.uppercase(),
        fontFamily = BitchatFontFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = palette.textTertiary,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = SheetHorizontalPadding + 14.dp,
                end = SheetHorizontalPadding,
                top = SheetRowVertical + 7.dp,
                bottom = 6.dp
            )
    )
}

/**
 * Circular tinted badge holding a section's icon, used at the top of the sheets.
 */
@Composable
internal fun SheetHeaderBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(44.dp)
            .background(colorScheme.surface, androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier.size(26.dp)
        )
    }
}

/**
 * Full-width destructive action, e.g. `REMOVE LOCATION ACCESS`.
 */
@Composable
internal fun SheetDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = true
) {
    val colorScheme = MaterialTheme.colorScheme
    val accent = if (isDestructive) colorScheme.error else colorScheme.primary

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text.uppercase(),
                fontFamily = BitchatFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                color = accent
            )
        }
    }
}
