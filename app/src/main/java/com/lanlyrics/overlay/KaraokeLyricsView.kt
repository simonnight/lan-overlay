package com.lanlyrics.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * 逐字流光歌词自定义 View
 * 专为 60FPS 逐字渐变高亮与平滑位移渲染
 */
class KaraokeLyricsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 默认未高亮歌词画笔（半透明灰白色 + 阴影防背景干扰）
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        textSize = 64f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(8f, 2f, 2f, Color.parseColor("#99000000"))
    }

    // 已唱/正在唱的高亮流光画笔
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFFFF")
        textSize = 64f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(16f, 0f, 0f, Color.parseColor("#8000D2FF"))
    }

    private var currentText: String = "等待局域网播放..."
    private var wordsList: List<WordSegment> = emptyList()
    private var currentPositionMs: Long = 0L

    data class WordSegment(
        val word: String,
        val startMs: Long,
        val endMs: Long
    )

    fun updateLine(text: String, words: List<WordSegment>) {
        this.currentText = text
        this.wordsList = words
        postInvalidateOnAnimation()
    }

    fun setProgress(positionMs: Long) {
        this.currentPositionMs = positionMs
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentText.isEmpty()) return

        val textY = height / 2f - (inactivePaint.descent() + inactivePaint.ascent()) / 2f
        var startX = 40f

        // 如果没有逐字时间轴，直接整行渲染
        if (wordsList.isEmpty()) {
            canvas.drawText(currentText, startX, textY, activePaint)
            return
        }

        // 逐字流光高亮渲染
        for (w in wordsList) {
            val wordWidth = inactivePaint.measureText(w.word)

            if (currentPositionMs >= w.endMs) {
                // 该字完全唱完：完全高亮发光
                canvas.drawText(w.word, startX, textY, activePaint)
            } else if (currentPositionMs <= w.startMs) {
                // 该字未唱到：半透明灰色
                canvas.drawText(w.word, startX, textY, inactivePaint)
            } else {
                // 正在唱该字：精准平滑插值线性裁切 (流光上色)
                val fraction = (currentPositionMs - w.startMs).toFloat() / (w.endMs - w.startMs).toFloat()
                val splitX = startX + wordWidth * fraction.coerceIn(0f, 1f)

                // 1. 先画高亮部分
                canvas.save()
                canvas.clipRect(startX, 0f, splitX, height.toFloat())
                canvas.drawText(w.word, startX, textY, activePaint)
                canvas.restore()

                // 2. 再画未高亮部分
                canvas.save()
                canvas.clipRect(splitX, 0f, startX + wordWidth, height.toFloat())
                canvas.drawText(w.word, startX, textY, inactivePaint)
                canvas.restore()
            }
            startX += wordWidth
        }
    }
}
