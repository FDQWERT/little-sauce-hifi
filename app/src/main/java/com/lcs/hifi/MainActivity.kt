package com.lcs.hifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lcs.hifi.audio.MediaScanner
import com.lcs.hifi.audio.PlaybackEngine
import com.lcs.hifi.audio.PlaybackService
import com.lcs.hifi.audio.PlayerHub
import com.lcs.hifi.audio.Track
import com.lcs.hifi.core.AudioRouteManager
import com.lcs.hifi.core.UsbHub
import com.lcs.hifi.ui.TrackAdapter
import com.lcs.hifi.ui.UsbRows
import com.lcs.hifi.util.PcmCodec
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), PlaybackEngine.Listener {

    companion object {
        private const val REQ_PERM = 1001
    }

    private lateinit var engine: PlaybackEngine
    private lateinit var route: AudioRouteManager
    private lateinit var usbHub: UsbHub

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var pages: List<View>
    private lateinit var adapter: TrackAdapter

    // 顶栏
    private lateinit var usbDot: View
    private lateinit var usbHeaderText: TextView

    // 播放页
    private lateinit var trackTitle: TextView
    private lateinit var trackMeta: TextView
    private lateinit var outDevice: TextView
    private lateinit var outMode: TextView
    private lateinit var outFormat: TextView
    private lateinit var bitPerfectBadge: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeCur: TextView
    private lateinit var timeTotal: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var volumeBar: SeekBar
    private lateinit var playerHint: TextView
    private var activeMode: PlaybackEngine.Mode = PlaybackEngine.Mode.SYSTEM

    // USB 页
    private lateinit var modeSystemCard: View
    private lateinit var modeSystemTag: TextView
    private lateinit var modeDirectCard: View
    private lateinit var modeDirectTag: TextView
    private lateinit var directStatus: TextView
    private lateinit var usbOutContainer: ViewGroup
    private lateinit var rawUsbContainer: ViewGroup
    private lateinit var btnRescan: View
    private lateinit var usbTip: TextView

    // 曲库页
    private lateinit var scanStatus: TextView
    private lateinit var btnScan: View

    private var dragging = false
    private var scanning = false
    private var plugReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PlayerHub.init(this)
        engine = PlayerHub.engine!!
        route = PlayerHub.route
        usbHub = PlayerHub.usb

        bindViews()
        setupNav()
        setupPlayer()
        setupUsb()
        setupLibrary()

        PlayerHub.addListener(this)
        PlaybackService.ensure(this)

        seekBar.max = 1000
        volumeBar.max = 100

        ensurePermissions()
        refreshUsb()
    }

    override fun onResume() {
        super.onResume()
        registerPlugReceiver()
        refreshUsb()
    }

    override fun onPause() {
        unregisterPlugReceiver()
        super.onPause()
    }

    override fun onDestroy() {
        PlayerHub.removeListener(this)
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 绑定

    private fun bindViews() {
        val container = findViewById<ViewGroup>(R.id.pageContainer)
        pages = (0 until container.childCount).map { container.getChildAt(it) }

        usbDot = findViewById(R.id.usbDot)
        usbHeaderText = findViewById(R.id.usbHeaderText)

        trackTitle = findViewById(R.id.trackTitle)
        trackMeta = findViewById(R.id.trackMeta)
        outDevice = findViewById(R.id.outDevice)
        outMode = findViewById(R.id.outMode)
        outFormat = findViewById(R.id.outFormat)
        bitPerfectBadge = findViewById(R.id.bitPerfectBadge)
        seekBar = findViewById(R.id.seekBar)
        timeCur = findViewById(R.id.timeCur)
        timeTotal = findViewById(R.id.timeTotal)
        btnPlay = findViewById(R.id.btnPlay)
        volumeBar = findViewById(R.id.volumeBar)
        playerHint = findViewById(R.id.playerHint)

        modeSystemCard = findViewById(R.id.modeSystemCard)
        modeSystemTag = findViewById(R.id.modeSystemTag)
        modeDirectCard = findViewById(R.id.modeDirectCard)
        modeDirectTag = findViewById(R.id.modeDirectTag)
        directStatus = findViewById(R.id.directStatus)
        usbOutContainer = findViewById(R.id.usbOutContainer)
        rawUsbContainer = findViewById(R.id.rawUsbContainer)
        btnRescan = findViewById(R.id.btnRescan)
        usbTip = findViewById(R.id.usbTip)

        scanStatus = findViewById(R.id.scanStatus)
        btnScan = findViewById(R.id.btnScan)
    }

    private fun setupNav() {
        findViewById<View>(R.id.navPlayer).setOnClickListener { showPage(0) }
        findViewById<View>(R.id.navUsb).setOnClickListener { showPage(1) }
        findViewById<View>(R.id.navLibrary).setOnClickListener { showPage(2) }

        findViewById<ImageButton>(R.id.btnLibrary).setOnClickListener { showPage(2) }
        showPage(0)
    }

    private fun showPage(i: Int) {
        pages.forEachIndexed { idx, v ->
            v.visibility = if (idx == i) View.VISIBLE else View.GONE
        }
        val on = 0xFFFF9A3C.toInt()
        val off = 0xFF5C616C.toInt()
        val navs = listOf(
            R.id.navPlayerIcon to R.id.navPlayerText,
            R.id.navUsbIcon to R.id.navUsbText,
            R.id.navLibraryIcon to R.id.navLibraryText
        )
        navs.forEachIndexed { idx, (iconId, textId) ->
            val c = if (idx == i) on else off
            findViewById<ImageView>(iconId).setColorFilter(c)
            findViewById<TextView>(textId).setTextColor(c)
        }
    }

    private fun setupPlayer() {
        btnPlay.setOnClickListener { engine.toggle() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { engine.previous() }
        findViewById<View>(R.id.btnNext).setOnClickListener { engine.next() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeCur.text = PcmCodec.timeText(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
                sb?.let { engine.seekTo(it.progress.toLong()) }
            }
        })

        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.let { applyVolume(it.progress) }
            }
        })

        timeTotal.text = "0:00"
        timeCur.text = "0:00"
        trackTitle.text = "未在播放"
        trackMeta.text = "从曲库选一首歌"
        outDevice.text = "—"
        outMode.text = "系统 USB 路由"
        outFormat.text = "—"
        bitPerfectBadge.visibility = View.GONE
        playerHint.text = "插入 USB DAC 后，在「USB 直连」页选择输出模式"
    }

    private fun applyVolume(p: Int) {
        val pct = p.coerceIn(0, 100)
        if (activeMode == PlaybackEngine.Mode.DIRECT) {
            engine.softwareVolume = pct / 100f
            playerHint.text =
                if (pct >= 100) "独占模式：满电平直通（bit-perfect）"
                else "独占模式：软件音量 $pct%（会改变采样值，非 bit-perfect）"
        } else {
            if (engine.mode == PlaybackEngine.Mode.DIRECT) engine.softwareVolume = 1f
            route.setStreamVolume(pct)
            playerHint.text = "系统 USB 路由：音量由 AudioFlinger 处理，$pct%"
        }
    }

    // ------------------------------------------------------------ USB 页

    private fun setupUsb() {
        btnRescan.setOnClickListener { refreshUsb() }

        modeSystemCard.setOnClickListener {
            engine.mode = PlaybackEngine.Mode.SYSTEM
            refreshUsb()
        }
        modeDirectCard.setOnClickListener {
            val t = engine.directTarget
            if (t == null) {
                toast("先在下方的 USB 设备列表里选一个 DAC")
                return@setOnClickListener
            }
            engine.mode = PlaybackEngine.Mode.DIRECT
            refreshUsb()
        }

        usbTip.text =
            "系统路由：任何小尾巴都能用，采样率随曲目切换，但数据经 AudioFlinger 混音。\n\n" +
                "独占直连：绕过 AudioFlinger 与系统音量，按 DAC 支持的格式逐位直写，" +
                "切歌时自动重设采样率。需要 USB 授权；失败会自动回落系统路由并在播放页说明原因。"
    }

    private fun refreshUsb() {
        val outs = try {
            route.usbOutputs()
        } catch (t: Throwable) {
            emptyList()
        }
        val raws = try {
            usbHub.audioDevices()
        } catch (t: Throwable) {
            emptyList()
        }

        val connected = outs.isNotEmpty() || raws.isNotEmpty()
        usbDot.setBackgroundResource(
            if (connected) R.drawable.bg_dot_on else R.drawable.bg_dot_off
        )
        usbHeaderText.text = when {
            outs.isNotEmpty() -> outs[0].name
            raws.isNotEmpty() -> raws[0].title
            else -> "无 USB 音频"
        }

        // 系统音频输出
        usbOutContainer.removeAllViews()
        if (outs.isEmpty()) {
            usbOutContainer.addView(
                UsbRows.emptyRow(
                    this, usbOutContainer,
                    "系统未识别到 USB 音频输出（插好小尾巴试试）"
                )
            )
        } else {
            outs.forEach { o ->
                val selected = engine.preferredDeviceId == o.id
                usbOutContainer.addView(
                    UsbRows.audioRow(this, usbOutContainer, o, selected) {
                        engine.preferredDeviceId = o.id
                        engine.mode = PlaybackEngine.Mode.SYSTEM
                        refreshUsb()
                    }
                )
            }
        }

        // 原始 USB 设备（用于独占直连）
        rawUsbContainer.removeAllViews()
        if (raws.isEmpty()) {
            rawUsbContainer.addView(
                UsbRows.emptyRow(
                    this, rawUsbContainer,
                    "没有检测到 USB Audio 类设备"
                )
            )
        } else {
            raws.forEach { r ->
                val isTarget = engine.directTarget?.deviceName == r.deviceName
                rawUsbContainer.addView(
                    UsbRows.rawRow(this, rawUsbContainer, r, isTarget) {
                        onPickRaw(r.deviceName, r.hasPermission)
                    }
                )
            }
        }

        // 模式卡片
        val direct = engine.mode == PlaybackEngine.Mode.DIRECT
        modeSystemTag.text = if (direct) "可切换" else "使用中"
        modeSystemTag.setTextColor(if (direct) 0xFF8B909B.toInt() else 0xFF35D6C4.toInt())
        modeSystemCard.setBackgroundResource(
            if (direct) R.drawable.bg_card else R.drawable.bg_card_hi
        )
        modeDirectTag.text = if (direct) "使用中" else "可切换"
        modeDirectTag.setTextColor(if (direct) 0xFF35D6C4.toInt() else 0xFF8B909B.toInt())
        modeDirectCard.setBackgroundResource(
            if (direct) R.drawable.bg_card_hi else R.drawable.bg_card
        )

        directStatus.text = when {
            engine.directTarget == null && direct -> "已选择独占模式，但还没选择 DAC —— 播放会回落系统路由"
            engine.directTarget == null -> "尚未选择直连设备"
            direct -> {
                val t = engine.directTarget!!
                val fb = engine.lastFallback()
                if (fb.isBlank()) "独占直连已启用：${t.title}"
                else "独占直连失败已回落：$fb"
            }
            else -> "直连目标：${engine.directTarget!!.title}（未启用）"
        }
        directStatus.setTextColor(
            if (direct && engine.directTarget != null && engine.lastFallback().isBlank())
                0xFF4ADE80.toInt() else 0xFF8B909B.toInt()
        )

        volumeBar.progress = if (direct) (engine.softwareVolume * 100).toInt()
        else route.streamVolumePercent()
    }

    private fun onPickRaw(deviceName: String, hasPermission: Boolean) {
        val raw = try {
            usbHub.audioDevices().firstOrNull { it.deviceName == deviceName }
        } catch (t: Throwable) {
            null
        }
        if (raw == null) {
            toast("设备已断开")
            refreshUsb()
            return
        }
        if (!hasPermission) {
            usbHub.requestPermission(raw.device) { ok ->
                runOnUiThread {
                    if (ok) {
                        engine.directTarget = raw
                        engine.mode = PlaybackEngine.Mode.DIRECT
                        toast("已授权并启用独占直连")
                    } else {
                        toast("USB 授权被拒绝")
                    }
                    refreshUsb()
                }
            }
        } else {
            engine.directTarget = raw
            engine.mode = PlaybackEngine.Mode.DIRECT
            toast("已选择 ${raw.title}")
            refreshUsb()
        }
    }

    private fun registerPlugReceiver() {
        if (plugReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                refreshUsb()
            }
        }
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            ContextCompat.registerReceiver(this, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            plugReceiver = r
        } catch (t: Throwable) {
            // 忽略：部分 ROM 限制注册
        }
    }

    private fun unregisterPlugReceiver() {
        plugReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Throwable) {
            }
        }
        plugReceiver = null
    }

    // ------------------------------------------------------------ 曲库

    private fun setupLibrary() {
        adapter = TrackAdapter { idx ->
            engine.playAt(idx)
            showPage(0)
        }
        findViewById<RecyclerView>(R.id.trackList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true)
        }
        btnScan.setOnClickListener { scanLibrary(force = true) }
        scanStatus.text = "等待授权…"
    }

    private fun ensurePermissions() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) need.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (need.isEmpty()) scanLibrary() else requestPermissions(need.toTypedArray(), REQ_PERM)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            val ok = permissions.indices.any {
                permissions[it] == Manifest.permission.READ_MEDIA_AUDIO ||
                    permissions[it] == Manifest.permission.READ_EXTERNAL_STORAGE
            } && grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            if (ok) scanLibrary()
            else scanStatus.text = "没有媒体读取权限，无法扫描本地音乐"
        }
    }

    private fun scanLibrary(force: Boolean = false) {
        if (scanning && !force) return
        if (scanning) return
        scanning = true
        scanStatus.text = "扫描中…"
        io.execute {
            val list = try {
                MediaScanner.scan(this)
            } catch (t: Throwable) {
                emptyList<Track>()
            }
            runOnUiThread {
                scanning = false
                engine.setPlaylist(list)
                adapter.submit(list)
                scanStatus.text = when {
                    list.isEmpty() -> "没有找到音频文件（支持 FLAC/WAV/MP3/M4A/OGG/APE/DSF）"
                    else -> "共 ${list.size} 首 · 点任意一首开始播放"
                }
            }
        }
    }

    // ------------------------------------------------------------ 引擎回调（主线程）

    override fun onTrack(index: Int, track: Track, durationMs: Long) {
        trackTitle.text = track.title
        trackMeta.text = track.subtitle
        timeTotal.text = PcmCodec.timeText(durationMs)
        adapter.playingIndex = index
        seekBar.progress = 0
        timeCur.text = "0:00"
    }

    override fun onPlaying(playing: Boolean) {
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    override fun onProgress(positionMs: Long) {
        if (!dragging) {
            val total = engine.currentTrack()?.durationMs ?: 0L
            if (total > 0) {
                seekBar.progress = ((positionMs.toDouble() / total) * 1000).toInt().coerceIn(0, 1000)
            }
            timeCur.text = PcmCodec.timeText(positionMs)
        }
    }

    override fun onOutput(info: PlaybackEngine.OutputInfo?) {
        if (info == null) {
            outDevice.text = "—"
            return
        }
        activeMode = info.mode
        outDevice.text = info.deviceName
        outMode.text = if (info.mode == PlaybackEngine.Mode.DIRECT) "独占直连" else "系统 USB 路由"
        outFormat.text =
            "${info.rate / 1000f}kHz · ${PcmCodec.nameOf(info.encoding)} · ${info.channels}ch"
        bitPerfectBadge.visibility = if (info.bitPerfect) View.VISIBLE else View.GONE
        playerHint.text = if (info.note.isBlank()) {
            if (info.bitPerfect) "逐位直通，未经过系统混音" else "经 AudioFlinger 输出"
        } else info.note
        refreshUsb()
    }

    override fun onError(message: String) {
        toast(message)
        playerHint.text = message
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
