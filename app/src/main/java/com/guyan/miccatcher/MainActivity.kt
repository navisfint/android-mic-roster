package com.guyan.miccatcher

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var tvCount: TextView
    private lateinit var tvLimit: TextView
    private lateinit var tvTip: TextView
    private lateinit var tvLimitSet: TextView
    private lateinit var tvRegion: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvAutoState: TextView
    private lateinit var btnAutoSwitch: TextView
    private lateinit var btnAutoInterval: TextView
    private lateinit var progress: ProgressBar
    private lateinit var rv: RecyclerView
    private lateinit var adapter: NameAdapter
    private lateinit var btnCapture: MaterialButton
    private lateinit var btnRecognize: MaterialButton

    private var waitingOverlay = false
    private var lastAutoState = false
    private var autoBarKey = ""

    /** 自动识别倒计时/状态的轻量刷新，只在界面可见时跑 */
    private val autoTicker = object : Runnable {
        override fun run() {
            val auto = CaptureService.instance?.isAuto == true
            if (auto != lastAutoState) {
                // 自动状态被别处改了（达上限自动停、悬浮窗里点的），整页刷新
                lastAutoState = auto
                refresh()
            } else {
                refreshAutoBar()
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    /**
     * 用独立的主线程 Handler，而不是 decorView.postDelayed：
     * 界面退到后台（moveTaskToBack）时 view 的 post 可能拿不到回调。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val storeListener: (List<String>) -> Unit = { refresh() }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureService.start(this, result.resultCode, result.data!!)
            toast("已开启，切到目标画面点悬浮球")
        } else {
            toast("没有屏幕录制权限就没法识别")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        NameStore.setLimit(Prefs.limit(this))

        tvCount = findViewById(R.id.tvCount)
        tvLimit = findViewById(R.id.tvLimit)
        tvTip = findViewById(R.id.tvTip)
        tvLimitSet = findViewById(R.id.tvLimitSet)
        tvRegion = findViewById(R.id.tvRegion)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvAutoState = findViewById(R.id.tvAutoState)
        btnAutoSwitch = findViewById(R.id.btnAutoSwitch)
        btnAutoInterval = findViewById(R.id.btnAutoInterval)
        progress = findViewById(R.id.progress)
        rv = findViewById(R.id.rv)
        btnCapture = findViewById(R.id.btnCapture)
        btnRecognize = findViewById(R.id.btnRecognize)

        adapter = NameAdapter(
            onDelete = { index -> NameStore.removeAt(index) },
            onEdit = { index -> editName(index) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<ImageView>(R.id.btnFloat).setOnClickListener { toggleFloat() }
        findViewById<View>(R.id.boxLimit).setOnClickListener { editLimit() }
        findViewById<View>(R.id.boxRegion).setOnClickListener { editRegion() }
        findViewById<View>(R.id.btnCopy).setOnClickListener { copyAll() }
        findViewById<View>(R.id.btnClear).setOnClickListener { confirmClear() }
        findViewById<View>(R.id.boxAuto).setOnClickListener { toggleAuto() }
        btnAutoSwitch.setOnClickListener { toggleAuto() }
        btnAutoInterval.setOnClickListener { editInterval() }

        btnCapture.setOnClickListener { requestCapture() }
        btnRecognize.setOnClickListener { recognizeOnce() }

        askNotificationPermission()
        NameStore.observe(storeListener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        CaptureService.instance?.setUiVisible(true)
        if (waitingOverlay && Settings.canDrawOverlays(this)) {
            waitingOverlay = false
            handlerPost(300) { requestCapture() }
        }
        refresh()
        mainHandler.removeCallbacks(autoTicker)
        mainHandler.postDelayed(autoTicker, 1000)
    }

    override fun onPause() {
        // 本界面在前台时截图截到的是自己，自动识别要跳过
        CaptureService.instance?.setUiVisible(false)
        mainHandler.removeCallbacks(autoTicker)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoTicker)
        NameStore.unobserve(storeListener)
        super.onDestroy()
    }

    // ------------------------------------------------------------ 操作

    private fun requestCapture() {
        // 没悬浮窗权限的话，目标画面里就没法点按钮，先去要权限
        if (!Settings.canDrawOverlays(this)) {
            waitingOverlay = true
            toast("请允许「显示在其他应用上层」，回来会自动继续")
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                toast("请手动到设置里开启悬浮窗权限")
            }
            return
        }

        // 已经在跑就先停掉，否则 Android 14 会拒绝第二次 createVirtualDisplay
        val wasRunning = CaptureService.instance != null
        if (wasRunning) {
            stopService(Intent(this, CaptureService::class.java))
        }

        handlerPost(if (wasRunning) 450 else 0) {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            try {
                captureLauncher.launch(mgr.createScreenCaptureIntent())
            } catch (e: Exception) {
                toast("无法发起屏幕捕获：${e.message}")
            }
        }
    }

    private fun recognizeOnce() {
        val svc = CaptureService.instance
        if (svc == null) {
            toast("请先点「开启屏幕捕获」")
            return
        }
        // 本应用界面盖在目标画面上面，先退到后台让目标画面露出来再截
        moveTaskToBack(true)
        toast("正在识别，请让目标画面停在屏幕上")
        handlerPost(900) { svc.scan() }
    }

    private fun toggleFloat() {
        val svc = CaptureService.instance
        if (svc == null) {
            toast("请先开启屏幕捕获")
            return
        }
        svc.togglePanel()
    }

    /** 自动识别开关。主界面盖在目标画面上，先退到后台再启动 */
    private fun toggleAuto() {
        val svc = CaptureService.instance
        if (svc == null) {
            toast("请先开启屏幕捕获")
            return
        }
        if (svc.isAuto) {
            svc.stopAuto()
            handlerPost(200) { refreshAutoBar() }
            return
        }
        if (NameStore.isFull) {
            toast("已达上限 ${NameStore.limit} 个，先清空或调高上限")
            return
        }
        moveTaskToBack(true)
        toast("正在开启自动识别，请让目标画面留在屏幕上")
        handlerPost(900) {
            svc.startAuto()
            refreshAutoBar()
        }
    }

    private fun editInterval() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.interval(this@MainActivity).toString())
            setSelection(text.length)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(10), dp(24), dp(0))
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("识别间隔")
            .setMessage("自动识别每隔多少秒跑一次（5 – 600 秒），重名会自动排除")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v == null || v < 5) {
                    toast("请输入 5 以上的秒数")
                    return@setPositiveButton
                }
                Prefs.setInterval(this, v)
                // 正在自动跑的话，按新间隔重新排期
                CaptureService.instance?.let { svc ->
                    if (svc.isAuto) {
                        svc.stopAuto(quiet = true)
                        svc.startAuto(quiet = true)
                    }
                }
                refresh()
            }
            .show()
    }

    private fun copyAll() {
        if (NameStore.size == 0) {
            toast("名单还是空的")
            return
        }
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("名单", NameStore.exportText()))
            toast("已复制 ${NameStore.size} 个名字")
        } catch (e: Exception) {
            toast("复制失败")
        }
    }

    /** 长按列表里的名字：手工纠正 OCR 认错的字 */
    private fun editName(index: Int) {
        val cur = NameStore.list.getOrNull(index) ?: return
        val input = EditText(this).apply {
            setText(cur)
            setSelection(text.length)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(10), dp(24), dp(0))
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("修正名字")
            .setMessage("改完列表和复制都用新名字；原来那个错字形态不会被自动加回来")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                when {
                    input.text.toString().isBlank() -> toast("名字不能为空")
                    NameStore.updateAt(index, input.text.toString()) -> toast("已更新")
                    else -> toast("跟列表里其它名字重复了")
                }
            }
            .show()
    }

    private fun confirmClear() {
        if (NameStore.size == 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle("清空名单？")
            .setMessage("当前 ${NameStore.size} 个名字会全部删掉，不可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                NameStore.clear()
                toast("已清空")
            }
            .show()
    }

    private fun editLimit() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(NameStore.limit.toString())
            setSelection(text.length)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(10), dp(24), dp(0))
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("数量上限")
            .setMessage("识别到这么多个名字后自动停止采集")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v == null || v < 1) {
                    toast("请输入大于 0 的数字")
                    return@setPositiveButton
                }
                val clamped = v.coerceIn(1, 999)
                NameStore.setLimit(clamped)
                Prefs.setLimit(this, clamped)
                refresh()
            }
            .show()
    }

    private fun editRegion() {
        val svc = CaptureService.instance
        if (svc == null) {
            toast("请先开启屏幕捕获，再去目标画面框选")
            return
        }
        // 切到后台，让目标画面画面露出来，再叠一层框选浮层
        moveTaskToBack(true)
        handlerPost(500) { svc.showRegionPicker() }
    }

    // ------------------------------------------------------------ UI

    private fun refresh() {
        val limit = NameStore.limit
        val size = NameStore.size

        tvCount.text = size.toString()
        tvLimit.text = "/ $limit"
        progress.max = limit
        progress.progress = size
        tvLimitSet.text = "$limit 个"

        val top = (Prefs.regionTop(this) * 100).toInt()
        val bottom = (Prefs.regionBottom(this) * 100).toInt()
        tvRegion.text = "$top% – $bottom%"

        adapter.submit(NameStore.list)
        tvEmpty.visibility = if (size == 0) View.VISIBLE else View.GONE

        val running = CaptureService.instance != null
        val auto = CaptureService.instance?.isAuto == true
        btnCapture.text = if (running) "重新授权截屏" else "开启屏幕捕获"
        btnRecognize.isEnabled = running
        btnRecognize.alpha = if (running) 1f else 0.5f

        tvTip.text = when {
            !running -> "点「开启屏幕捕获」，之后在目标画面点悬浮球就能识别"
            NameStore.isFull -> "已达上限 $limit 个，清空名单或调高上限后继续"
            auto -> "自动识别中 · 已收集 $size 个，还差 ${limit - size} 个到上限"
            size == 0 -> "已就绪，去目标画面点悬浮球上的「识别一次」"
            else -> "还差 ${limit - size} 个达到上限"
        }
        tvTip.setTextColor(
            ContextCompat.getColor(
                this,
                if (NameStore.isFull) R.color.warn else R.color.ink3
            )
        )

        refreshAutoBar()
    }

    /** 自动识别状态条：运行中显示绿字 + 停止按钮，关闭时显示开启按钮 */
    private fun refreshAutoBar() {
        val svc = CaptureService.instance
        val auto = svc?.isAuto == true
        val sec = Prefs.interval(this)

        // 每秒 tick 一次，状态没变就别重复设背景（省一次重绘）
        val key = "$auto|$sec|${svc != null}"
        if (key == autoBarKey) return
        autoBarKey = key

        btnAutoInterval.text = "每 $sec 秒"
        btnAutoSwitch.text = if (auto) "停止" else "开启"
        btnAutoSwitch.setBackgroundResource(
            if (auto) R.drawable.bg_pill_stop else R.drawable.bg_pill_primary
        )
        btnAutoSwitch.setTextColor(
            ContextCompat.getColor(this, if (auto) R.color.warn else R.color.white)
        )

        tvAutoState.text = when {
            svc == null -> "关闭 · 先开启屏幕捕获"
            auto -> "运行中 · 每 $sec 秒识别一次，重名自动排除"
            else -> "关闭 · 开启后按间隔自动识别，重名自动排除"
        }
        tvAutoState.setTextColor(
            ContextCompat.getColor(this, if (auto) R.color.ok else R.color.ink3)
        )
    }

    // ------------------------------------------------------------ 杂项

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        try {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        } catch (_: Exception) {
        }
    }

    private fun handlerPost(delayMs: Long, block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        mainHandler.postDelayed(block, delayMs)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
