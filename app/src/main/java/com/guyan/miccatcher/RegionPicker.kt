package com.guyan.miccatcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 全屏框选浮层：上下拖动两条白线，圈出名单名字所在的那一条区域。
 *
 * 这个区域是识别准确率的关键 —— 只在这个纵向区间内取字，
 * 弹幕、礼物飘屏、标题栏全都会被排除。
 */
class RegionPicker(
    private val ctx: Context,
    private val wm: WindowManager,
    topRatio: Float,
    bottomRatio: Float,
    private val onConfirm: (Float, Float) -> Unit
) {

    private var topRatio = topRatio
    private var bottomRatio = bottomRatio

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var mask: MaskView? = null

    /** 浮层关闭（确认或取消）都会回调，用来恢复被临时隐藏的悬浮面板 */
    var onClosed: (() -> Unit)? = null

    @SuppressLint("SetTextI18n")
    fun show() {
        if (root != null) return

        val frame = FrameLayout(ctx)
        val m = MaskView(ctx)
        frame.addView(
            m,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        mask = m

        // 顶部说明
        val hint = TextView(ctx).apply {
            text = "上下拖动白线，只框住名单名字那一条"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setBackgroundResource(R.drawable.bg_region_hint)
        }
        frame.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(64)
            }
        )

        // 底部按钮
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancel = pillButton("取消", false)
        val ok = pillButton("确定", true)
        bar.addView(cancel)
        bar.addView(ok)

        frame.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(46)
                marginStart = dp(28)
                marginEnd = dp(28)
            }
        )

        cancel.setOnClickListener { dismiss() }
        ok.setOnClickListener {
            val t = topRatio
            val b = bottomRatio
            dismiss()
            onConfirm(t, b)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(frame, p)
        } catch (e: Exception) {
            return
        }
        root = frame
        params = p
    }

    fun dismiss() {
        val r = root ?: return
        try {
            wm.removeView(r)
        } catch (_: Exception) {
        }
        root = null
        params = null
        mask = null
        onClosed?.invoke()
    }

    private fun pillButton(text: String, primary: Boolean): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else Color.parseColor("#D6CFFF"))
            setPadding(dp(30), dp(13), dp(30), dp(13))
            setBackgroundResource(if (primary) R.drawable.bg_pill_primary else R.drawable.bg_panel_btn)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    @SuppressLint("ViewConstructor")
    private inner class MaskView(c: Context) : View(c) {

        private val dpDensity = resources.displayMetrics.density
        private val grabRadius = 92f * dpDensity

        private val maskPaint = Paint().apply { color = 0xB8000000.toInt() }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * dpDensity
            color = Color.parseColor("#8B6CF0")
        }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val handleGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#806C4CE0")
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * resources.displayMetrics.scaledDensity
            typeface = Typeface.DEFAULT_BOLD
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C9C3E6")
            textSize = 11.5f * resources.displayMetrics.scaledDensity
        }

        private var dragging = 0
        private var lastY = 0f

        override fun onDraw(canvas: Canvas) {
            val t = topRatio * height
            val b = bottomRatio * height

            canvas.drawRect(0f, 0f, width.toFloat(), t, maskPaint)
            canvas.drawRect(0f, b, width.toFloat(), height.toFloat(), maskPaint)

            canvas.drawRect(1f, t, width - 1f, b, borderPaint)
            drawHandle(canvas, t)
            drawHandle(canvas, b)

            val label = "识别区域  ${(topRatio * 100).toInt()}% – ${(bottomRatio * 100).toInt()}%"
            val cx = width / 2f
            val cy = (t + b) / 2f
            canvas.drawText(label, cx - labelPaint.measureText(label) / 2f, cy - 4f * dpDensity, labelPaint)
            val sub = "框外的文字一律忽略"
            canvas.drawText(sub, cx - subPaint.measureText(sub) / 2f, cy + 16f * dpDensity, subPaint)
        }

        private fun drawHandle(canvas: Canvas, y: Float) {
            val w = 46f * dpDensity
            val h = 4.5f * dpDensity
            val left = width / 2f - w / 2f
            val rect = RectF(left, y - h / 2f, left + w, y + h / 2f)
            canvas.drawRoundRect(
                RectF(rect.left - 3 * dpDensity, rect.top - 3 * dpDensity, rect.right + 3 * dpDensity, rect.bottom + 3 * dpDensity),
                8f * dpDensity, 8f * dpDensity, handleGlow
            )
            canvas.drawRoundRect(rect, h / 2f, h / 2f, handlePaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val y = e.y
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val dTop = abs(y - topRatio * height)
                    val dBottom = abs(y - bottomRatio * height)
                    dragging = when {
                        dTop <= grabRadius && dTop <= dBottom -> 1
                        dBottom <= grabRadius -> 2
                        else -> 0
                    }
                    lastY = y
                    return dragging != 0
                }

                MotionEvent.ACTION_MOVE -> {
                    if (dragging == 0) return false
                    val ratio = (y / height).coerceIn(0f, 1f)
                    if (dragging == 1) {
                        topRatio = ratio.coerceIn(0.01f, bottomRatio - 0.05f)
                    } else {
                        bottomRatio = ratio.coerceIn(topRatio + 0.05f, 0.99f)
                    }
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = 0
                    return true
                }
            }
            return false
        }
    }
}
