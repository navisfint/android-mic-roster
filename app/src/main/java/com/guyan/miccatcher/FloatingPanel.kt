package com.guyan.miccatcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import kotlin.math.abs

/**
 * 悬浮在目标画面上的控制面板。
 *
 * 关键：截图前必须把整块面板 GONE 掉，否则它自己的文字会被 OCR 认进名单里。
 */
class FloatingPanel(
    private val ctx: Context,
    private val cb: Callbacks
) {

    interface Callbacks {
        fun onScan()
        fun onAuto()
        fun onCopy()
        fun onRegion()
        fun onClear()
        fun onClose()
    }

    companion object {
        // 悬浮窗不能被截图，也不能抢焦点
        private const val FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var expanded = false

    private var tvCount: TextView? = null
    private var tvMsg: TextView? = null
    private var expandBox: View? = null
    private var btnExpand: TextView? = null
    private var btnAuto: TextView? = null
    private var dot: View? = null
    private var header: View? = null

    /** flash 提示的到期时刻，期间不让倒计时覆盖它 */
    private var flashUntil = 0L

    var isShowing: Boolean = false
        private set

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (isShowing) return
        val themed = ContextThemeWrapper(ctx, R.style.Theme_MicCatcher)
        val v = LayoutInflater.from(themed).inflate(R.layout.view_float_panel, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(240)
        }

        tvCount = v.findViewById(R.id.tvCount)
        tvMsg = v.findViewById(R.id.tvMsg)
        expandBox = v.findViewById(R.id.expand)
        btnExpand = v.findViewById(R.id.btnExpand)
        btnAuto = v.findViewById(R.id.btnAuto)
        dot = v.findViewById(R.id.dot)
        header = v.findViewById(R.id.header)

        v.findViewById<View>(R.id.btnScan).setOnClickListener { cb.onScan() }
        v.findViewById<View>(R.id.btnAuto).setOnClickListener { cb.onAuto() }
        v.findViewById<View>(R.id.btnCopy).setOnClickListener { cb.onCopy() }
        v.findViewById<View>(R.id.btnRegion).setOnClickListener { cb.onRegion() }
        v.findViewById<View>(R.id.btnClear).setOnClickListener { cb.onClear() }
        v.findViewById<View>(R.id.btnClose).setOnClickListener { cb.onClose() }

        attachDrag(header!!)

        try {
            wm.addView(v, params)
        } catch (e: Exception) {
            return
        }
        root = v
        lp = params
        isShowing = true
    }

    fun remove() {
        val v = root ?: return
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
        root = null
        lp = null
        isShowing = false
    }

    /** 截图前：藏起来，等一帧再截 */
    fun hideForCapture() {
        root?.visibility = View.GONE
    }

    fun restoreAfterCapture() {
        root?.visibility = View.VISIBLE
    }

    fun update(count: Int, limit: Int, auto: Boolean, intervalSec: Int) {
        tvCount?.text = "$count/$limit"
        tvCount?.setTextColor(if (count >= limit) 0xFFFF7A6E.toInt() else 0xFFBDB6E0.toInt())

        val b = btnAuto ?: return
        if (auto) {
            b.text = "停止自动 · 每 $intervalSec 秒"
            b.setBackgroundResource(R.drawable.bg_panel_btn_on)
            b.setTextColor(0xFF241640.toInt())
        } else {
            b.text = "开启自动 · 每 $intervalSec 秒"
            b.setBackgroundResource(R.drawable.bg_panel_btn)
            b.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    fun setBusy(busy: Boolean) {
        dot?.setBackgroundResource(if (busy) R.drawable.bg_dot_busy else R.drawable.bg_dot)
        if (busy) {
            setMsg("识别中…")
        } else if (tvMsg?.text?.toString() == "识别中…") {
            tvMsg?.visibility = View.GONE
        }
    }

    /** 常态状态行（自动倒计时），被 flash 提示占用时让位 */
    fun setStatus(text: String?) {
        if (android.os.SystemClock.elapsedRealtime() < flashUntil) return
        setMsg(text)
    }

    fun setMsg(text: String?) {
        val m = tvMsg ?: return
        if (text.isNullOrEmpty()) {
            m.visibility = View.GONE
        } else {
            m.visibility = View.VISIBLE
            m.text = text
            m.setTextColor(0xFFBDB6E0.toInt())
        }
    }

    /** 达到数量上限：展开面板 + 红字提示几秒 */
    fun flashLimit(limit: Int) {
        expand(true)
        val m = tvMsg ?: return
        flashUntil = android.os.SystemClock.elapsedRealtime() + 4000
        m.visibility = View.VISIBLE
        m.text = "已达上限 $limit 个，已停止采集"
        m.setTextColor(0xFFFF7A6E.toInt())
        dot?.setBackgroundColor(0xFFFF7A6E.toInt())

        main.removeCallbacksAndMessages(null)
        main.postDelayed({
            m.visibility = View.GONE
            dot?.setBackgroundResource(R.drawable.bg_dot)
        }, 4000)
    }

    fun flash(text: String, autoExpand: Boolean = true) {
        if (autoExpand) expand(true)
        val m = tvMsg ?: return
        flashUntil = android.os.SystemClock.elapsedRealtime() + 2600
        m.visibility = View.VISIBLE
        m.text = text
        m.setTextColor(0xFFBDB6E0.toInt())
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ m.visibility = View.GONE }, 2600)
    }

    private fun expand(open: Boolean) {
        expanded = open
        expandBox?.visibility = if (open) View.VISIBLE else View.GONE
        btnExpand?.text = if (open) "收起" else "展开"
    }

    private fun toggleExpand() = expand(!expanded)

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(handle: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop

        handle.setOnTouchListener { _, e ->
            val params = lp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        root?.let { wm.updateViewLayout(it, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleExpand()
                    true
                }

                else -> false
            }
        }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
