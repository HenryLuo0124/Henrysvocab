package com.eng.henrysvocab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as BitmapCanvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import kotlin.math.min
import kotlin.math.roundToInt

@get:DrawableRes
internal val defaultWallpaperResId: Int get() = R.drawable.forest

/** Stores just the custom crop in app-private, non-backed-up storage. Call on an I/O thread. */
internal class WallpaperStore(context: Context) {
    private val data = AppDataDirectory(context)
    private val file = data.file("wallpaper.png")
    private val legacyFile = File(data.root, "wallpaper.png")
    private val legacy = AtomicFile(legacyFile)
    private val legacyFiles = listOf(
        File(data.root, "wallpaper.png.new"),
        legacyFile,
        File(data.root, "wallpaper.png.bak"),
    )

    fun load(): Bitmap? = synchronized(lock) {
        val current = try {
            Files.newInputStream(file.toPath()).use { BitmapFactory.decodeStream(it) }
        } catch (_: IOException) {
            null
        }
        if (current != null) {
            retireLegacyAfterCommit()
            return@synchronized current
        }

        // AtomicFile recovers a committed legacy .bak, but never treats an unfinished .new as data.
        val previous = try {
            legacy.openRead().use { BitmapFactory.decodeStream(it) }
        } catch (_: FileNotFoundException) {
            null
        } ?: return@synchronized null

        try {
            legacy.openRead().use { input -> data.write(file) { output -> input.copyTo(output) } }
            retireLegacyAfterCommit()
        } catch (_: IOException) {
            // Leave the original readable and retry on the next load.
        }
        previous
    }

    fun save(bitmap: Bitmap) = synchronized(lock) {
        data.write(file) { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IOException("Could not encode wallpaper")
            }
        }
        retireLegacyAfterCommit()
    }

    fun reset() = synchronized(lock) {
        // Remove all legacy variants first. Never delete the new file while fallback can resurrect the old one.
        deleteLegacy()
        data.delete(file)
    }

    private fun retireLegacyAfterCommit() {
        if (legacyFiles.none { it.exists() }) return
        try {
            data.sync()
            deleteLegacy()
        } catch (_: IOException) {
            // The new file wins; retry legacy cleanup on a later load.
        }
    }

    private fun deleteLegacy() {
        legacyFiles.forEach { Files.deleteIfExists(it.toPath()) }
        syncDataDirectory(data.root)
    }

    private companion object {
        val lock = Any()
    }
}

private const val MAX_PHOTO_EDGE = 2048
private const val MAX_SOURCE_EDGE = 32768
private const val MAX_SOURCE_PIXELS = 256_000_000L
private const val MAX_ZOOM = 8f

/** Decodes an orientation-corrected, software bitmap from a readable picker URI. */
internal suspend fun loadPhoto(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodePhoto(context, uri)
    } else {
        decodeLegacyPhoto(context, uri)
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun decodePhoto(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val (width, height) = info.size.let { it.width to it.height }
        checkPhotoDimensions(width, height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val ratio = min(1f, MAX_PHOTO_EDGE.toFloat() / maxOf(width, height))
        if (ratio < 1f) {
            decoder.setTargetSize(
                (width * ratio).roundToInt().coerceAtLeast(1),
                (height * ratio).roundToInt().coerceAtLeast(1),
            )
        }
    }
}

private fun checkPhotoDimensions(width: Int, height: Int) {
    if (width <= 0 || height <= 0) throw IOException("Unsupported or invalid photo")
    if (maxOf(width, height) > MAX_SOURCE_EDGE || width.toLong() * height > MAX_SOURCE_PIXELS) {
        throw IOException("Photo dimensions are too large")
    }
}

private fun decodeLegacyPhoto(context: Context, uri: Uri): Bitmap {
    val resolver = context.contentResolver
    fun open() = resolver.openInputStream(uri) ?: throw IOException("Could not open photo")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    checkPhotoDimensions(bounds.outWidth, bounds.outHeight)
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while ((longest + sample - 1) / sample > MAX_PHOTO_EDGE) sample *= 2

    val orientation = try {
        open().use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = open().use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IOException("Could not decode photo")
    val orientationMatrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    if (orientationMatrix.isIdentity) return decoded
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, orientationMatrix, true)
        .also { if (it !== decoded) decoded.recycle() }
}

/** An inline editor. [aspectRatio] is the target width / height; [onSave] receives only the crop. */
@Composable
internal fun CropWallpaper(
    bitmap: Bitmap,
    aspectRatio: Float,
    onSave: (Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    require(aspectRatio.isFinite() && aspectRatio > 0f) { "Expected a positive width/height ratio" }
    val geometry = remember(bitmap, aspectRatio) { CropGeometry(bitmap, aspectRatio) }
    var crop by remember(geometry) { mutableStateOf(geometry.initial) }
    var busy by remember(geometry) { mutableStateOf(false) }
    var failed by remember(geometry) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentGeometry by rememberUpdatedState(geometry)
    val saveResult by rememberUpdatedState(onSave)
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("拖动调整位置，双指捏合缩放", style = MaterialTheme.typography.bodyMedium)
        // Bound the viewport independently of the scrollable parent's unbounded height.
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val previewWidth = minOf(maxWidth, 320.dp, 360.dp * aspectRatio).coerceAtLeast(1.dp)
            val previewHeight = (previewWidth / aspectRatio).coerceIn(1.dp, 360.dp)
            Canvas(
                Modifier.size(previewWidth, previewHeight)
                    .clipToBounds()
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.65f))
                    .semantics { contentDescription = "背景裁剪预览" }
                    .pointerInput(geometry, busy) {
                        if (busy) return@pointerInput
                        detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                            if (size.width > 0 && size.height > 0) {
                                crop = geometry.transform(
                                    crop,
                                    Offset(centroid.x / size.width, centroid.y / size.height),
                                    Offset(pan.x / size.width, pan.y / size.height),
                                    zoom,
                                )
                            }
                        }
                    },
            ) {
                if (size.width > 0f && size.height > 0f) {
                    drawIntoCanvas {
                        paintCrop(it.nativeCanvas, bitmap, geometry.rect(crop), size.width, size.height, paint)
                    }
                }
            }
        }
        if (failed) Text("无法裁剪这张照片，请重试。", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, onClick = onCancel) { Text("取消") }
            Button(
                enabled = !busy,
                onClick = {
                    if (!busy) {
                        busy = true
                        failed = false
                        val selection = geometry.rect(crop)
                        scope.launch {
                            val result = try {
                                withContext(Dispatchers.Default) { renderCrop(bitmap, selection) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                failed = true
                                null
                            } finally {
                                busy = false
                            }
                            if (result != null && currentGeometry === geometry) saveResult(result)
                        }
                    }
                },
            ) { Text(if (busy) "正在裁剪…" else "保存") }
        }
    }
}

