package com.lcs.hifi.audio

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.lcs.hifi.core.AudioRouteManager
import com.lcs.hifi.core.RawUsb
import com.lcs.hifi.core.UacDescriptor
import com.lcs.hifi.core.UsbHub
import com.lcs.hifi.native.UsbIso
import com.lcs.hifi.util.PcmCodec
import kotlin.math.max

/** 输出端点抽象：模式 A 走 AudioTrack，模式 B 走 USB 独占 */
interface AudioSink {
    val label: String
    val detail: String

    /** open() 成功后实际生效的格式（可能被 DAC/设备修正） */
    val actualRate: Int
    val actualChannels: Int
    val actualEncoding: Int

    fun open(rate: Int, channels: Int, encoding: Int, device: AudioDeviceInfo?): Boolean
    fun write(buf: ByteArray, offset: Int, size: Int): Int
    fun flush()
    fun drain()
    fun close()
}

/**
 * 模式 A：AudioTrack + setPreferredDevice 强制路由到 USB 设备。
 *
 * 优点：任何小尾巴都能出声，采样率随曲目切换，不需要特殊权限。
 * 缺点：数据仍经 AudioFlinger，无法保证逐位一致（float 混音/音量处理）。
 */
class AudioTrackSink(private val route: AudioRouteManager) : AudioSink {

    private companion object {
        const val TAG = "LCS-AudioTrack"
    }


    private var track: AudioTrack? = null
    private var bufferBytes = 0
    private var openedRate = 0
    private var openedChannels = 0
    private var openedEncoding = 0

    override val actualRate: Int get() = openedRate
    override val actualChannels: Int get() = openedChannels
    override val actualEncoding: Int get() = openedEncoding

    override val label: String get() = "系统 USB 路由"
    override val detail: String
        get() = if (track != null)
            "${openedRate / 1000f}kHz/${PcmCodec.nameOf(openedEncoding)}/buffer ${bufferBytes / 1024}KB"
        else "未打开"

    override fun open(rate: Int, channels: Int, encoding: Int, device: AudioDeviceInfo?): Boolean {
        close()
        val mask = route.channelMask(channels)

        // FLOAT 在部分 USB 通路上打开成功但写不进去，退回 16bit 保底出声
        val candidates = if (encoding == AudioFormat.ENCODING_PCM_FLOAT)
            intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
        else intArrayOf(encoding)

        for (enc in candidates) {
            val t = tryOpen(rate, channels, mask, enc, device) ?: continue
            track = t
            openedRate = rate
            openedChannels = channels
            openedEncoding = enc
            return true
        }
        return false
    }

    /** 建 track + 路由 + 静音探测；任一步失败返回 null 并清理 */
    private fun tryOpen(rate: Int, channels: Int, mask: Int, enc: Int, device: AudioDeviceInfo?): AudioTrack? {
        val minBuf = try {
            AudioTrack.getMinBufferSize(rate, mask, enc)
        } catch (t: Throwable) {
            AudioTrack.ERROR
        }
        if (minBuf <= 0) {
            Log.w(TAG, "getMinBufferSize fail rate=$rate enc=$enc min=$minBuf")
            return null
        }

        val frameBytes = channels * PcmCodec.bytesPerSample(enc)
        // 0.35s 缓冲：USB 时钟抖动大，太小会爆音
        val want = max(minBuf * 2, rate * frameBytes / 3)

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(route.audioAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(enc)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(want)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t2: Throwable) {
            Log.w(TAG, "AudioTrack build fail rate=$rate enc=$enc", t2)
            return null
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack state=${t.state} rate=$rate enc=$enc")
            try {
                t.release()
            } catch (_: Throwable) {
            }
            return null
        }

        if (device != null) {
            try {
                t.setPreferredDevice(device)
            } catch (_: Throwable) {
            }
        }
        // 交给 AudioFlinger 前不做软件音量缩放，音量交给系统 stream volume
        t.setVolume(1f)

        try {
            t.play()
        } catch (t2: Throwable) {
            try {
                t.release()
            } catch (_: Throwable) {
            }
            return null
        }

        // 静音探测：确认真能写进去（有些通路 open 成功但 write 直接报错）
        val probe = ByteArray(frameBytes * 240)
        val w = try {
            t.write(probe, 0, probe.size, AudioTrack.WRITE_BLOCKING)
        } catch (t2: Throwable) {
            Log.w(TAG, "probe exception", t2)
            -1
        }
        if (w <= 0) {
            Log.w(TAG, "probe fail w=$w state=${t.state} play=${t.playState} rate=$rate enc=$enc")
            try {
                t.pause()
                t.flush()
                t.release()
            } catch (_: Throwable) {
            }
            return null
        }
        try {
            t.pause()
            t.flush()
            t.play()
        } catch (_: Throwable) {
        }

        bufferBytes = want
        Log.i(TAG, "AudioTrack ok rate=$rate ch=$channels enc=$enc buffer=$want")
        return t
    }

    override fun write(buf: ByteArray, offset: Int, size: Int): Int {
        val t = track ?: return -1
        return try {
            val w = t.write(buf, offset, size, AudioTrack.WRITE_BLOCKING)
            if (w < 0) {
                Log.e(TAG, "write=$w state=${t.state} play=${t.playState} size=$size off=$offset")
            }
            w
        } catch (t2: Throwable) {
            Log.e(TAG, "write exception", t2)
            -1
        }
    }

    override fun flush() {
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (_: Throwable) {
        }
    }

