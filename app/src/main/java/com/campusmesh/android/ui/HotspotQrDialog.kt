package com.campusmesh.android.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.campusmesh.android.net.WifiHotspotManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Scenario 1: "Invite Nearby Peers" Modal Popup (HotspotQrDialog)
 *
 * Displayed when the user taps the "Invite Nearby Peers" / "Share Gateway" button
 * in the app header. Shows a clean, scannable QR code combining:
 * 1. Wi-Fi credentials (SSID + Password) to join the local-only hotspot.
 * 2. The Ktor PWA server URL, shown as text below the QR (WIFI: QR payloads only carry network
 *    credentials, not URLs). Uses the device's actual reachable IP on the hotspot interface —
 *    NsdManager's DNS-SD service registration does not publish a custom "campusmesh.local" host
 *    record, so that hostname is not guaranteed to resolve in Safari/Chrome.
 *
 * Android only for now (iPhone/PWA gateway support had connection issues that weren't worth
 * continuing to chase - see AGENTS.md/session notes). The QR payload uses the standard Wi-Fi
 * provisioning format understood by Android's Quick Settings Wi-Fi scanner and most camera/QR
 * apps:
 *   WIFI:S:<SSID>;T:WPA;P:<PASSWORD>;;
 *
 * @param onDismiss  Called when the user taps "Close" or swipes down to dismiss.
 */
@Composable
fun HotspotQrDialog(
    onDismiss: () -> Unit
) {
    val ssid     = WifiHotspotManager.hotspotSsid ?: "Campus-Mesh-GCTU"
    val password = WifiHotspotManager.hotspotPassword ?: "campusmesh"
    val hotspotActive = WifiHotspotManager.isHotspotActive
    // NsdManager registers a discoverable *service*, not a "campusmesh.local" host record, so
    // that hostname isn't guaranteed to resolve. The gateway IP always works once joined.
    val gatewayUrl = WifiHotspotManager.hotspotGatewayIp?.let { "http://$it:8080" } ?: "http://campusmesh.local:8080"

    // Combined QR payload: WPA join + mDNS link
    val qrPayload = buildQrPayload(ssid, password)
    val qrBitmap  = remember(qrPayload) { generateQrBitmap(qrPayload) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .shadow(16.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1A1A2E))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📡", fontSize = 28.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Invite Nearby Peers",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Share Gateway QR Code",
                            color = Color(0xFF9E9EC8),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // QR Code
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(12.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Wi-Fi QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF16213E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF7C83FD), modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Starting hotspot…",
                                color = Color(0xFF9E9EC8),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Network info card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF16213E))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (hotspotActive) {
                        InfoRow(label = "Network", value = ssid)
                        Spacer(modifier = Modifier.height(4.dp))
                        InfoRow(label = "Password", value = password)
                        Spacer(modifier = Modifier.height(4.dp))
                        InfoRow(label = "PWA URL", value = gatewayUrl)
                    } else {
                        Text(
                            text = "Starting local hotspot… Scan once the code appears.",
                            color = Color(0xFF9E9EC8),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Android only for now - scan this from another Android phone's camera or QR scanner to join.",
                    color = Color(0xFF9E9EC8),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Close button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7C83FD),
                        contentColor = Color.White
                    )
                ) {
                    Text("Close", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF7C83FD), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(value, color = Color.White, fontSize = 12.sp)
    }
}

/**
 * Builds the WPA Wi-Fi QR payload that iPhones and Android automatically parse.
 * Format: WIFI:S:<SSID>;T:WPA;P:<PASSWORD>;;
 */
private fun buildQrPayload(ssid: String, password: String): String {
    // Escape special chars as per WPA QR spec
    val escapedSsid = ssid.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\"", "\\\"")
    val escapedPwd  = password.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\"", "\\\"")
    return "WIFI:S:$escapedSsid;T:WPA;P:$escapedPwd;;"
}

/**
 * Generates a ZXing QR code bitmap from the payload string.
 */
private fun generateQrBitmap(payload: String, sizePx: Int = 512): Bitmap? {
    return try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
