package com.bitchat.android.migration

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.ui.QRCodeAnalyzer
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.ClassicColors
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationSheet(
    isPresented: Boolean,
    initialTab: Int = 0, // 0 = Send (Old Phone), 1 = Receive (New Phone), 2 = File Backup
    onDismiss: () -> Unit,
    onMigrationSuccess: () -> Unit = {}
) {
    if (!isPresented) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(initialTab) }

    BitchatBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.92f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.SwapHoriz,
                        contentDescription = null,
                        tint = ClassicColors.AccentGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "nemix 端末データ移行",
                        fontFamily = BitchatFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                CloseButton(onClick = onDismiss)
            }

            // Tab bar
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = ClassicColors.AccentGreen,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = ClassicColors.AccentGreen
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("旧端末から送信", fontFamily = BitchatFontFamily, fontSize = 13.sp) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("新端末へ引き継ぐ", fontFamily = BitchatFontFamily, fontSize = 13.sp) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("ファイル保存", fontFamily = BitchatFontFamily, fontSize = 13.sp) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Crossfade(targetState = selectedTab, label = "MigrationTabCrossfade") { tab ->
                when (tab) {
                    0 -> SendMigrationTab(context = context, onDismiss = onDismiss)
                    1 -> ReceiveMigrationTab(
                        context = context,
                        onSuccess = {
                            onMigrationSuccess()
                            onDismiss()
                        }
                    )
                    2 -> FileBackupTab(context = context)
                }
            }
        }
    }
}

/**
 * Tab 1: Old Phone - Package, Start Server, Display QR Code.
 */
