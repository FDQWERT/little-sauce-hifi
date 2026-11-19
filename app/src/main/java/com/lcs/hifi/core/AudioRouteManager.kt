package com.lcs.hifi.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** 一处可用的 USB 音频输出（来自内核 snd-usb-audio，被 AudioFlinger 暴露） */
data class UsbOut(
    val id: Int,
    val name: String,
    val product: String,
    val type: Int,
    val rates: IntArray,
    val channels: IntArray,
    val address: String
) {
    val typeText: String
        get() = when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB DAC / 小尾巴"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 音频配件"
            else -> "USB 音频"
        }

    val rateText: String
        get() = if (rates.isEmpty()) "--" else rates.sorted().joinToString("/") { (it / 1000f).let { k -> if (k % 1f == 0f) k.toInt().toString() else k.toString() } } + " kHz"
}

/**
 * USB / 系统输出设备探测与采样率、位深协商。
 */
class AudioRouteManager(private val ctx: Context) {

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val systemRate: Int
        get() = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

    val systemFramesPerBuffer: Int
        get() = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256

    fun outputs(): List<AudioDeviceInfo> =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    fun usbOutputs(): List<UsbOut> = outputs()
        .filter { isUsbType(it.type) }
        .map {
            UsbOut(
                id = it.id,
                name = it.productName?.toString()?.takeIf { s -> s.isNotBlank() } ?: "USB 音频设备",
                product = it.productName?.toString() ?: "",
                type = it.type,
                rates = it.sampleRates ?: intArrayOf(),
                channels = it.channelCounts ?: intArrayOf(),
                address = it.address ?: ""
            )
        }

    fun deviceById(id: Int): AudioDeviceInfo? = outputs().firstOrNull { it.id == id }

    fun isUsbType(t: Int): Boolean =
        t == AudioDeviceInfo.TYPE_USB_DEVICE ||
            t == AudioDeviceInfo.TYPE_USB_HEADSET ||
            t == AudioDeviceInfo.TYPE_USB_ACCESSORY

    fun currentOutput(): AudioDeviceInfo? {
        // 优先 USB，其次任何 sink
        val list = outputs()
        return list.firstOrNull { isUsbType(it.type) } ?: list.firstOrNull()
    }

    /**
     * 选择输出采样率：
     * 1) 设备支持源采样率 → 直通，不重采样
     * 2) 设备有比源更高的 → 取最接近的更高值
     * 3) 否则取设备支持的最高值
     */
    fun pickRate(dev: AudioDeviceInfo?, srcRate: Int): Int {
        val rates = dev?.sampleRates?.filter { it > 0 } ?: emptyList()
        if (rates.isEmpty()) return srcRate
        if (rates.contains(srcRate)) return srcRate
        rates.filter { it >= srcRate }.minOrNull()?.let { return it }
        return rates.maxOrNull() ?: srcRate
    }

    fun pickChannels(dev: AudioDeviceInfo?, srcCh: Int): Int {
        val list = dev?.channelCounts?.filter { it > 0 } ?: return srcCh
        if (list.isEmpty()) return srcCh
        if (list.contains(srcCh)) return srcCh
        return if (srcCh >= 2 && list.contains(2)) 2 else list.minOrNull() ?: srcCh
    }

    fun channelMask(ch: Int): Int = when (ch) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> if (ch > 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
    }

    /** 系统/设备是否接受该 PCM 编码（用 getMinBufferSize 探测，返回 >0 即支持） */
    fun acceptsEncoding(rate: Int, ch: Int, encoding: Int): Boolean {
        return try {
            AudioTrack.getMinBufferSize(rate, channelMask(ch), encoding) > 0
        } catch (t: Throwable) {
            false
        }
    }

    fun pickEncoding(dev: AudioDeviceInfo?, rate: Int, ch: Int): Int {
        // float 交给 AudioFlinger 内部 float 混音链路最省一次转换
        if (acceptsEncoding(rate, ch, AudioFormat.ENCODING_PCM_FLOAT)) return AudioFormat.ENCODING_PCM_FLOAT
        if (acceptsEncoding(rate, ch, AudioFormat.ENCODING_PCM_16BIT)) return AudioFormat.ENCODING_PCM_16BIT
        return AudioFormat.ENCODING_PCM_16BIT
    }

    fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun registerDeviceCallback(cb: AudioManager.AudioDeviceCallback) {
        am.registerAudioDeviceCallback(cb, null)
    }

    fun unregisterDeviceCallback(cb: AudioManager.AudioDeviceCallback) {
        try {
            am.unregisterAudioDeviceCallback(cb)
        } catch (_: Throwable) {
        }
    }

    fun setStreamVolume(percent: Int) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (percent.coerceIn(0, 100) * max / 100f).toInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
    }

    fun streamVolumePercent(): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 70
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun maxStreamVolume(): Int = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
}
