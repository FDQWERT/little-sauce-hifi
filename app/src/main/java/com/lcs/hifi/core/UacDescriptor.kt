package com.lcs.hifi.core

import android.hardware.usb.UsbDeviceConnection

/** 一个 UAC Streaming 接口的某个 alternate setting（= 一种 PCM 格式） */
data class UacAlt(
    val ifIndex: Int,
    val alt: Int,
    val channels: Int,
    val subframeBytes: Int,
    val bits: Int,
    val rates: List<Int>,
    val epAddr: Int,
    val epPacket: Int,
    val epInterval: Int
) {
    val bytesPerFrame: Int get() = channels * subframeBytes

    fun supports(rate: Int): Boolean = rates.isEmpty() || rates.contains(rate)

    override fun toString(): String {
        val r = if (rates.isEmpty()) "任意" else rates.take(8).joinToString("/")
        return "alt$alt · ${bits}bit/${channels}ch · $r Hz"
    }
}

/**
 * 直接解析 USB 配置描述符，拿到 UAC1/UAC2 的 streaming 格式表。
 *
 * 这是"独占直连"必需的一步：AudioDeviceInfo 只告诉我们内核支持什么，
 * 而定 alt setting 必须知道每个 alternate setting 的通道数/位深/端点包长。
 */
object UacDescriptor {

    const val DESC_INTERFACE = 0x04
    const val DESC_ENDPOINT = 0x05
    const val DESC_CS_INTERFACE = 0x24

    private const val CS_HEADER = 0x01
    private const val CS_AS_GENERAL = 0x01
    private const val CS_FORMAT_TYPE = 0x02
    private const val CS_CLOCK_SOURCE = 0x0A

    data class Parsed(val alts: List<UacAlt>, val clockId: Int, val uacVersion: Int)

    fun readConfig(conn: UsbDeviceConnection, devTotalHint: Int = 512): ByteArray? {
        return try {
            val head = ByteArray(9)
            val n = conn.controlTransfer(0x80, 6, 0x0200, 0, head, 9, 1500)
            if (n < 9) return null
            val total = (head[2].toInt() and 0xFF) or ((head[3].toInt() and 0xFF) shl 8)
            if (total < 9 || total > 8192) return null
            val buf = ByteArray(total)
            val r = conn.controlTransfer(0x80, 6, 0x0200, 0, buf, total, 2500)
            if (r <= 0) null else buf.copyOf(r.coerceAtMost(total))
        } catch (t: Throwable) {
            null
        }
    }

    fun parse(cfg: ByteArray): Parsed {
        val map = LinkedHashMap<Pair<Int, Int>, MutableAlt>()
        var uacVersion = 0x0100
        var clockId = 0

        var curIf = -1
        var curAlt = 0
        var curIsStreaming = false

        var i = 0
        while (i + 2 <= cfg.size) {
            val len = cfg[i].toInt() and 0xFF
            if (len < 2 || i + len > cfg.size) break
            val type = cfg[i + 1].toInt() and 0xFF
            when (type) {
                DESC_INTERFACE -> {
                    if (len >= 9) {
                        curIf = cfg[i + 2].toInt() and 0xFF
                        curAlt = cfg[i + 3].toInt() and 0xFF
                        val cls = cfg[i + 5].toInt() and 0xFF
                        val sub = cfg[i + 6].toInt() and 0xFF
                        curIsStreaming = (cls == 1 && sub == 2)
                        if (curIsStreaming) {
                            map.getOrPut(curIf to curAlt) { MutableAlt(curIf, curAlt) }
                        }
                    }
                }
                DESC_ENDPOINT -> {
                    if (curIsStreaming && len >= 7) {
                        val a = map[curIf to curAlt] ?: MutableAlt(curIf, curAlt).also { map[curIf to curAlt] = it }
                        val attr = cfg[i + 3].toInt() and 0xFF
                        val xfer = attr and 0x03
                        val dir = (cfg[i + 2].toInt() and 0x80)
                        if (xfer == 1 && dir == 0) {
                            a.epAddr = cfg[i + 2].toInt() and 0xFF
                            a.epPacket = (cfg[i + 4].toInt() and 0xFF) or ((cfg[i + 5].toInt() and 0xFF) shl 8)
                            a.epInterval = cfg[i + 6].toInt() and 0xFF
                        }
                    }
                }
                DESC_CS_INTERFACE -> {
                    if (len >= 3) {
                        val sub = cfg[i + 2].toInt() and 0xFF
                        when (sub) {
                            CS_HEADER -> {
                                if (len >= 5) {
                                    val bcd = (cfg[i + 3].toInt() and 0xFF) or ((cfg[i + 4].toInt() and 0xFF) shl 8)
                                    if (bcd > 0) uacVersion = bcd
                                }
                            }
                            CS_CLOCK_SOURCE -> {
                                if (clockId == 0 && len >= 4) clockId = cfg[i + 2 + 1].toInt() and 0xFF
                            }
                            CS_AS_GENERAL -> {
                                if (curIsStreaming && len >= 4) {
                                    val a = map.getOrPut(curIf to curAlt) { MutableAlt(curIf, curAlt) }
                                    a.terminalLink = cfg[i + 3].toInt() and 0xFF
                                }
                            }
                            CS_FORMAT_TYPE -> {
                                if (curIsStreaming && len >= 8) {
                                    val fmtType = cfg[i + 3].toInt() and 0xFF
                                    if (fmtType == 1) {
                                        val a = map.getOrPut(curIf to curAlt) { MutableAlt(curIf, curAlt) }
                                        a.channels = cfg[i + 4].toInt() and 0xFF
                                        a.subframeBytes = cfg[i + 5].toInt() and 0xFF
                                        a.bits = cfg[i + 6].toInt() and 0xFF
                                        val nr = cfg[i + 7].toInt() and 0xFF
                                        val freqs = mutableListOf<Int>()
                                        if (nr == 0) {
                                            if (i + 14 <= cfg.size) {
                                                val lo = u24(cfg, i + 8)
                                                val hi = u24(cfg, i + 11)
                                                if (lo > 0) freqs += lo
                                                if (hi > 0 && hi != lo) freqs += hi
                                            }
                                        } else {
                                            for (k in 0 until nr) {
                                                val off = i + 8 + k * 3
                                                if (off + 3 > cfg.size) break
                                                val f = u24(cfg, off)
                                                if (f > 0) freqs += f
                                            }
                                        }
                                        a.rates = freqs
                                    }
                                }
                            }
                        }
                    }
                }
            }
            i += len
        }

        val alts = map.values
            .filter { it.epAddr != 0 && it.channels > 0 }
            .map {
                UacAlt(
                    ifIndex = it.ifIndex,
                    alt = it.alt,
                    channels = it.channels,
                    subframeBytes = if (it.subframeBytes > 0) it.subframeBytes else (it.bits + 7) / 8,
                    bits = it.bits,
                    rates = it.rates,
                    epAddr = it.epAddr,
                    epPacket = it.epPacket,
                    epInterval = if (it.epInterval > 0) it.epInterval else 1
                )
            }
            .sortedWith(compareBy({ it.ifIndex }, { it.alt }))

        return Parsed(alts, clockId, uacVersion)
    }

