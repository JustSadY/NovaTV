package com.novatv.player.ui.common

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import java.util.Locale

/** Placeholder showing up to two initials of the channel name, tinted by a stable hue. */
class InitialsDrawable(name: String) : Drawable() {
    private val text: String = initials(name)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF3F5F9.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PALETTE[(name.hashCode() and 0x7fffffff) % PALETTE.size]
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        if (size <= 0f) return
        val radius = size * 0.42f
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), radius, bgPaint)
        textPaint.textSize = radius * 0.85f
        val y = b.exactCenterY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, b.exactCenterX(), y, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        textPaint.alpha = alpha
        bgPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        textPaint.colorFilter = colorFilter
        bgPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val PALETTE = intArrayOf(
            0xFF2F5FAF.toInt(), 0xFF2E7D6B.toInt(), 0xFF7A4FB5.toInt(), 0xFFB5574F.toInt(),
            0xFF4F7FB5.toInt(), 0xFF8A6D2F.toInt(), 0xFF3F8F9F.toInt(), 0xFF9F3F6F.toInt(),
        )

        fun initials(name: String): String {
            val words = name.trim().split(Regex("[\\s_\\-|:]+")).filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
            return when {
                words.isEmpty() -> "TV"
                words.size == 1 -> words[0].take(2)
                else -> "${words[0][0]}${words[1][0]}"
            }.uppercase(Locale.getDefault())
        }
    }
}
