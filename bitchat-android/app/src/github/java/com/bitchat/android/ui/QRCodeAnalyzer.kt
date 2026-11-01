package com.bitchat.android.ui

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * GitHub / privacy build: QR code analyzer built on ZXing (fully open source, no network access or telemetry).
 * Reads the luminance (Y) plane of the CameraX YUV_420_888 frame.
 */
internal class QRCodeAnalyzer(
    private val onCode: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true
    )

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val plane = imageProxy.planes.firstOrNull() ?: return
            val width = imageProxy.width
            val height = imageProxy.height
            val buffer = plane.buffer.duplicate().apply { rewind() }
            val rowStride = plane.rowStride
            val luminance = ByteArray(width * height)
            if (rowStride == width) {
                buffer.get(luminance, 0, minOf(luminance.size, buffer.remaining()))
            } else {
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(luminance, row * width, width)
                }
            }
            val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
            val text = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (_: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            if (!text.isNullOrBlank()) onCode(text)
        } catch (_: Exception) {
            // Malformed frame: skip it and wait for the next one.
        } finally {
            imageProxy.close()
        }
    }
}