    override fun drain() {
        try {
            if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) track?.stop()
        } catch (_: Throwable) {
        }
    }

    override fun close() {
        val t = track ?: return
        track = null
        try {
            t.pause()
            t.flush()
            t.stop()
        } catch (_: Throwable) {
        }
        try {
            t.release()
        } catch (_: Throwable) {
        }
    }
}

/**
 * 模式 B：UAC 独占直通。
 *
 * 步骤：读配置描述符 → 选 alt setting → setSampleRate(control transfer)
 *      → claimInterface + setInterface(alt) → isochronous 写。
 * 任一步失败返回 false，上层回落模式 A，并把原因显示到 USB 页。
 */
class DirectUsbSink(
    private val ctx: Context,
    private val hub: UsbHub,
    private val target: RawUsb
) : AudioSink {

    private var handle = 0L
    private var conn: UsbDeviceConnection? = null
    private var chosenAlt: com.lcs.hifi.core.UacAlt? = null
    private var openedRate = 0
    private var openedBytes = 0

    var failReason: String = ""
        private set

    override val actualRate: Int get() = openedRate
    override val actualChannels: Int get() = chosenAlt?.channels ?: 0
    /** 以描述符里的子帧字节数为准，保证编码字节数与端点帧长严格一致 */
    override val actualEncoding: Int
        get() = when (chosenAlt?.subframeBytes ?: 2) {
            2 -> AudioFormat.ENCODING_PCM_16BIT
            3 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            4 -> AudioFormat.ENCODING_PCM_32BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

    override val label: String get() = "独占直连 (bit-perfect)"
    override val detail: String
        get() {
            val a = chosenAlt ?: return "未打开"
            return "${openedRate / 1000f}kHz/${a.bits}bit/${a.channels}ch · alt${a.alt} · ep 0x${Integer.toHexString(a.epAddr)}"
        }

    fun statsText(): String = if (handle == 0L) "" else UsbIso.nativeStats(handle)

    override fun open(rate: Int, channels: Int, encoding: Int, device: AudioDeviceInfo?): Boolean {
        failReason = ""
        if (!UsbIso.available) {
            failReason = "native 直通库未装载，回落系统路由"
            return false
        }
        if (!target.hasPermission) {
            failReason = "USB 设备未授权"
            return false
        }

        val c = hub.open(target.device)
        if (c == null) {
            failReason = "openDevice 失败（可能被系统音频服务占用）"
            return false
        }

        val cfg = UacDescriptor.readConfig(c)
        if (cfg == null) {
            failReason = "读取 USB 配置描述符失败"
            c.close()
            return false
        }
        val parsed = UacDescriptor.parse(cfg)
        if (parsed.alts.isEmpty()) {
            failReason = "未在描述符中找到 UAC streaming 端点"
            c.close()
            return false
        }

        val bytesPerSample = PcmCodec.bytesPerSample(encoding).coerceIn(1, 4)
        val wantBits = bytesPerSample * 8
        val alt = UacDescriptor.pickAlt(parsed.alts, rate, channels, wantBits)
        if (alt == null) {
            failReason = "没有可用的 ${rate}Hz/${channels}ch 格式"
            c.close()
            return false
        }

        // 先设采样率（必须在 setInterface 之前，部分固件要求）
        UacDescriptor.setSampleRate(c, alt.ifIndex, alt.epAddr, parsed.clockId, parsed.uacVersion, rate)

        // wMaxPacketSize 低 11 位是每 microframe 包长，高 2 位是额外传输次数
        val rawPacket = alt.epPacket
        val packet = rawPacket and 0x7FF
        val mult = ((rawPacket shr 11) and 0x3) + 1
        if (packet <= 0) {
            failReason = "端点包长为 0"
            c.close()
            return false
        }
        if (mult > 1) {
            failReason = "该端点使用高带宽多包模式（mult=$mult），独占直连暂不支持"
            c.close()
            return false
        }

        val h = try {
            UsbIso.nativeOpen(
                c.fileDescriptor,
                alt.ifIndex,
                alt.alt,
                alt.epAddr,
                packet,
                alt.epInterval,
                alt.subframeBytes,
                alt.channels,
                rate
            )
        } catch (t: Throwable) {
            failReason = "nativeOpen 异常: ${t.message}"
            c.close()
            return false
        }

        if (h == 0L) {
            failReason = "抢占 UAC 接口失败: ${runCatching { UsbIso.nativeLastError() }.getOrNull() ?: "unknown"}"
            c.close()
            return false
        }

        if (UsbIso.nativeStart(h) != 0) {
            failReason = "启动 isochronous 传输失败: ${runCatching { UsbIso.nativeLastError() }.getOrNull() ?: "unknown"}"
            UsbIso.nativeClose(h)
            c.close()
            return false
        }

        handle = h
        conn = c
        chosenAlt = alt
        openedRate = rate
        openedBytes = alt.subframeBytes
        return true
    }

    override fun write(buf: ByteArray, offset: Int, size: Int): Int {
        if (handle == 0L) return -1
        return try {
            UsbIso.nativeWrite(handle, buf, offset, size)
        } catch (t: Throwable) {
            -1
        }
    }

    override fun flush() {
        // 独占模式下 flush 等于丢弃 ring buffer 中未发出的数据
    }

    override fun drain() {
        try {
            Thread.sleep(60)
        } catch (_: InterruptedException) {
        }
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) {
            try {
                UsbIso.nativeStop(h)
            } catch (_: Throwable) {
            }
            try {
                UsbIso.nativeClose(h)
            } catch (_: Throwable) {
            }
        }
        try {
            conn?.close()
        } catch (_: Throwable) {
        }
        conn = null
        chosenAlt = null
    }
}
