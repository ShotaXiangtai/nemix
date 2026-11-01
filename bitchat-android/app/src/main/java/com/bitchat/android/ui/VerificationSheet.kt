package com.bitchat.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.LocalSheetDismiss
import com.bitchat.android.directory.NemixUuidDirectoryManager
import com.bitchat.android.groups.P2PGroupManager
import com.bitchat.android.services.VerificationService
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.ClassicColors
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel,
    initialTab: Int = 0,
    onOpenTargetChat: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (!isPresented) return

    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary

    var selectedTab by remember(initialTab) { mutableStateOf(initialTab.coerceIn(0, 2)) } // 0 = My QR, 1 = Scan, 2 = ID(UUID)
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val npub = remember { viewModel.getCurrentNpub() }

    val qrString = remember(nickname, npub) {
        viewModel.buildMyQRString(nickname, npub)
    }

    BitchatBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // Header
            VerificationHeader(
                accent = accent,
                onClose = onDismiss,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // 3 Tabs: マイQR / スキャン / ID (UUID) 追加
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = accent,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = accent
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = "マイQR",
                            fontFamily = BitchatFontFamily,
                            fontSize = 13.sp
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "スキャン",
                            fontFamily = BitchatFontFamily,
                            fontSize = 13.sp
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(
                            text = "ID (UUID)",
                            fontFamily = BitchatFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content
            Crossfade(
                targetState = selectedTab,
                label = "VerificationTabCrossfade",
                modifier = Modifier.weight(1f)
            ) { tab ->
                when (tab) {
                    0 -> MyQrTabContent(
                        qrString = qrString,
                        nickname = nickname,
                        accent = accent
                    )
                    1 -> ScanTabContent(
                        accent = accent,
                        onScan = { code ->
                            if (code.startsWith("nemix://group", ignoreCase = true)) {
                                P2PGroupManager.joinGroupFromInviteCode(context, code, nickname)
                                    .onSuccess { grp ->
                                        Toast.makeText(context, "P2Pグループ「${grp.name}」に参加しました", Toast.LENGTH_SHORT).show()
                                        onOpenTargetChat?.invoke("group:${grp.id}") ?: onDismiss()
                                    }
                                return@ScanTabContent
                            }
                            val qr = VerificationService.verifyScannedQR(code)
                            if (qr != null && viewModel.beginQRVerification(qr)) {
                                selectedTab = 0
                            }
                        }
                    )
                    2 -> UuidInternetAddTabContent(
                        nickname = nickname,
                        accent = accent,
                        onContactAdded = { conversationId ->
                            onOpenTargetChat?.invoke(conversationId) ?: onDismiss()
                        }
                    )
                }
            }

            // Unverify Action
            val peerID by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
            val fingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()

            if (peerID != null && selectedTab != 2) {
                val fingerprint = viewModel.getMeshPeerFingerprint(peerID!!)
                if (fingerprint != null && fingerprints.contains(fingerprint)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.unverifyFingerprint(peerID!!) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.verify_remove),
                            fontFamily = BitchatFontFamily,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UuidInternetAddTabContent(
    nickname: String,
    accent: Color,
    onContactAdded: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val myUuid by NemixUuidDirectoryManager.myUuid.collectAsStateWithLifecycle()
    val isPublishing by NemixUuidDirectoryManager.isPublishing.collectAsStateWithLifecycle()

    var targetUuidInput by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Ensure our UUID record is fresh on active P2P relays when opening this tab
    LaunchedEffect(myUuid, nickname) {
        withContext(Dispatchers.IO) {
            NemixUuidDirectoryManager.publishMyDirectoryRecord(context, nickname)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Card 1: My Random UUID
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = accent.copy(alpha = 0.09f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Public,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "あなたのネット検索用 ID (ランダムUUID)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = accent,
                        fontFamily = BitchatFontFamily
                    )
                }

                Text(
                    text = "このUUIDを相手に伝えると、近くにいなくてもインターネット / P2Pリレー経由であなたを検索して連絡先に追加できます（UUIDを知らない第三者は復号できません）。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = myUuid,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = BitchatFontFamily,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("nemix-uuid", myUuid))
                            Toast.makeText(context, "自分のUUIDをコピーしました", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("UUIDをコピー", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val fresh = NemixUuidDirectoryManager.regenerateUuid(context, nickname)
                            Toast.makeText(context, "新しいランダムUUIDを再生成しました: ${fresh.take(8)}…", Toast.LENGTH_SHORT).show()
                        },
                        enabled = !isPublishing
                    ) {
                        Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("再生成", fontSize = 12.sp)
                    }
                }
            }
        }

        HorizontalDivider()

        // Card 2: Search & Simultaneously Add Peer by UUID over Internet
        Text(
            text = "相手のUUIDを検索してネット経由でユーザー追加",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            fontFamily = BitchatFontFamily
        )

        OutlinedTextField(
            value = targetUuidInput,
            onValueChange = {
                targetUuidInput = it
                statusMessage = null
            },
            label = { Text("相手のランダムUUIDを入力・貼り付け") },
            placeholder = { Text("例: 8f3a9c12-4b7e-4d2a-9e1f-...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (isSearching) return@Button
                isSearching = true
                statusMessage = "P2Pリレー上で暗号化UUIDレコードを検索中..."
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        NemixUuidDirectoryManager.lookupAndAddByUuid(
                            context = context,
                            targetUuidRaw = targetUuidInput,
                            myNickname = nickname
                        )
                    }
                    isSearching = false
                    result.onSuccess { added ->
                        Toast.makeText(
                            context,
                            "✅ ${added.nickname} さんをネット経由で追加しました！",
                            Toast.LENGTH_LONG
                        ).show()
                        onContactAdded(added.conversationID)
                    }.onFailure { err ->
                        statusMessage = err.message ?: "ユーザーが見つかりませんでした"
                    }
                }
            },
            enabled = !isSearching && targetUuidInput.trim().length >= 8,
            colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.AccentGreen),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("ネット検索・鍵交換中...", color = Color.White, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Filled.PersonAdd, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("UUID追加と同時にネット経由でユーザー追加", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        statusMessage?.let { msg ->
            Text(
                text = msg,
                fontSize = 12.sp,
                color = if (isSearching) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun VerificationHeader(
    accent: Color,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "友達追加 (QR / ネットUUID)".uppercase(),
            fontSize = 14.sp,
            fontFamily = BitchatFontFamily,
            fontWeight = FontWeight.Bold,
            color = accent
        )
        val dismiss = LocalSheetDismiss.current
        CloseButton(onClick = { dismiss?.invoke() ?: onClose() })
    }
}

@Composable
private fun MyQrTabContent(
    qrString: String,
    nickname: String,
    accent: Color
) {
    val myUuid by NemixUuidDirectoryManager.myUuid.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.verify_my_qr_title),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = BitchatFontFamily,
            color = accent
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (qrString.isNotBlank()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .padding(20.dp)
            ) {
                QRCodeImage(data = qrString, size = 240.dp)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.verify_qr_unavailable),
                    fontFamily = BitchatFontFamily,
                    fontSize = 12.sp,
                    color = Color.Black.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = nickname,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = BitchatFontFamily,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "UUID: $myUuid",
            fontSize = 11.sp,
            fontFamily = BitchatFontFamily,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ScanTabContent(
    accent: Color,
    onScan: (String) -> Unit
) {
    val permissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (permissionState.status.isGranted) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                ScannerView(onScan = onScan)

                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .border(2.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                )

                Text(
                    text = stringResource(R.string.verify_scan_prompt_friend),
                    color = Color.White,
                    fontFamily = BitchatFontFamily,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = accent
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.verify_camera_permission),
                    fontFamily = BitchatFontFamily,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { permissionState.launchPermissionRequest() },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text(
                        text = stringResource(R.string.verify_request_camera),
                        fontFamily = BitchatFontFamily
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerView(
    onScan: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastValid by remember { mutableStateOf<String?>(null) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val surfaceRequests = remember { MutableStateFlow<SurfaceRequest?>(null) }
    val surfaceRequest by surfaceRequests.collectAsState(initial = null)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val onCodeState = rememberUpdatedState(onScan)
    val analyzer = remember {
        QRCodeAnalyzer { text ->
            mainHandler.post {
                if (text == lastValid) return@post
                lastValid = text
                onCodeState.value(text)
            }
        }
    }

    DisposableEffect(Unit) {
        val executor = ContextCompat.getMainExecutor(context)
        var cameraProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener(
            {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider { request -> surfaceRequests.value = request }
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer) }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }.onFailure {
                    Log.w("VerificationSheet", "Failed to bind camera: ${it.message}")
                }
            },
            executor
        )

        onDispose {
            surfaceRequests.value = null
            runCatching { cameraProvider?.unbindAll() }
            cameraExecutor.shutdown()
        }
    }

    surfaceRequest?.let { request ->
        CameraXViewfinder(
            surfaceRequest = request,
            implementationMode = ImplementationMode.EMBEDDED,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun QRCodeImage(data: String, size: Dp) {
    val sizePx = with(LocalDensity.current) { size.toPx().toInt() }
    val bitmap = remember(data, sizePx) { generateQrBitmap(data, sizePx) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    }
}

private fun generateQrBitmap(data: String, sizePx: Int): Bitmap? {
    if (data.isBlank() || sizePx <= 0) return null
    return try {
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)
        bitmapFromMatrix(matrix)
    } catch (_: Exception) {
        null
    }
}

private fun bitmapFromMatrix(matrix: BitMatrix): Bitmap {
    val width = matrix.width
    val height = matrix.height
    val bitmap = createBitmap(width, height)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap[x, y] =
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return bitmap
}

internal class QRCodeAnalyzer(
    private val onCode: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val text = barcodes.firstOrNull()?.rawValue
                if (!text.isNullOrBlank()) onCode(text)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
