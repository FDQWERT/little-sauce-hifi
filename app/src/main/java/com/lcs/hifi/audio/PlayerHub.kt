package com.lcs.hifi.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lcs.hifi.core.AudioRouteManager
import com.lcs.hifi.core.UsbHub
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局播放中枢：持有唯一的 [PlaybackEngine]，把回调广播给所有 UI/通知监听者。
 * 回调统一 post 到主线程，UI 侧可以直接改控件。
 */
object PlayerHub : PlaybackEngine.Listener {

    @Volatile private var initialized = false

    lateinit var route: AudioRouteManager
        private set
    lateinit var usb: UsbHub
        private set

    var engine: PlaybackEngine? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<PlaybackEngine.Listener>()

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = ctx.applicationContext
            route = AudioRouteManager(app)
            usb = UsbHub(app)
            val e = PlaybackEngine(app, route, usb)
            e.listener = this
            engine = e
            initialized = true
        }
    }

    fun addListener(l: PlaybackEngine.Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: PlaybackEngine.Listener) {
        listeners.remove(l)
    }

    // ------------------------------------------------ 转发（主线程）

    override fun onTrack(index: Int, track: Track, durationMs: Long) {
        main.post { for (l in listeners) l.onTrack(index, track, durationMs) }
    }

    override fun onPlaying(playing: Boolean) {
        main.post { for (l in listeners) l.onPlaying(playing) }
    }

    override fun onProgress(positionMs: Long) {
        main.post { for (l in listeners) l.onProgress(positionMs) }
    }

    override fun onOutput(info: PlaybackEngine.OutputInfo?) {
        main.post { for (l in listeners) l.onOutput(info) }
    }

    override fun onError(message: String) {
        main.post { for (l in listeners) l.onError(message) }
    }
}
