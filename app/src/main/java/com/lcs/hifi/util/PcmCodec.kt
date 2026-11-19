package com.lcs.hifi.util

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM 格式转换。内部统一用 FloatArray（交织、[-1,1]）作为管线中间表示，
 * 解码器输出在这里归一化，输出端再按 DAC/设备实际支持的格式重新编码。
 */
object PcmCodec {

    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT -> 4
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    fun bitsPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
        else -> 16
    }

    fun nameOf(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "U8"
        AudioFormat.ENCODING_PCM_16BIT -> "S16"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "S24"
        AudioFormat.ENCODING_PCM_32BIT -> "S32"
        AudioFormat.ENCODING_PCM_FLOAT -> "F32"
        else -> "?"
    }

    /**
     * 把任意 PCM 缓冲解成交织 FloatArray。返回写入的 float 个数（frames * ch）。
     */
    fun decodeToFloat(
        src: ByteBuffer,
        offset: Int,
        size: Int,
        encoding: Int,
        out: FloatArray,
        outOffset: Int = 0
    ): Int {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        b.position(offset)
        b.limit(offset + size)
        var n = 0
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                while (b.remaining() >= 2 && outOffset + n < out.size) {
                    out[outOffset + n++] = b.short / 32768f
                }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                while (b.remaining() >= 3 && outOffset + n < out.size) {
                    val b0 = b.get().toInt() and 0xFF
                    val b1 = b.get().toInt() and 0xFF
                    val b2 = b.get()
                    var v = (b2.toInt() shl 16) or (b1 shl 8) or b0
                    if (v and 0x800000 != 0) v = v or 0xFF000000.toInt()
                    out[outOffset + n++] = v / 8388608f
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                while (b.remaining() >= 4 && outOffset + n < out.size) {
                    out[outOffset + n++] = b.int / 2147483648f
                }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                while (b.remaining() >= 4 && outOffset + n < out.size) {
                    out[outOffset + n++] = b.float
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                while (b.remaining() >= 1 && outOffset + n < out.size) {
                    out[outOffset + n++] = ((b.get().toInt() and 0xFF) - 128) / 128f
                }
            }
        }
        return n
    }

    /** 帧对齐：返回 size 中完整的帧字节数 */
    fun alignFrames(size: Int, channelCount: Int, encoding: Int): Int {
        val frame = channelCount * bytesPerSample(encoding)
        if (frame <= 0) return 0
        return size - (size % frame)
    }

    fun encodeS16(src: FloatArray, samples: Int, out: ByteArray): Int {
        var o = 0
        for (i in 0 until samples) {
            var v = src[i]
            if (v > 1f) v = 1f
            if (v < -1f) v = -1f
            val s = (v * 32767f).toInt()
            out[o++] = (s and 0xFF).toByte()
            out[o++] = ((s shr 8) and 0xFF).toByte()
        }
        return o
    }

    fun encodeS24(src: FloatArray, samples: Int, out: ByteArray): Int {
        var o = 0
        for (i in 0 until samples) {
            var v = src[i]
            if (v > 1f) v = 1f
            if (v < -1f) v = -1f
            val s = (v * 8388607f).toInt()
            out[o++] = (s and 0xFF).toByte()
            out[o++] = ((s shr 8) and 0xFF).toByte()
            out[o++] = ((s shr 16) and 0xFF).toByte()
        }
        return o
    }

    fun encodeS32(src: FloatArray, samples: Int, out: ByteArray): Int {
        var o = 0
        for (i in 0 until samples) {
            var v = src[i]
            if (v > 1f) v = 1f
            if (v < -1f) v = -1f
            val s = (v * 2147483520f).toInt()
            out[o++] = (s and 0xFF).toByte()
            out[o++] = ((s shr 8) and 0xFF).toByte()
            out[o++] = ((s shr 16) and 0xFF).toByte()
            out[o++] = ((s shr 24) and 0xFF).toByte()
        }
        return o
    }

    fun encodeF32LE(src: FloatArray, samples: Int, outBytes: ByteArray): Int {
        val bb = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(src, 0, samples)
        return samples * 4
    }

    fun timeText(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return String.format("%d:%02d", m, s)
    }
}
