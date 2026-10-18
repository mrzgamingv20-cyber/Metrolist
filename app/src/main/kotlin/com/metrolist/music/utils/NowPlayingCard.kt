/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import timber.log.Timber

/**
 * Renders a square card used as the artwork of the playing song, so the OEM dynamic island shows
 * something designed instead of a bare thumbnail. The result is cached on disk per song, which
 * keeps the uri stable across recompositions and avoids the notification reloading it constantly.
 */
object NowPlayingCard {

    private const val SIZE = 512
    private const val TITLE_LINES = 2
    private const val SUBTITLE_LINES = 1
    private const val BLUR_STEPS = 24
    private const val AVERAGE_STEPS = 8

    // Avoids touching the disk for songs already rendered this session. collectLatest can restart
    // a block while the previous render is still winding down, hence the lock.
    private val cacheLock = Any()
    private val memoryCache = object : LinkedHashMap<String, Uri>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Uri>) = size > 64
    }

    private fun cached(key: String): Uri? = synchronized(cacheLock) { memoryCache[key] }

    private fun remember(key: String, uri: Uri) = synchronized(cacheLock) { memoryCache[key] = uri }

    /**
     * Returns a cached uri for [metadata], generating and storing the card on first use. Returns
     * null when the card cannot be produced, in which case the caller keeps the album artwork.
     */
    suspend fun uriFor(context: Context, metadata: MediaMetadata): Uri? {
        val subtitle = metadata.artists.joinToString(", ") { it.name }
        val title = metadata.title.takeIf { it.isNotBlank() } ?: return null
        val key = metadata.id
        cached(key)?.let { return it }

        // Rendering allocates a bitmap and compressing it hits the disk, so never let this run on
        // the caller when that caller is the service main thread.
        return withContext(Dispatchers.IO) {
            val cacheDir = File(context.cacheDir, "now_playing_cards").apply { mkdirs() }
            val file = File(cacheDir, "${metadata.id.sanitized()}.jpg")
            if (file.exists() && file.length() > 0) {
                val cached = Uri.fromFile(file)
                remember(key, cached)
                return@withContext cached
            }

            try {
                val bitmap = render(context, title, subtitle, metadata.thumbnailUrl)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                bitmap.recycle()
                val uri = Uri.fromFile(file)
                remember(key, uri)
                uri
            } catch (e: Exception) {
                Timber.tag("NowPlayingCard").w(e, "Failed to render now playing card")
                file.delete()
                null
            }
        }
    }

    private suspend fun render(
        context: Context,
        title: String,
        subtitle: String,
        artworkUrl: String?,
    ): Bitmap {
        val card = createBitmap(SIZE, SIZE)
        val canvas = Canvas(card)

        val art = artworkUrl?.let { loadArtwork(context, it) }
        if (art != null) {
            // Heavy blur behind the text is what makes these cards read as glass rather than as a
            // plain album cover. Done by downscaling hard and filtering back up so it works below
            // API 31 where RenderEffect is unavailable.
            val backdrop = soften(art)
            canvas.drawBitmap(backdrop, null, Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG))
            backdrop.recycle()
            // Keeps a trace of the cover colour instead of flattening everything to black.
            val tint = averageColor(art)
            canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
                shader = LinearGradient(
                    0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
                    intArrayOf(
                        withAlpha(tint, 0),
                        withAlpha(tint, 70),
                        withAlpha(tint, 0),
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            })
        } else {
            canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), fallbackGradient(title))
        }

        // Scrim: gentle at the top so the artwork still reads, heavy at the bottom for the text.
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, SIZE.toFloat(),
                intArrayOf(
                    Color.argb(70, 6, 8, 14),
                    Color.argb(120, 6, 8, 14),
                    Color.argb(215, 4, 6, 12),
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP,
            )
        })

        val margin = SIZE * 0.09f
        val textWidth = SIZE - margin * 2

        var titleSize = SIZE * 0.125f
        var subtitleSize = SIZE * 0.072f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.01f
        }
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(179, 255, 255, 255)
            typeface = Typeface.create("sans-serif-regular", Typeface.NORMAL)
        }

        // Shrink until the text fits the card so long titles never overflow.
        while (titleSize > SIZE * 0.06f &&
            (measureHeight(titlePaint, title, titleSize, textWidth, TITLE_LINES) > SIZE * 0.36f ||
                measureHeight(subtitlePaint, subtitle, subtitleSize, textWidth, SUBTITLE_LINES) > SIZE * 0.14f)
        ) {
            titleSize *= 0.94f
            subtitleSize *= 0.94f
            titlePaint.textSize = titleSize
            subtitlePaint.textSize = subtitleSize
        }
        titlePaint.textSize = titleSize
        subtitlePaint.textSize = subtitleSize

        val gap = SIZE * 0.022f
        val titleHeight = measureHeight(titlePaint, title, titleSize, textWidth, TITLE_LINES)
        val subtitleHeight =
            if (subtitle.isBlank()) 0f else measureHeight(subtitlePaint, subtitle, subtitleSize, textWidth, SUBTITLE_LINES)

        var y = SIZE - margin - subtitleHeight
        drawTextBlock(canvas, subtitlePaint, subtitle, margin, y, textWidth, subtitleHeight, SUBTITLE_LINES)
        y -= titleHeight + if (subtitleHeight > 0f) gap else 0f
        drawTextBlock(canvas, titlePaint, title, margin, y, textWidth, titleHeight, TITLE_LINES)

        return card
    }

    private fun drawTextBlock(
        canvas: Canvas,
        paint: TextPaint,
        text: String,
        x: Float,
        bottom: Float,
        maxWidth: Float,
        blockHeight: Float,
        maxLines: Int,
    ) {
        if (text.isBlank()) return
        val lines = wrapLines(paint, text, maxWidth, maxLines)
        val lineHeight = blockHeight / lines.size
        var baseline = bottom - paint.descent()
        for (line in lines) {
            canvas.drawText(line, x, baseline, paint)
            baseline -= lineHeight
        }
    }

    /**
     * Cheap box blur approximation: collapse to a handful of pixels, then let bilinear filtering
     * stretch it back. Runs on any API level unlike RenderEffect, which needs API 31.
     */
    private fun soften(src: Bitmap): Bitmap {
        val tiny = createBitmap(BLUR_STEPS, BLUR_STEPS)
        Canvas(tiny).drawBitmap(
            src,
            null,
            centerCrop(Rect(0, 0, BLUR_STEPS, BLUR_STEPS), src.width, src.height),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        val out = createBitmap(SIZE, SIZE)
        Canvas(out).drawBitmap(tiny, null, Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG))
        tiny.recycle()
        return out
    }

    private fun averageColor(src: Bitmap): Int {
        val sample = createBitmap(AVERAGE_STEPS, AVERAGE_STEPS)
        Canvas(sample).drawBitmap(src, null, Rect(0, 0, AVERAGE_STEPS, AVERAGE_STEPS), Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(AVERAGE_STEPS * AVERAGE_STEPS)
        sample.getPixels(pixels, 0, AVERAGE_STEPS, 0, 0, AVERAGE_STEPS, AVERAGE_STEPS)
        sample.recycle()
        var r = 0L
        var g = 0L
        var b = 0L
        for (pixel in pixels) {
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
        }
        val count = pixels.size
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Used when there is no cover to work with, keeps cards distinguishable per song. */
    private fun fallbackGradient(seed: String): Paint {
        val hash = seed.hashCode()
        val hue = ((hash % 360) + 360) % 360
        val top = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.45f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.65f, 0.22f))
        return Paint().apply {
            shader = LinearGradient(
                0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
                intArrayOf(top, bottom),
                null,
                Shader.TileMode.CLAMP,
            )
        }
    }

    /**
     * Greedy word wrap with an ellipsis on the last kept line. Hand rolled because the platform
     * layout builders are awkward to reach from Kotlin and the older alternatives are deprecated.
     */
    private fun wrapLines(paint: TextPaint, text: String, maxWidth: Float, maxLines: Int): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var truncated = false

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isEmpty() || paint.measureText(candidate) <= maxWidth) {
                current.setLength(0)
                current.append(candidate)
            } else if (lines.size < maxLines - 1) {
                lines.add(current.toString())
                current.setLength(0)
                current.append(word)
            } else {
                truncated = true
                break
            }
        }
        if (current.isNotEmpty() && lines.size < maxLines) lines.add(current.toString())

        return lines.mapIndexed { index, line ->
            val isLast = index == lines.lastIndex
            if (!(truncated && isLast) && paint.measureText(line) <= maxWidth) return@mapIndexed line
            var shrunk = line
            while (shrunk.isNotEmpty() && paint.measureText("$shrunk…") > maxWidth) {
                shrunk = shrunk.dropLast(1)
            }
            "$shrunk…"
        }
    }

    private fun measureHeight(paint: TextPaint, text: String, textSize: Float, maxWidth: Float, maxLines: Int): Float {
        if (text.isBlank()) return 0f
        paint.textSize = textSize
        return wrapLines(paint, text, maxWidth, maxLines).size * paint.fontSpacing
    }

    private fun centerCrop(target: Rect, srcWidth: Int, srcHeight: Int): Rect {
        val scale = max(target.width().toFloat() / srcWidth, target.height().toFloat() / srcHeight)
        val width = (target.width() / scale).toInt()
        val height = (target.height() / scale).toInt()
        val left = (srcWidth - width) / 2
        val top = (srcHeight - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private suspend fun loadArtwork(context: Context, url: String): Bitmap? {
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(url)
                .size(SIZE, SIZE)
                .allowHardware(false)
                .build(),
        )
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    private fun String.sanitized(): String = map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
        .joinToString("")
        .take(120)
        .ifBlank { abs(hashCode().toLong()).toString() }
}