private data class CropState(val center: Offset, val zoom: Float = 1f)

/** Keep all geometry in source pixels so layout changes don't move the crop. */
private class CropGeometry(bitmap: Bitmap, aspectRatio: Float) {
    private val width = bitmap.width.toFloat()
    private val height = bitmap.height.toFloat()
    private val baseWidth = min(width, height * aspectRatio)
    private val baseHeight = min(height, width / aspectRatio)
    val initial = CropState(Offset(width / 2f, height / 2f))

    fun rect(state: CropState): Rect {
        val halfWidth = baseWidth / state.zoom / 2f
        val halfHeight = baseHeight / state.zoom / 2f
        return Rect(
            state.center.x - halfWidth, state.center.y - halfHeight,
            state.center.x + halfWidth, state.center.y + halfHeight,
        )
    }

    fun transform(state: CropState, pivot: Offset, pan: Offset, zoomChange: Float): CropState {
        val zoom = (state.zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        val newWidth = baseWidth / zoom
        val newHeight = baseHeight / zoom
        val x = state.center.x + (pivot.x - 0.5f) * (baseWidth / state.zoom - newWidth) - pan.x * newWidth
        val y = state.center.y + (pivot.y - 0.5f) * (baseHeight / state.zoom - newHeight) - pan.y * newHeight
        return CropState(
            Offset(
                x.coerceIn(newWidth / 2f, width - newWidth / 2f),
                y.coerceIn(newHeight / 2f, height - newHeight / 2f),
            ),
            zoom,
        )
    }
}

private fun renderCrop(bitmap: Bitmap, selection: Rect): Bitmap {
    val ratio = min(1f, MAX_PHOTO_EDGE / maxOf(selection.width, selection.height))
    val width = (selection.width * ratio).roundToInt().coerceAtLeast(1)
    val height = (selection.height * ratio).roundToInt().coerceAtLeast(1)
    // The picker decoder supplies software bitmaps. Also handle callers passing a hardware bitmap.
    val source = if (bitmap.config == Bitmap.Config.HARDWARE) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("Could not read bitmap")
    } else {
        bitmap
    }
    try {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        paintCrop(BitmapCanvas(output), source, selection, width.toFloat(), height.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    } finally {
        if (source !== bitmap) source.recycle()
    }
}

private fun paintCrop(canvas: BitmapCanvas, bitmap: Bitmap, rect: Rect, width: Float, height: Float, paint: Paint) {
    val checkpoint = canvas.save()
    try {
        canvas.clipRect(0f, 0f, width, height)
        canvas.scale(width / rect.width, height / rect.height)
        canvas.translate(-rect.left, -rect.top)
        // An explicit bitmap destination avoids density scaling by the Android Canvas.
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), paint)
    } finally {
        canvas.restoreToCount(checkpoint)
    }
}
