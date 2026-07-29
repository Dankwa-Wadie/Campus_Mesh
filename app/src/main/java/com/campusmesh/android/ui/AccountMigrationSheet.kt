package com.campusmesh.android.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.campusmesh.android.R
import com.campusmesh.android.core.ui.component.sheet.BitchatBottomSheet
import com.campusmesh.android.core.ui.component.sheet.BitchatSheetTitle
import com.campusmesh.android.core.ui.component.sheet.BitchatSheetTopBar
import com.campusmesh.android.crypto.AccountTransferManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountMigrationSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val transferManager = remember { AccountTransferManager(context) }
    
    var migrationPackage by remember { mutableStateOf<AccountTransferManager.MigrationPackage?>(null) }
    var secondsLeft by remember { mutableStateOf(60) }
    
    // Regenerate package on display and every 60 seconds
    LaunchedEffect(isPresented) {
        if (isPresented) {
            while (true) {
                migrationPackage = transferManager.generateMigrationPackage()
                secondsLeft = 60
                for (i in 60 downTo 1) {
                    delay(1000)
                    secondsLeft = i - 1
                }
            }
        }
    }

    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Text(
                        text = "Sync Profile to Safari",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Scan this QR code with an iPhone camera to sync your identity profile and cryptographic private keys to Safari PWA over the offline hotspot.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    migrationPackage?.let { pkg ->
                        Box(
                            modifier = Modifier
                                .background(Color.White, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            QRCodeImage(data = pkg.qrContent, size = 220.dp)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Rolling Countdown Indicator
                    LinearProgressIndicator(
                        progress = { secondsLeft.toFloat() / 60f },
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Rolling code regenerates in $secondsLeft seconds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                }
                
                // TopBar with close button
                BitchatSheetTopBar(
                    title = {},
                    onClose = onDismiss
                )
            }
        }
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
    } catch (e: Exception) {
        Log.e("AccountMigrationSheet", "Error generating QR Bitmap: ${e.message}")
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
