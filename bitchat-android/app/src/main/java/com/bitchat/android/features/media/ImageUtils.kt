package com.bitchat.android.features.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ImageUtils {
    /**
     * Private-media v1 over BLE/Wi-Fi mesh supports up to 256 fragments (~120 KB total packet bytes
     * including TLV + Noise AEAD overhead). Keeping outgoing JPEGs <= 92 KB guarantees 100% reliable
     * single-pass mesh delivery without ever hitting the 256-fragment rejection error.
     */
    private const val MAX_MESH_IMAGE_BYTES = 92_000

    fun downscaleAndSaveToAppFiles(context: Context, uri: Uri, maxDim: Int = 512, quality: Int = 84): String? {
        return try {
            val resolver = context.contentResolver
            val exifRotation = resolver.openInputStream(uri)?.use { getRotationDegreesFromExif(it) } ?: 0

            val input = resolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(input)
            input.close()
            original ?: return null

            val oriented = if (exifRotation != 0) rotateBitmap(original, exifRotation) else original
            val outPath = compressAndWriteUnderMeshLimit(context, oriented, maxDim, quality)
            try { if (oriented !== original) original.recycle() } catch (_: Exception) {}
            try { oriented.recycle() } catch (_: Exception) {}
            outPath
        } catch (_: Exception) {
            null
        }
    }

    fun downscalePathAndSaveToAppFiles(context: Context, path: String, maxDim: Int = 512, quality: Int = 84): String? {
        return try {
            val original = BitmapFactory.decodeFile(path) ?: return null
            val exifRotation = getRotationDegreesFromExif(path)
            val oriented = if (exifRotation != 0) rotateBitmap(original, exifRotation) else original
            val outPath = compressAndWriteUnderMeshLimit(context, oriented, maxDim, quality)
            try { if (oriented !== original) original.recycle() } catch (_: Exception) {}
            try { oriented.recycle() } catch (_: Exception) {}
            outPath
        } catch (_: Exception) {
            null
        }
    }

    private fun compressAndWriteUnderMeshLimit(
        context: Context,
        oriented: Bitmap,
        initialMaxDim: Int,
        initialQuality: Int
    ): String {
        val dims = intArrayOf(initialMaxDim, 448, 384, 320)
        val qualities = intArrayOf(initialQuality.coerceIn(40, 90), 72, 60, 50, 40)
        var bestBytes: ByteArray? = null

        outer@ for (targetDim in dims) {
            val w = oriented.width
            val h = oriented.height
            val scale = (maxOf(w, h).toFloat() / targetDim.toFloat()).coerceAtLeast(1f)
            val newW = (w / scale).toInt().coerceAtLeast(1)
            val newH = (h / scale).toInt().coerceAtLeast(1)
            val scaled = if (scale > 1f) Bitmap.createScaledBitmap(oriented, newW, newH, true) else oriented

            for (q in qualities) {
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, q, baos)
                val bytes = baos.toByteArray()
                bestBytes = bytes
                if (bytes.size <= MAX_MESH_IMAGE_BYTES) {
                    if (scaled !== oriented) {
                        try { scaled.recycle() } catch (_: Exception) {}
                    }
                    break@outer
                }
            }
            if (scaled !== oriented) {
                try { scaled.recycle() } catch (_: Exception) {}
            }
        }

        val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
        val outFile = File(dir, "img_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { fos ->
            if (bestBytes != null) {
                fos.write(bestBytes)
            } else {
                oriented.compress(Bitmap.CompressFormat.JPEG, 55, fos)
            }
        }
        return outFile.absolutePath
    }

    fun loadBitmapWithExifOrientation(path: String): Bitmap? {
        return try {
            val base = BitmapFactory.decodeFile(path) ?: return null
            val rotation = getRotationDegreesFromExif(path)
            if (rotation != 0) rotateBitmap(base, rotation) else base
        } catch (_: Exception) {
            null
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        return try {
            val m = Matrix()
            m.postRotate(degrees.toFloat())
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also {
                try { src.recycle() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            src
        }
    }

    private fun getRotationDegreesFromExif(path: String): Int = try {
        val exif = ExifInterface(path)
        orientationToDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    } catch (_: Exception) { 0 }

    private fun getRotationDegreesFromExif(stream: InputStream): Int = try {
        val exif = ExifInterface(stream)
        orientationToDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    } catch (_: Exception) { 0 }

    private fun orientationToDegrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }
}