@Composable
private fun SendMigrationTab(context: Context, onDismiss: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var isStarting by remember { mutableStateOf(false) }
    var serverRunning by remember { mutableStateOf(false) }
    var qrContent by remember { mutableStateOf("") }
    var connectionInfoText by remember { mutableStateOf("") }
    var transferCompleted by remember { mutableStateOf(false) }
    var showWipeDialog by remember { mutableStateOf(false) }

    var migrationServer by remember { mutableStateOf<MigrationServer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            migrationServer?.stop()
        }
    }

    if (showWipeDialog) {
        AlertDialog(
            onDismissRequest = { showWipeDialog = false },
            title = { Text("旧端末のデータを消去しますか？", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "新しい端末へのデータ移行が正常に完了しました。\nプライバシー保護のため、この端末内のnemix暗号鍵、会話履歴、設定をすべて消去して初期化できます。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        MigrationDataPackager.wipeAllLocalData(context)
                        showWipeDialog = false
                        Toast.makeText(context, "データを消去しました", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("データを初期化する")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showWipeDialog = false
                    onDismiss()
                }) {
                    Text("残す / 完了")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "この端末のアカウントと履歴を、新しいスマートフォンへ直接P2P暗号化転送します。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (!serverRunning) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhonelinkRing,
                        contentDescription = null,
                        tint = ClassicColors.AccentGreen,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "準備ができたら「移行を開始」を押してください。\nワンタイム暗号化鍵とQRコードが生成されます。",
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            isStarting = true
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val ip = MigrationDataPackager.getLocalIpAddress()
                                    val port = MigrationServer.DEFAULT_PORT
                                    val token = UUID.randomUUID().toString()
                                    val aesKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

                                    val payload = MigrationDataPackager.packageData(context)
                                    val encrypted = MigrationDataPackager.encryptWithKey(payload, aesKey)

                                    val server = MigrationServer(
                                        port = port,
                                        encryptedData = encrypted,
                                        expectedToken = token,
                                        onTransferComplete = {
                                            transferCompleted = true
                                            showWipeDialog = true
                                        }
                                    )
                                    server.start()
                                    migrationServer = server

                                    val keyBase64 = Base64.encodeToString(aesKey, Base64.NO_WRAP or Base64.URL_SAFE)
                                    val info = MigrationConnectionInfo(ip = ip, port = port, token = token, keyBase64 = keyBase64)
                                    val jsonInfo = Gson().toJson(info)

                                    withContext(Dispatchers.Main) {
                                        qrContent = jsonInfo
                                        connectionInfoText = "接続先: $ip:$port"
                                        serverRunning = true
                                        isStarting = false
                                    }
                                } catch (e: Exception) {
                                    Log.e("SendMigration", "Failed to start migration server", e)
                                    withContext(Dispatchers.Main) {
                                        isStarting = false
                                        Toast.makeText(context, "開始失敗: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.AccentGreen),
                        enabled = !isStarting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                        } else {
                            Text("移行を開始 (QRコード生成)", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            // QR Display Area
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(16.dp)
            ) {
                MigrationQRCodeImage(data = qrContent, size = 240.dp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ClassicColors.AccentGreen.copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (transferCompleted) Color.Green else ClassicColors.AccentGreen)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (transferCompleted) "転送完了！" else "新しい端末からの接続を待機中...",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = connectionInfoText,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    migrationServer?.stop()
                    serverRunning = false
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("移行を中止")
            }
        }
    }
}

/**
 * Tab 2: New Phone - Scan QR, Connect, Download & Restore.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ReceiveMigrationTab(context: Context, onSuccess: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val permissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusMessage by remember { mutableStateOf("古い端末の画面に表示されたQRコードを読み取ってください") }
    var restoredNickname by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (restoredNickname != null) {
            // Success Screen
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = ClassicColors.AccentGreen.copy(alpha = 0.15f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = ClassicColors.AccentGreen,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "引き継ぎが完了しました！",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "アカウント: $restoredNickname\n暗号鍵、会話データ、連絡先が完全に復元されました。",
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onSuccess,
                        colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.AccentGreen),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("nemix を始める", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (isProcessing) {
            // Progress Screen
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(64.dp),
                        color = ClassicColors.AccentGreen
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(text = statusMessage, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
        } else if (permissionState.status.isGranted) {
            // Camera Scanner
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                MigrationScannerView { scannedText ->
                    try {
                        val info = Gson().fromJson(scannedText, MigrationConnectionInfo::class.java)
                        if (info.ip.isNotEmpty() && info.token.isNotEmpty() && info.keyBase64.isNotEmpty()) {
                            isProcessing = true
                            statusMessage = "データをダウンロードして復元中..."
                            coroutineScope.launch {
                                val result = MigrationClient.downloadAndRestore(
                                    context = context,
                                    info = info,
                                    onProgress = { p -> progress = p }
                                )
                                result.onSuccess { payload ->
                                    restoredNickname = payload.nickname
                                    isProcessing = false
                                }.onFailure { error ->
                                    isProcessing = false
                                    Toast.makeText(context, "引き継ぎエラー: ${error.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Overlay targeting box
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .border(2.dp, ClassicColors.AccentGreen, RoundedCornerShape(16.dp))
                )

                Text(
                    text = "QRコードを枠内に収めてください",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        } else {
            // Permission request
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("QRコードを読み取るためにカメラ権限が必要です")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionState.launchPermissionRequest() }) {
                    Text("カメラを許可")
                }
            }
        }
    }
}

/**
 * Tab 3: Password-protected File Backup & Restore (.nemixbackup).
 */
@Composable
private fun FileBackupTab(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "パスワードで暗号化されたバックアップファイル (.nemixbackup) を作成または読み込みます。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("暗号化パスワード") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Export Button
        Button(
            onClick = {
                if (password.length < 6) {
                    Toast.makeText(context, "パスワードは6文字以上にしてください", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                isWorking = true
                coroutineScope.launch(Dispatchers.IO) {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val backupFile = File(downloadsDir, "nemix_backup_${System.currentTimeMillis()}.nemixbackup")
                    val ok = MigrationDataPackager.exportToFile(context, backupFile, password)
                    withContext(Dispatchers.Main) {
                        isWorking = false
                        if (ok) {
                            Toast.makeText(context, "保存完了: ${backupFile.name}", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "バックアップ作成に失敗しました", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            enabled = !isWorking && password.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.AccentGreen),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ファイルへバックアップ書き出し", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Import Button
        OutlinedButton(
            onClick = {
                if (password.length < 6) {
                    Toast.makeText(context, "パスワードを入力してください", Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                isWorking = true
                coroutineScope.launch(Dispatchers.IO) {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val backupFiles = downloadsDir.listFiles { _, name -> name.endsWith(".nemixbackup") }
                    val latest = backupFiles?.maxByOrNull { it.lastModified() }
                    if (latest == null) {
                        withContext(Dispatchers.Main) {
                            isWorking = false
                            Toast.makeText(context, "ダウンロードフォルダに .nemixbackup ファイルが見つかりません", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    val ok = MigrationDataPackager.importFromFile(context, latest, password)
                    withContext(Dispatchers.Main) {
                        isWorking = false
                        if (ok) {
                            Toast.makeText(context, "${latest.name} から正常に復元しました！", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "復元失敗: パスワードが異なるかファイルが破損しています", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            enabled = !isWorking && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("最新のバックアップから復元")
        }
    }
}

@Composable
private fun MigrationQRCodeImage(data: String, size: Dp) {
    val sizePx = with(LocalDensity.current) { size.toPx().toInt() }
    val bitmap = remember(data, sizePx) {
        if (data.isBlank() || sizePx <= 0) null
        else {
            try {
                val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)
                val bmp = createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
                for (x in 0 until matrix.width) {
                    for (y in 0 until matrix.height) {
                        bmp[x, y] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    }
                }
                bmp
            } catch (_: Exception) {
                null
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    }
}

@Composable
private fun MigrationScannerView(onScan: (String) -> Unit) {
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
                    Log.w("MigrationScanner", "Failed to bind camera: ${it.message}")
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
