package com.lcs.hifi.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 多相 windowed-sinc 重采样器（Kaiser 窗，16 taps / 128 相位）。
 *
 * 为什么自己写：小尾巴在 UAC1 模式下经常只报 48k，而曲目是 44.1k / 88.2k。
 * 交给 AudioFlinger 做 src 质量一般，而且无法控制同步策略；
 * 这里在送给 DAC 之前先做高质量转换，输出采样率就与 DAC 完全一致。
 *
 * 用法：每次解码块调用 process()，读 output / lastFrames。
 */
class Resampler(inRate: Int, outRate: Int, private val ch: Int) {

    val ratio: Double = outRate.toDouble() / inRate.toDouble()
    private val taps = 16
    private val phases = 128
    private val coef = FloatArray(phases * taps)
    private val cutoff = min(1.0, ratio)

    private var hist = FloatArray(taps * 2 * ch)
    private var histFrames = 0
    private var pos = 0.0
    private var outBuf = FloatArray(0)

    var lastFrames = 0
        private set

    /** 已积累但尚未输出的输入帧数（用于判断尾部） */
    val pending: Int get() = histFrames

    init {
        buildTable()
    }

    private fun buildTable() {
        val beta = 8.0
        val i0b = besselI0(beta)
        for (p in 0 until phases) {
            val frac = p.toDouble() / phases
            var dc = 0.0
            for (k in 0 until taps) {
                val d = k - frac - (taps - 1) / 2.0
                val x = cutoff * d
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                val t = ((k - frac - (taps - 1) / 2.0) / (taps / 2.0)).coerceIn(-1.0, 1.0)
                val w = besselI0(beta * sqrt(1.0 - t * t)) / i0b
                val v = cutoff * sinc * w
                coef[p * taps + k] = v.toFloat()
                dc += v
            }
            if (abs(dc) > 1e-9) {
                for (k in 0 until taps) coef[p * taps + k] = (coef[p * taps + k] / dc).toFloat()
            }
        }
    }

    /** seek / 切歌时重置滤波器状态，避免残留尾巴 */
    fun reset() {
        histFrames = 0
        pos = 0.0
        lastFrames = 0
        java.util.Arrays.fill(hist, 0f)
    }

    /**
     * 输入交织 float 帧，输出写入 [output]，实际输出帧数见 [lastFrames]。
     */
    fun process(input: FloatArray, inFrames: Int) {
        if (inFrames <= 0) {
            lastFrames = 0
            return
        }
        val need = (histFrames + inFrames) * ch
        if (hist.size < need) hist = hist.copyOf(maxOf(need, hist.size * 2))
        System.arraycopy(input, 0, hist, histFrames * ch, inFrames * ch)
        val avail = histFrames + inFrames

        val maxOut = (((avail - pos) * ratio).toInt() + 4).coerceAtLeast(0)
        val needOut = maxOut * ch
        if (outBuf.size < needOut) outBuf = FloatArray(needOut)

        var o = 0
        while (pos + taps <= avail) {
            val base = pos.toInt()
            val frac = pos - base
            val ph = (frac * phases).toInt().coerceIn(0, phases - 1)
            val cOff = ph * taps
            for (c in 0 until ch) {
                var acc = 0f
                var idx = base * ch + c
                for (k in 0 until taps) {
                    acc += hist[idx] * coef[cOff + k]
                    idx += ch
                }
                outBuf[o++] = acc
            }
            pos += 1.0 / ratio
        }
        lastFrames = o / ch

        // 回收已消费的历史
        var consumed = pos.toInt()
        if (consumed > avail) consumed = avail
        val remain = avail - consumed
        if (remain > 0) System.arraycopy(hist, consumed * ch, hist, 0, remain * ch)
        histFrames = remain
        pos -= consumed
    }

    val output: FloatArray get() = outBuf

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4.0
        for (k in 1..30) {
            term *= q / (k * k)
            sum += term
            if (term < 1e-13 * sum) break
        }
        return sum
    }
}
