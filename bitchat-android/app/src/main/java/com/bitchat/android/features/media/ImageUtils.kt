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
     * including TLV + Noise AEAD overhead). Mesh sends are re-encoded to <= 92 KB right before
     * sending (see [fitForMesh]); internet sends keep the standard, much sharper version.
     */
    private const val MAX_MESH_IMAGE_BYTES = 92_000

    /** Standard quality for picked/captured photos: sharp on phones, small enough for relays. */
    private const val STANDARD_MAX_DIM = 1600
    private const val STANDARD_MAX_BYTES = 450_000

    fun downscaleAndSaveToAppFiles(context: Context, uri: Uri, maxDim: Int = STANDARD_MAX_DIM, quality: Int = 85): String? {
        return try {
            val resolver = context.contentResolver
            val exifRotation = resolver.openInputStream(uri)?.use { getRotationDegreesFromExif(it) } ?: 0
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, maxDim) }
            val original = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return null
            encodeOriented(context, original, exifRotation, maxDim, quality)
        } catch (_: Throwable) {
            // Includes OutOfMemoryError on very large photos: never crash the chat screen.
            null
        }
    }

    fun downscalePathAndSaveToAppFiles(context: Context, path: String, maxDim: Int = STANDARD_MAX_DIM, quality: Int = 85): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, maxDim) }
            val original = BitmapFactory.decodeFile(path, options) ?: return null
            encodeOriented(context, original, getRotationDegreesFromExif(path), maxDim, quality)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns a version of [path] small enough for one mesh (Bluetooth / Wi-Fi Aware) transfer.
     * The file itself is returned when it already fits; otherwise a re-encoded copy is written
     * next to it. Returns null only if the image cannot be decoded.
     */
    fun fitForMesh(path: String): String? {
        val file = File(path)
        if (!file.exists()) return null
        if (file.length() <= MAX_MESH_IMAGE_BYTES) return path
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, 1024) }
            val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
            val bytes = compressUnderLimit(bitmap, intArrayOf(1024, 800, 640, 512, 384, 320), MAX_MESH_IMAGE_BYTES)
            try { bitmap.recycle() } catch (_: Exception) {}
            val out = File(file.parentFile, file.nameWithoutExtension + "_mesh.jpg")
            FileOutputStream(out).use { it.write(bytes) }
            out.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    /** Power-of-two sample size so the decoded bitmap is at least [maxDim] but not far above it. */
    private fun sampleSizeFor(bounds: BitmapFactory.Options, maxDim: Int): Int {
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return 1
        var sample = 1
        while (longest / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    private fun encodeOriented(context: Context, original: Bitmap, exifRotation: Int, maxDim: Int, quality: Int): String {
        val oriented = if (exifRotation != 0) rotateBitmap(original, exifRotation) else original
        val dims = intArrayOf(maxDim, (maxDim * 0.8f).toInt(), (maxDim * 0.64f).toInt(), 800, 640)
            .filter { it > 0 }.distinct().toIntArray()
        val bytes = compressUnderLimit(oriented, dims, STANDARD_MAX_BYTES, firstQuality = quality)
        try { oriented.recycle() } catch (_: Exception) {}
        val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
        val outFile = File(dir, "img_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { it.write(bytes) }
        return outFile.absolutePath
    }

    /** Tries sizes from large to small and qualities from high to low until the JPEG fits. */
    private fun compressUnderLimit(
        source: Bitmap,
        dims: IntArray,
        maxBytes: Int,
        firstQuality: Int = 82
    ): ByteArray {
        val qualities = intArrayOf(firstQuality.coerceIn(40, 92), 76, 68, 60, 50, 42).distinct()
        var best: ByteArray? = null
        for (targetDim in dims) {
            val scale = (maxOf(source.width, source.height).toFloat() / targetDim.toFloat()).coerceAtLeast(1f)
            val scaled = if (scale > 1f) {
                Bitmap.createScaledBitmap(
                    source,
                    (source.width / scale).toInt().coerceAtLeast(1),
                    (source.height / scale).toInt().coerceAtLeast(1),
                    true
                )
            } else source
            try {
                for (q in qualities) {
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, q, baos)
                    val bytes = baos.toByteArray()
                    if (best == null || bytes.size < best.size) best = bytes
                    if (bytes.size <= maxBytes) return bytes
                }
            } finally {
                if (scaled !== source) try { scaled.recycle() } catch (_: Exception) {}
            }
        }
        return best ?: ByteArray(0)
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
