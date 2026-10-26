package com.guyan.miccatcher

import android.content.Context

/**
 * 轻量设置存储。
 */
object Prefs {
    private const val FILE = "mic_prefs"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 数量上限：识别到这么多个名字就停止采集 */
    fun limit(c: Context): Int = sp(c).getInt("limit", 30)
    fun setLimit(c: Context, v: Int) = sp(c).edit().putInt("limit", v.coerceIn(1, 999)).apply()

    /** 自动识别间隔（秒） */
    fun interval(c: Context): Int = sp(c).getInt("interval", 60).coerceIn(5, 600)
    fun setInterval(c: Context, v: Int) = sp(c).edit().putInt("interval", v.coerceIn(5, 600)).apply()

    /** 识别区域（相对屏幕高度的比例，0f..1f） */
    fun regionTop(c: Context): Float = sp(c).getFloat("rtop", 0.20f)
    fun regionBottom(c: Context): Float = sp(c).getFloat("rbot", 0.62f)
    fun setRegion(c: Context, top: Float, bottom: Float) {
        val t = top.coerceIn(0.02f, 0.96f)
        val b = bottom.coerceIn(t + 0.04f, 0.99f)
        sp(c).edit().putFloat("rtop", t).putFloat("rbot", b).apply()
    }
}
