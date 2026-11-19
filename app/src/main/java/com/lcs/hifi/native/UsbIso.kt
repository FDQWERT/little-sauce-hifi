package com.lcs.hifi.native

import java.nio.ByteBuffer

/**
 * USB isochronous 独占输出（native / usbfs）。
 *
 * 走的是 Linux usbfs 的 USBDEVFS_SUBMITURB + isochronous URB 队列，
 * 自己补齐每个 microframe 的数据包，直接喂给 UAC streaming 端点，
 * 中间没有 AudioFlinger、没有系统音量、没有重采样。
 *
 * native 库缺失（withNative=false 或非支持 ABI）时 [available] 为 false，
 * 上层自动回落模式 A。
 */
object UsbIso {

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("hifiusb")
            true
        } catch (t: Throwable) {
            false
        }
    }

    val available: Boolean get() = loaded

    /** 返回 native 上下文句柄，0 表示失败（原因见 [nativeLastError]） */
    external fun nativeOpen(
        fd: Int,
        ifIndex: Int,
        alt: Int,
        epAddr: Int,
        maxPacket: Int,
        interval: Int,
        bytesPerSample: Int,
        channels: Int,
        rate: Int
    ): Long

    external fun nativeStart(h: Long): Int

    /** 返回实际写入的字节数，<0 表示错误 */
    external fun nativeWrite(h: Long, buf: ByteArray, off: Int, len: Int): Int

    external fun nativeStop(h: Long)

    external fun nativeClose(h: Long)

    external fun nativeLastError(): String

    external fun nativeStats(h: Long): String

    /** 运行中切换 alt setting（切换格式用），返回 0 成功 */
    external fun nativeSetAlt(h: Long, alt: Int): Int

    external fun nativeFrames(h: Long): Long

    external fun nativeDropped(h: Long): Long
}
