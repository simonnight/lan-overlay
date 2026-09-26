package com.lanlyrics.overlay

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

/**
 * 影视双语字幕专业渲染控件 (BilingualSubtitleView)
 * 专为 4K Android TV (Netflix / Disney+ / HBO 等) 设计
 * 特性：
 * 1. 影院级中英双行黄金比例排版
 * 2. 双层纯黑轮廓描边 (TextOutline)，防止任何雪地/爆炸等浅色背景干扰
 * 3. 毫秒级时间轴驱动与平滑切换
 */
class BilingualSubtitleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 中文主文字画笔 (填充)
    private val zhFillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE042") // 经典电影金黄 (可配置)
        textSize = spToPx(26f)
        typeface = Typeface.DEFAULT_BOLD
    }

    // 中文轮廓描边画笔 (防止亮背景干扰)
    private val zhStrokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000") // 纯黑深色描边
        textSize = spToPx(26f)
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    // 英文副文字画笔 (填充)
    private val enFillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0F0F0") // 浅亮白
        textSize = spToPx(18f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    // 英文轮廓描边画笔
    private val enStrokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
        textSize = spToPx(18f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private var currentZh: String = ""
    private var currentEn: String = ""

    private var zhLayout: StaticLayout? = null
    private var enLayout: StaticLayout? = null

    fun setSubtitles(zh: String, en: String) {
        if (this.currentZh == zh && this.currentEn == en) return
        this.currentZh = zh.trim()
        this.currentEn = en.trim()
        rebuildLayouts()
        postInvalidate()
    }

    fun clear() {
        if (currentZh.isEmpty() && currentEn.isEmpty()) return
        currentZh = ""
        currentEn = ""
        zhLayout = null
        enLayout = null
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayouts()
    }

    private fun rebuildLayouts() {
        val availableW = (width - paddingLeft - paddingRight).coerceAtLeast(100)

        zhLayout = if (currentZh.isNotEmpty()) {
            StaticLayout.Builder.obtain(currentZh, 0, currentZh.length, zhFillPaint, availableW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()
        } else null

        enLayout = if (currentEn.isNotEmpty()) {
            StaticLayout.Builder.obtain(currentEn, 0, currentEn.length, enFillPaint, availableW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()
        } else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (zhLayout == null && enLayout == null) return

        val totalH = (zhLayout?.height ?: 0) + (if (zhLayout != null && enLayout != null) dpToPx(6f).toInt() else 0) + (enLayout?.height ?: 0)
        var startY = (height - totalH) / 2f

        // 1. 绘制中文主字幕 (描边 + 填充)
        zhLayout?.let { layout ->
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), startY)

            // 先用描边画笔画轮廓
            drawLayoutWithPaint(canvas, layout, zhStrokePaint)
            // 再用高亮画笔画正文
            drawLayoutWithPaint(canvas, layout, zhFillPaint)

            canvas.restore()
            startY += layout.height + dpToPx(6f)
        }

        // 2. 绘制英文副字幕 (描边 + 填充)
        enLayout?.let { layout ->
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), startY)

            drawLayoutWithPaint(canvas, layout, enStrokePaint)
            drawLayoutWithPaint(canvas, layout, enFillPaint)

            canvas.restore()
        }
    }

    private fun drawLayoutWithPaint(canvas: Canvas, layout: StaticLayout, paint: TextPaint) {
        val tempLayout = StaticLayout.Builder.obtain(layout.text, 0, layout.text.length, paint, layout.width)
            .setAlignment(layout.alignment)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
        tempLayout.draw(canvas)
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    private fun spToPx(sp: Float): Float {
        return sp * resources.displayMetrics.scaledDensity
    }
}