    private fun u24(b: ByteArray, off: Int): Int {
        if (off + 2 >= b.size) return 0
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or ((b[off + 2].toInt() and 0xFF) shl 16)
    }

    /** 选最合适的 alt：位深够用、通道数匹配、支持目标采样率 */
    fun pickAlt(alts: List<UacAlt>, rate: Int, channels: Int, bits: Int): UacAlt? {
        val byRate = alts.filter { it.supports(rate) }
        if (byRate.isEmpty()) return alts.maxByOrNull { it.bits }
        val byCh = byRate.filter { it.channels == channels }
        val pool = if (byCh.isNotEmpty()) byCh else byRate
        val enough = pool.filter { it.bits >= bits }
        if (enough.isNotEmpty()) return enough.minByOrNull { it.bits }
        return pool.maxByOrNull { it.bits }
    }

    /**
     * 设置采样率。UAC2 发到 Clock Source 实体，UAC1 发到 streaming 端点。
     * 返回是否成功（失败时 DAC 通常继续用自己的默认时钟，不算致命）。
     */
    fun setSampleRate(
        conn: UsbDeviceConnection,
        ifIndex: Int,
        epAddr: Int,
        clockId: Int,
        uacVersion: Int,
        rate: Int
    ): Boolean {
        // UAC2：bmRequestType=0x21(host→dev, class, interface), CS_SAM_FREQ_CONTROL=0x01
        if (uacVersion >= 0x0200 && clockId > 0) {
            val d = ByteArray(4) {
                ((rate shr (8 * it)) and 0xFF).toByte()
            }
            try {
                val r = conn.controlTransfer(0x21, 0x01, 0x0100, (clockId shl 8) or ifIndex, d, 4, 800)
                if (r >= 0) return true
            } catch (_: Throwable) {
            }
        }
        // UAC1：bmRequestType=0x22(host→dev, class, endpoint)
        val d3 = byteArrayOf(
            (rate and 0xFF).toByte(),
            ((rate shr 8) and 0xFF).toByte(),
            ((rate shr 16) and 0xFF).toByte()
        )
        return try {
            val r = conn.controlTransfer(0x22, 0x01, 0x0100, epAddr or (ifIndex shl 8), d3, 3, 800)
            r >= 0
        } catch (t: Throwable) {
            false
        }
    }

    private class MutableAlt(val ifIndex: Int, val alt: Int) {
        var channels = 0
        var subframeBytes = 0
        var bits = 0
        var rates = listOf<Int>()
        var epAddr = 0
        var epPacket = 0
        var epInterval = 1
        var terminalLink = 0
    }
}
