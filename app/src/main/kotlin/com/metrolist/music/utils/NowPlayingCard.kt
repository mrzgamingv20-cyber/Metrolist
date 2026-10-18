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
import android.net.Uri
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.models.MediaMetadata
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

    private const val SIZE = 640
    private const val MAX_LINES = 3

    /**
     * Returns a cached uri for [metadata], generating and storing the card on first use. Returns
     * null when the card cannot be produced, in which case the caller keeps the album artwork.
     */
    suspend fun uriFor(context: Context, metadata: MediaMetadata): Uri? {
        val subtitle = metadata.artists.joinToString(", ") { it.name }
        val title = metadata.title.takeIf { it.isNotBlank() } ?: return null
        val cacheDir = File(context.cacheDir, "now_playing_cards").apply { mkdirs() }
        val file = File(cacheDir, "${metadata.id.sanitized()}.jpg")
        if (file.exists() && file.length() > 0) return Uri.fromFile(file)

        return try {
            val bitmap = render(context, title, subtitle, metadata.thumbnailUrl)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            Uri.fromFile(file)
        } catch (e: Exception) {
            Timber.tag("NowPlayingCard").w(e, "Failed to render now playing card")
            file.delete()
            null
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
            // Not recycled: Coil may hand back an instance that is still shared with its cache.
            canvas.drawBitmap(art, null, centerCrop(Rect(0, 0, SIZE, SIZE), art.width, art.height), Paint(Paint.ANTI_ALIAS_FLAG))
        }
        // Scrim keeps the text readable over bright artwork and makes the card feel intentional.
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, SIZE.toFloat(),
                intArrayOf(
                    Color.argb(110, 0, 0, 0),
                    Color.argb(150, 0, 0, 0),
                    Color.argb(225, 0, 0, 0),
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        })

        val margin = SIZE * 0.08f
        val textWidth = SIZE - margin * 2

        var titleSize = SIZE * 0.115f
        var subtitleSize = SIZE * 0.075f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(214, 255, 255, 255) }

        // Shrink until the text fits the card so long titles never overflow.
        while (titleSize > SIZE * 0.055f &&
            (measureHeight(titlePaint, title, titleSize, textWidth) > SIZE * 0.5f ||
                measureHeight(subtitlePaint, subtitle, subtitleSize, textWidth) > SIZE * 0.3f)
        ) {
            titleSize *= 0.94f
            subtitleSize *= 0.94f
            titlePaint.textSize = titleSize
            subtitlePaint.textSize = subtitleSize
        }
        titlePaint.textSize = titleSize
        subtitlePaint.textSize = subtitleSize

        val titleHeight = measureHeight(titlePaint, title, titleSize, textWidth)
        val subtitleHeight = if (subtitle.isBlank()) 0f else measureHeight(subtitlePaint, subtitle, subtitleSize, textWidth)

        var y = SIZE - margin - subtitleHeight
        drawTextBlock(canvas, subtitlePaint, subtitle, margin, y, textWidth, subtitleHeight)
        y -= titleHeight
        drawTextBlock(canvas, titlePaint, title, margin, y, textWidth, titleHeight)

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
    ) {
        if (text.isBlank()) return
        val lines = wrapLines(paint, text, maxWidth)
        val lineHeight = blockHeight / lines.size
        var baseline = bottom - paint.descent()
        for (line in lines) {
            canvas.drawText(line, x, baseline, paint)
            baseline -= lineHeight
        }
    }

    /**
     * Greedy word wrap with an ellipsis on the last kept line. Hand rolled because the platform
     * layout builders are awkward to reach from Kotlin and the older alternatives are deprecated.
     */
    private fun wrapLines(paint: TextPaint, text: String, maxWidth: Float): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var truncated = false

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isEmpty() || paint.measureText(candidate) <= maxWidth) {
                current.setLength(0)
                current.append(candidate)
            } else if (lines.size < MAX_LINES - 1) {
                lines.add(current.toString())
                current.setLength(0)
                current.append(word)
            } else {
                truncated = true
                break
            }
        }
        if (current.isNotEmpty() && lines.size < MAX_LINES) lines.add(current.toString())

        return lines.map { line ->
            var shrunk = line
            val needsEllipsis = truncated && line === lines.last() || paint.measureText(shrunk) > maxWidth
            if (!needsEllipsis) return@map line
            while (shrunk.isNotEmpty() && paint.measureText("$shrunk…") > maxWidth) {
                shrunk = shrunk.dropLast(1)
            }
            "$shrunk…"
        }
    }

    private fun measureHeight(paint: TextPaint, text: String, textSize: Float, maxWidth: Float): Float {
        if (text.isBlank()) return 0f
        paint.textSize = textSize
        return wrapLines(paint, text, maxWidth).size * paint.fontSpacing
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