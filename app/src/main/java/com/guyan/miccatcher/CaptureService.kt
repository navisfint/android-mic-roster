package com.guyan.miccatcher

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 常驻前台服务：持有 MediaProjection + VirtualDisplay + 悬浮窗。
 *
 * 注意 Android 14 起「同一个屏幕捕获授权只能 createVirtualDisplay 一次」，
 * 重复调用会抛 SecurityException。所以这里 VirtualDisplay 只建一次、全程复用，
 * 每次识别只是从 ImageReader 里取最新一帧。
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "MicCapture"
        private const val CHANNEL_ID = "mic_capture"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.guyan.miccatcher.START"
        const val ACTION_STOP = "com.guyan.miccatcher.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        @Volatile
        var instance: CaptureService? = null
            private set

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).apply { action = ACTION_STOP })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var panel: FloatingPanel? = null
    private var regionPicker: RegionPicker? = null

    @Volatile
    private var screenW = 0

    @Volatile
    private var screenH = 0

    @Volatile
    private var scanning = false

    private var destroying = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        NameStore.setLimit(Prefs.limit(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (code == Activity.RESULT_OK && data != null) {
                    startCapture(code, data)
                } else {
                    stopSelf()
                }
            }

            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroying = true
        autoOn = false
        instance = null
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        regionPicker?.dismiss()
        regionPicker = null
        panel?.remove()
        panel = null
        releaseCapture()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 捕获

    private fun startCapture(resultCode: Int, data: Intent) {
        createChannel()
        val notification = buildNotification("正在等待识别")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }

        if (mp == null) {
            toast("屏幕捕获授权失效，请重新开启")
            stopSelf()
            return
        }

        // Android 14 要求：必须在前台服务起来之后才能注册回调 / 建 VD
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (destroying) return
                main.post {
                    toast("屏幕捕获已被系统结束")
                    stopSelf()
                }
            }
        }, main)

        projection = mp

        measureScreen()
        val dpi = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = try {
            mp.createVirtualDisplay(
                "mic-capture",
                screenW,
                screenH,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            null
        }

        if (virtualDisplay == null) {
            toast("屏幕捕获启动失败，请重试")
            stopSelf()
            return
        }

        showPanel()
        toast("已开启，去目标画面点悬浮球")
    }

    private fun measureScreen() {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width()
            screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
        if (screenW <= 0 || screenH <= 0) {
            val dm = resources.displayMetrics
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
    }

    private fun releaseCapture() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
    }

    /** 从 ImageReader 取最新一帧。必须在 IO 线程调用。 */
    private fun grabFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val w = screenW
        val h = screenH
        if (w <= 0 || h <= 0) return null

        var image: Image? = null
        val deadline = SystemClock.elapsedRealtime() + 1500
        while (SystemClock.elapsedRealtime() < deadline) {
            image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (image != null) break
            try {
                Thread.sleep(12)
            } catch (_: InterruptedException) {
                return null
            }
        }
        val img = image ?: return null

        return try {
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val bmpW = w + rowPadding / pixelStride

            val raw = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)
            if (bmpW != w) {
                val cropped = Bitmap.createBitmap(raw, 0, 0, w, h)
                raw.recycle()
                cropped
            } else {
                raw
            }
        } catch (e: Exception) {
            Log.e(TAG, "decode frame failed", e)
            null
        } finally {
            try {
                img.close()
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- 识别

    /**
     * 识别一次。
     *
     * @param onFinished 本次扫描彻底结束后的回调（主线程），自动模式靠它排下一次
     * @return false 表示这次没能启动（正在扫描 / 未授权 / 已达上限）
     */
    fun scan(onFinished: (() -> Unit)? = null): Boolean {
        if (scanning) return false

        if (projection == null || imageReader == null) {
            toast("请先点「开启屏幕捕获」授权")
            return false
        }

        if (NameStore.isFull) {
            vibrate()
            panel?.flashLimit(NameStore.limit)
            toast("已达上限 ${NameStore.limit} 个，先清空或调高上限")
            return false
        }

        scanning = true
        panel?.setBusy(true)

        scope.launch {
            try {
                panel?.hideForCapture()
                delay(180) // 等一帧，让悬浮窗从画面里消失

                val bitmap = withContext(Dispatchers.IO) { grabFrame() }
                panel?.restoreAfterCapture()
                panel?.setBusy(false)

                if (bitmap == null) {
                    toast("截屏失败，请重新开启屏幕捕获")
                    return@launch
                }

                val top = Prefs.regionTop(this@CaptureService)
                val bottom = Prefs.regionBottom(this@CaptureService)

                val outcome = try {
                    MicOcr.recognize(bitmap, top, bottom)
                } catch (e: Exception) {
                    Log.e(TAG, "ocr failed", e)
                    null
                } finally {
                    bitmap.recycle()
                }

                if (outcome == null) {
                    toast("识别出错，重试一次")
                    return@launch
                }

                val result = NameStore.add(outcome.names)
                refreshPanel()

                when {
                    result.reachedLimit -> {
                        // 自动模式：自己停下来，只提示一次
                        if (autoOn) {
                            stopAuto(reachedLimit = true)
                        } else {
                            vibrate()
                            panel?.flashLimit(NameStore.limit)
                            toast("已达上限 ${NameStore.limit} 个，已停止采集")
                        }
                    }

                    result.added == 0 -> {
                        // 自动模式下没人看着，重复了就安静跳过，不刷提示
                        if (!autoOn) {
                            val hint = when {
                                outcome.names.isEmpty() ->
                                    "这一屏没识别到名字，点「框选识别区域」调整范围"
                                else ->
                                    "这 ${outcome.names.size} 个都已在名单里"
                            }
                            panel?.flash(hint)
                        }
                    }

                    else -> {
                        if (autoOn) {
                            // 别把面板顶出来挡住画面
                            panel?.flash("新增 ${result.added} 个，共 ${result.total} 个", autoExpand = false)
                        } else {
                            panel?.flash("新增 ${result.added} 个，共 ${result.total} 个")
                            toast("新增 ${result.added} 个，共 ${result.total} 个")
                        }
                    }
                }
            } finally {
                scanning = false
                panel?.setBusy(false)
                onFinished?.invoke()
            }
        }
        return true
    }

    // ---------------------------------------------------------------- 自动识别

    private var autoOn = false

    /** 本应用 Activity 在前台时置 true：此时截图截到的是自己的界面，自动识别跳过 */
    @Volatile
    private var uiVisible = false

    private var nextScanAt = 0L

    val isAuto: Boolean get() = autoOn

    fun setUiVisible(v: Boolean) {
        uiVisible = v
    }

    fun toggleAuto() {
        if (autoOn) stopAuto() else startAuto()
    }

    /** 开启自动：立刻扫第一次，之后每 interval 秒一次；重复名字由 NameStore 去重 */
    fun startAuto(quiet: Boolean = false) {
        if (autoOn) return
        if (projection == null || imageReader == null) {
            toast("请先开启屏幕捕获")
            return
        }
        if (NameStore.isFull) {
            toast("已达上限 ${NameStore.limit} 个，先清空或调高上限")
            return
        }
        autoOn = true
        val sec = Prefs.interval(this)
        refreshPanel()
        if (!quiet) {
            panel?.flash("自动识别已开启 · 每 $sec 秒一次")
            toast("自动识别已开启，每 $sec 秒识别一次")
        }
        main.removeCallbacks(autoTickRunnable)
        main.postDelayed(autoTickRunnable, 1000)
        scheduleAuto(0L)
    }

    fun stopAuto(reachedLimit: Boolean = false, quiet: Boolean = false) {
        val was = autoOn
        autoOn = false
        main.removeCallbacks(autoCycleRunnable)
        main.removeCallbacks(autoTickRunnable)
        nextScanAt = 0L
        if (was) {
            panel?.setStatus(null)
            refreshPanel()
        }
        when {
            reachedLimit -> {
                vibrate()
                panel?.flashLimit(NameStore.limit)
                toast("已达上限 ${NameStore.limit} 个，自动识别已停止")
            }

            was && !quiet -> {
                panel?.flash("自动识别已关闭")
                toast("自动识别已关闭")
            }
        }
    }

    private fun scheduleAuto(delayMs: Long) {
        if (!autoOn) return
        main.removeCallbacks(autoCycleRunnable)
        nextScanAt = SystemClock.elapsedRealtime() + delayMs
        if (delayMs <= 0L) main.post(autoCycleRunnable) else main.postDelayed(autoCycleRunnable, delayMs)
    }

    /** 到点触发一次扫描，扫完再排下一次 */
    private val autoCycleRunnable = object : Runnable {
        override fun run() {
            if (!autoOn) return
            if (NameStore.isFull) {
                stopAuto(reachedLimit = true)
                return
            }
            if (projection == null || imageReader == null) {
                toast("屏幕捕获已断开，自动识别停止")
                stopAuto(quiet = true)
                return
            }
            // 用户正在本应用里看名单，截到的是自己界面，稍后再来
            if (uiVisible) {
                scheduleAuto(2000)
                return
            }

            val started = scan {
                if (autoOn) scheduleAuto(Prefs.interval(this@CaptureService) * 1000L)
            }
            if (!started) scheduleAuto(3000) // 正被手动识别占用，3 秒后重试
        }
    }

    /** 悬浮条上的倒计时，让用户知道它真在跑 */
    private val autoTickRunnable = object : Runnable {
        override fun run() {
            if (!autoOn) return
            if (!scanning) {
                val remain = ((nextScanAt - SystemClock.elapsedRealtime()) / 1000L).toInt()
                panel?.setStatus(
                    when {
                        uiVisible -> "自动中 · 回到目标画面继续"
                        remain > 0 -> "自动中 · ${remain}s 后识别"
                        else -> "自动中…"
                    }
                )
            }
            main.postDelayed(this, 1000)
        }
    }

    fun copyToClipboard(): Boolean {
        val text = NameStore.exportText()
        if (text.isEmpty()) {
            toast("名单还是空的")
            panel?.flash("名单还是空的")
            return false
        }
        return try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("名单", text))
            toast("已复制 ${NameStore.size} 个名字")
            panel?.flash("已复制 ${NameStore.size} 个")
            true
        } catch (e: Exception) {
            toast("复制失败")
            false
        }
    }

    fun showRegionPicker() {
        if (projection == null) {
            toast("请先开启屏幕捕获")
            return
        }
        if (regionPicker != null) return

        panel?.hideForCapture()

        val picker = RegionPicker(
            this,
            getSystemService(WindowManager::class.java),
            Prefs.regionTop(this),
            Prefs.regionBottom(this)
        ) { top, bottom ->
            Prefs.setRegion(this, top, bottom)
            toast("识别区域已保存")
        }
        picker.onClosed = {
            regionPicker = null
            panel?.restoreAfterCapture()
        }
        regionPicker = picker
        picker.show()
    }

    fun togglePanel() {
        if (panel?.isShowing == true) {
            panel?.remove()
            panel = null
        } else {
            showPanel()
        }
    }

    // ---------------------------------------------------------------- 面板

    private fun showPanel() {
        if (panel?.isShowing == true) return
        val p = FloatingPanel(this, object : FloatingPanel.Callbacks {
            override fun onScan() {
                scan()
            }

            override fun onAuto() = toggleAuto()

            override fun onCopy() {
                copyToClipboard()
            }

            override fun onRegion() = showRegionPicker()

            override fun onClear() {
                NameStore.clear()
                refreshPanel()
                panel?.flash("已清空")
            }

            override fun onClose() {
                stopAuto(quiet = true)
                panel?.remove()
                panel = null
                toast("悬浮窗已关闭")
            }
        })
        p.show()
        panel = p
        refreshPanel()
    }

    private fun refreshPanel() {
        panel?.update(NameStore.size, NameStore.limit, autoOn, Prefs.interval(this))
        updateNotification()
    }

    // ---------------------------------------------------------------- 杂项

    private fun vibrate() {
        try {
            val v = getSystemService(Vibrator::class.java) ?: return
            val pattern = longArrayOf(0, 140, 90, 180)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "名单识别", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ch.enableVibration(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(
        text: String = if (autoOn) "自动识别中 · 已识别 ${NameStore.size}/${NameStore.limit}"
        else "已识别 ${NameStore.size}/${NameStore.limit}"
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("名单")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }
}
