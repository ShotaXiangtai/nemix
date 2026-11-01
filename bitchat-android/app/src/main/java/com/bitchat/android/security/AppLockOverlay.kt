package com.bitchat.android.security

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.ClassicColors

/**
 * Full-screen privacy shield shown when App Lock is enabled and locked.
 */
@Composable
fun AppLockOverlay() {
    val context = LocalContext.current
    val activity = context as? Activity
    val colorScheme = MaterialTheme.colorScheme
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val credentialLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            AppLockManager.markUnlocked()
        } else {
            errorMsg = "認証が完了しませんでした"
        }
    }

    fun triggerAuth() {
        errorMsg = null
        if (activity != null) {
            AppLockManager.authenticate(
                activity = activity,
                onLaunchCredentialIntent = { credentialLauncher.launch(it) },
                onError = { errorMsg = it }
            )
        }
    }

    LaunchedEffect(Unit) {
        triggerAuth()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(ClassicColors.Teal.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = ClassicColors.Teal,
                    modifier = Modifier.size(40.dp)
                )
            }

            Text(
                text = "nemix はロックされています",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface,
                fontFamily = BitchatFontFamily
            )

            Text(
                text = "プライバシー保護のため生体認証または端末PINでロックを解除してください",
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariant,
                fontFamily = BitchatFontFamily
            )

            errorMsg?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = colorScheme.error,
                    fontFamily = BitchatFontFamily
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { triggerAuth() },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.Teal)
            ) {
                Icon(
                    imageVector = Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ロックを解除する",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontFamily = BitchatFontFamily
                )
            }
        }
    }
}

