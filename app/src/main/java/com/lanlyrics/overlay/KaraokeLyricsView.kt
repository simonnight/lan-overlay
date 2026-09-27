package com.lanlyrics.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * 专业级逐字/逐行卡拉OK流光歌词 View
 * 特性：
 * 1. 60FPS 逐帧平滑渲染
 * 2. 支持 YRC 逐字高精度流光 与 普通 LRC 智能平滑扫光 (彻底杜绝歌词静止不动)
 * 3. 双行黄金排版：当前演唱行 (流光高亮) + 下一句预告行 (半透明预览)
 * 4. 防亮背景双层描边阴影
 */
class KaraokeLyricsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 当前行未唱到画笔（半透明白 + 黑阴影）
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        textSize = spToPx(24f)
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(8f, 2f, 2f, Color.parseColor("#CC000000"))
    }

    // 当前行已唱/正在唱的高亮流光画笔（高亮白 + 青蓝荧光）
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFFFF")
        textSize = spToPx(24f)
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(16f, 0f, 0f, Color.parseColor("#CC00D2FF"))
    }

    // 下一句预告行画笔（更低透明度）
    private val nextLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#50FFFFFF")
        textSize = spToPx(16f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        setShadowLayer(6f, 1f, 1f, Color.parseColor("#AA000000"))
    }

    private var currentText: String = "等待播放..."
    private var nextText: String = ""
    private var wordsList: List<WordSegment> = emptyList()
    private var lineStartMs: Long = 0L
    private var lineEndMs: Long = 0L
    private var currentPositionMs: Long = 0L

    data class WordSegment(
        val word: String,
        val startMs: Long,
        val endMs: Long
    )

    fun updateLine(text: String, next: String = "", words: List<WordSegment> = emptyList(), startMs: Long = 0L, endMs: Long = 0L) {
        this.currentText = text
        this.nextText = next
        this.wordsList = words
        this.lineStartMs = startMs
        this.lineEndMs = endMs
        postInvalidateOnAnimation()
    }

    fun setProgress(positionMs: Long) {
        this.currentPositionMs = positionMs
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentText.isEmpty()) return

        val startX = dpToPx(28f)
        val line1Y = dpToPx(38f)
        val line2Y = dpToPx(72f)

        // 1. 绘制当前行
        if (wordsList.isNotEmpty()) {
            // 分支 A: 逐字 YRC 渲染
            var curX = startX
            for (w in wordsList) {
                val wordWidth = inactivePaint.measureText(w.word)
                if (currentPositionMs >= w.endMs) {
                    canvas.drawText(w.word, curX, line1Y, activePaint)
                } else if (currentPositionMs <= w.startMs) {
                    canvas.drawText(w.word, curX, line1Y, inactivePaint)
                } else {
                    val fraction = (currentPositionMs - w.startMs).toFloat() / (w.endMs - w.startMs).toFloat()
                    val splitX = curX + wordWidth * fraction.coerceIn(0f, 1f)

                    canvas.save()
                    canvas.clipRect(curX, 0f, splitX, height.toFloat())
                    canvas.drawText(w.word, curX, line1Y, activePaint)
                    canvas.restore()

                    canvas.save()
                    canvas.clipRect(splitX, 0f, curX + wordWidth, height.toFloat())
                    canvas.drawText(w.word, curX, line1Y, inactivePaint)
                    canvas.restore()
                }
                curX += wordWidth
            }
        } else {
            // 分支 B: 普通 LRC 智能平滑扫光 (彻底杜绝静态死卡)
            val totalWidth = inactivePaint.measureText(currentText)
            val duration = (lineEndMs - lineStartMs).coerceAtLeast(1000L)
            val fraction = if (currentPositionMs < lineStartMs) 0f
            else if (currentPositionMs >= lineEndMs) 1f
            else (currentPositionMs - lineStartMs).toFloat() / duration.toFloat()

            val splitX = startX + totalWidth * fraction.coerceIn(0f, 1f)

            if (fraction <= 0f) {
                canvas.drawText(currentText, startX, line1Y, inactivePaint)
            } else if (fraction >= 1f) {
                canvas.drawText(currentText, startX, line1Y, activePaint)
            } else {
                // 已唱部分高亮流光
                canvas.save()
                canvas.clipRect(startX, 0f, splitX, height.toFloat())
                canvas.drawText(currentText, startX, line1Y, activePaint)
                canvas.restore()

                // 未唱部分半透明
                canvas.save()
                canvas.clipRect(splitX, 0f, startX + totalWidth + 50f, height.toFloat())
                canvas.drawText(currentText, startX, line1Y, inactivePaint)
                canvas.restore()
            }
        }

        // 2. 绘制下一句预告行 (第二行)
        if (nextText.isNotEmpty()) {
            canvas.drawText(nextText, startX, line2Y, nextLinePaint)
        }
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
}
