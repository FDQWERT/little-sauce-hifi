package com.lcs.hifi.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import com.lcs.hifi.core.AudioRouteManager
import com.lcs.hifi.core.RawUsb
import com.lcs.hifi.core.UsbHub
import com.lcs.hifi.util.PcmCodec
import com.lcs.hifi.util.Resampler
import kotlin.math.min

private fun MediaFormat.intOr(key: String, def: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(def) else def

private fun MediaFormat.longOr(key: String, def: Long): Long =
    if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(def) else def

/**
 * 播放引擎。
 *
 * 管线：MediaExtractor → MediaCodec 解码 → FloatArray 交织
 *      → 声道适配 → 软件音量(仅直通) → 重采样(必要时) → PCM 重编码 → AudioSink
 *
 * 每首歌独立开线程，切换歌曲 = 杀旧线程 + 建新线程，
 * 这样采样率/位深/输出设备变了都能干净地重建 sink。
 */
class PlaybackEngine(
    private val ctx: Context,
    val route: AudioRouteManager,
    private val hub: UsbHub
) {

    enum class Mode { SYSTEM, DIRECT }

    data class OutputInfo(
        val mode: Mode,
        val deviceName: String,
        val rate: Int,
        val channels: Int,
        val encoding: Int,
        val bitPerfect: Boolean,
        val note: String
    )

    interface Listener {
        fun onTrack(index: Int, track: Track, durationMs: Long)
        fun onPlaying(playing: Boolean)
        fun onProgress(positionMs: Long)
        fun onOutput(info: OutputInfo?)
        fun onError(message: String)
    }

    var listener: Listener? = null

    @Volatile var mode: Mode = Mode.SYSTEM
    @Volatile var directTarget: RawUsb? = null
    @Volatile var preferredDeviceId: Int = 0
    @Volatile var softwareVolume: Float = 1f

    @Volatile private var playlist: List<Track> = emptyList()
    @Volatile private var index: Int = -1
    @Volatile private var paused = false
    @Volatile private var playing = false
    @Volatile private var seekToMs = -1L
    @Volatile private var runId = 0
    private var worker: Thread? = null

    private var lastFallbackNote = ""

    // 复用缓冲（只有 worker 线程访问）
    private val floatBuf = FloatArray(131072)
    private val chanBuf = FloatArray(131072)
    private val byteBuf = ByteArray(262144)

    fun setPlaylist(list: List<Track>) {
        playlist = list
        if (index >= list.size) index = -1
    }

    fun playlist(): List<Track> = playlist
    fun currentIndex(): Int = index
    fun currentTrack(): Track? = playlist.getOrNull(index)
    fun isPlaying(): Boolean = playing && !paused
    fun isPaused(): Boolean = paused

    fun playAt(i: Int) {
        val list = playlist
        if (i < 0 || i >= list.size) return
        index = i
        paused = false
        playing = true
        seekToMs = -1
        restartWorker()
        listener?.onPlaying(true)
    }

    fun toggle() {
        if (playing && !paused) pause() else resume()
    }

    fun pause() {
        if (!playing) return
        paused = true
        listener?.onPlaying(false)
    }

    fun resume() {
        if (playing && paused) {
            paused = false
            listener?.onPlaying(true)
            return
        }
        if (!playing) {
            val i = if (index < 0) 0 else index
            if (playlist.isNotEmpty()) playAt(i)
        }
    }

    fun next() {
        val list = playlist
        if (list.isEmpty()) return
        val n = (index + 1) % list.size
        playAt(n)
    }

    fun previous() {
        val list = playlist
        if (list.isEmpty()) return
        val n = if (index <= 0) list.size - 1 else index - 1
        playAt(n)
    }

    fun seekTo(ms: Long) {
        if (!playing) return
        seekToMs = if (ms < 0) 0 else ms
    }

    fun stop() {
        runId++
        val w = worker
        worker = null
        w?.interrupt()
        try {
            w?.join(1200)
        } catch (_: InterruptedException) {
        }
        playing = false
        paused = false
        listener?.onPlaying(false)
    }

    fun release() {
        stop()
        listener = null
    }

    fun lastFallback(): String = lastFallbackNote

    // ---------------------------------------------------------------- 线程

    private fun restartWorker() {
        val old = worker
        runId++
        worker = null
        old?.interrupt()
        try {
            old?.join(1500)
        } catch (_: InterruptedException) {
        }
        val myId = runId
        val t = Thread({ runLoop(myId) }, "lcs-playback")
        worker = t
        t.start()
    }

    private fun runLoop(myId: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        while (runId == myId) {
            val idx = index
            val track = playlist.getOrNull(idx) ?: break
            listener?.onTrack(idx, track, track.durationMs)
            val ok = playOne(myId, track)
            if (runId != myId) return
            if (!ok) break
            if (paused) {
                // 暂停状态下自然播完（极端情况）：停下等用户操作
                paused = false
            }
            if (idx + 1 < playlist.size) {
                index = idx + 1
                continue
            }
            break
        }
        if (runId == myId) {
            playing = false
            paused = false
            listener?.onPlaying(false)
        }
    }

    // ---------------------------------------------------------------- 单曲

    private fun playOne(myId: Int, track: Track): Boolean {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var sink: AudioSink? = null
        try {
            val ext = MediaExtractor()
            extractor = ext
            try {
                ext.setDataSource(ctx, track.uri, null)
            } catch (t: Throwable) {
                listener?.onError("打开文件失败: ${track.displayName}")
                return false
            }

            var trackIdx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val f = ext.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIdx = i
                    fmt = f
                    break
                }
            }
            if (trackIdx < 0 || fmt == null) {
                listener?.onError("没有音频轨道: ${track.displayName}")
                return false
            }
            ext.selectTrack(trackIdx)

            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: "audio/raw"
            var srcRate = fmt.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100).coerceAtLeast(8000)
            var srcCh = fmt.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceIn(1, 8)

            val dec = try {
                MediaCodec.createDecoderByType(mime).also { it.configure(fmt, null, null, 0); it.start() }
            } catch (t: Throwable) {
                listener?.onError("解码器不支持 $mime（${track.displayName}）")
                return false
            }
            codec = dec

            var currentSink = openSink(srcRate, srcCh) ?: return false
            sink = currentSink
            var outRate = currentSink.actualRate
            var outCh = currentSink.actualChannels
            var outEnc = currentSink.actualEncoding
            var resampler: Resampler? =
                if (srcRate != outRate) Resampler(srcRate, outRate, outCh) else null

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmEnc = AudioFormat.ENCODING_PCM_16BIT
            var lastPos = -1L
            var lastReport = 0L

            while (runId == myId && !outputDone) {
                if (paused) {
                    try {
                        Thread.sleep(40)
                    } catch (_: InterruptedException) {
                        return false
                    }
                    continue
                }

                val sk = seekToMs
                if (sk >= 0) {
                    seekToMs = -1
                    try {
                        ext.seekTo(sk * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        dec.flush()
                    } catch (_: Throwable) {
                    }
                    inputDone = false
                    resampler?.reset()
                    currentSink.flush()
                    lastPos = sk
                    listener?.onProgress(sk)
                }

                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(6000)
                    if (inIdx >= 0) {
                        val ib = dec.getInputBuffer(inIdx)
                        if (ib == null) {
                            inputDone = true
                        } else {
                            val sz = ext.readSampleData(ib, 0)
                            if (sz < 0) {
                                dec.queueInputBuffer(
                                    inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, sz, ext.sampleTime, 0)
                                ext.advance()
                            }
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(info, 6000)

                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = dec.outputFormat
                    pcmEnc = of.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    val nr = of.intOr(MediaFormat.KEY_SAMPLE_RATE, srcRate)
                    val nc = of.intOr(MediaFormat.KEY_CHANNEL_COUNT, srcCh).coerceIn(1, 8)
                    if (nr != srcRate || nc != srcCh) {
                        srcRate = nr
                        srcCh = nc
                        val ns = openSink(srcRate, srcCh) ?: return false
                        currentSink.close()
                        currentSink = ns
                        sink = ns
                        outRate = ns.actualRate
                        outCh = ns.actualChannels
                        outEnc = ns.actualEncoding
                        resampler = if (srcRate != outRate) Resampler(srcRate, outRate, outCh) else null
                    }
                } else if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = dec.getOutputBuffer(outIdx)
                        if (buf != null) {
                            val n = PcmCodec.decodeToFloat(buf, info.offset, info.size, pcmEnc, floatBuf, 0)
                            val frames = n / srcCh
                            if (frames > 0) {
                                if (!feed(myId, frames, srcCh, currentSink, outCh, outEnc, resampler)) {
                                    dec.releaseOutputBuffer(outIdx, false)
                                    return false
                                }
                            }
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    val st = ext.sampleTime
                    val pos = if (st > 0) st / 1000 else lastPos
                    if (pos > lastPos) lastPos = pos
                    val now = System.currentTimeMillis()
                    if (lastPos >= 0 && (now - lastReport > 200)) {
                        lastReport = now
                        listener?.onProgress(lastPos)
                    }
                }
            }

            // 收尾：等待硬件缓冲播完
            currentSink.drain()
            listener?.onProgress(track.durationMs)
            return true
        } catch (t: Throwable) {
            if (runId == myId) listener?.onError("播放异常: ${t.message}")
            return false
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor?.release()
            } catch (_: Throwable) {
            }
            try {
                sink?.close()
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- 写数据

    private fun feed(
        myId: Int,
        frames: Int,
        srcCh: Int,
        sink: AudioSink,
        outCh: Int,
        outEnc: Int,
        resampler: Resampler?
    ): Boolean {
        val gain = if (sink is DirectUsbSink) softwareVolume.coerceIn(0f, 1f) else 1f
        adaptChannels(floatBuf, frames, srcCh, outCh, gain)

        var src = chanBuf
        var nFrames = frames
        val rs = resampler
        if (rs != null) {
            rs.process(chanBuf, frames)
            src = rs.output
            nFrames = rs.lastFrames
        }
        if (nFrames <= 0) return true

        val samples = nFrames * outCh
        if (samples <= 0) return true
        val need = samples * PcmCodec.bytesPerSample(outEnc)
        if (need > byteBuf.size) return true

        val bytes = when (outEnc) {
            AudioFormat.ENCODING_PCM_16BIT -> PcmCodec.encodeS16(src, samples, byteBuf)
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmCodec.encodeS24(src, samples, byteBuf)
            AudioFormat.ENCODING_PCM_32BIT -> PcmCodec.encodeS32(src, samples, byteBuf)
            AudioFormat.ENCODING_PCM_FLOAT -> PcmCodec.encodeF32LE(src, samples, byteBuf)
            else -> PcmCodec.encodeS16(src, samples, byteBuf)
        }

        var off = 0
        while (off < bytes) {
            if (runId != myId) return false
            if (paused) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    return false
                }
                continue
            }
            val w = sink.write(byteBuf, off, bytes - off)
            if (w < 0) {
                listener?.onError("写入输出失败($w)")
                return false
            }
            if (w == 0) {
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    return false
                }
            } else {
                off += w
            }
        }
        return true
    }

    private fun adaptChannels(src: FloatArray, frames: Int, srcCh: Int, outCh: Int, gain: Float) {
        if (srcCh == outCh) {
            val n = frames * srcCh
            if (gain == 1f) {
                System.arraycopy(src, 0, chanBuf, 0, n)
            } else {
                for (i in 0 until n) chanBuf[i] = src[i] * gain
            }
            return
        }
        var o = 0
        if (srcCh == 1) {
            for (f in 0 until frames) {
                val v = src[f] * gain
                for (c in 0 until outCh) chanBuf[o++] = v
            }
        } else if (outCh == 1) {
            for (f in 0 until frames) {
                var s = 0f
                for (c in 0 until srcCh) s += src[f * srcCh + c]
                chanBuf[o++] = s / srcCh * gain
            }
        } else {
            val cp = min(srcCh, outCh)
            for (f in 0 until frames) {
                for (c in 0 until cp) chanBuf[o++] = src[f * srcCh + c] * gain
                for (c in cp until outCh) chanBuf[o++] = 0f
            }
        }
    }

    // ---------------------------------------------------------------- sink

    private fun openSink(srcRate: Int, srcCh: Int): AudioSink? {
        val dev = if (preferredDeviceId != 0) route.deviceById(preferredDeviceId)
        else route.currentOutput()
        val outRate = route.pickRate(dev, srcRate)
        val outCh = route.pickChannels(dev, srcCh)

        val target = directTarget
        if (mode == Mode.DIRECT && target != null) {
            val d = DirectUsbSink(ctx, hub, target)
            if (d.open(outRate, outCh, AudioFormat.ENCODING_PCM_24BIT_PACKED, null)) {
                lastFallbackNote = ""
                listener?.onOutput(
                    OutputInfo(
                        Mode.DIRECT, target.title, d.actualRate, d.actualChannels,
                        d.actualEncoding, true, d.detail
                    )
                )
                return d
            }
            lastFallbackNote = d.failReason.ifBlank { "独占打开失败" }
            try {
                d.close()
            } catch (_: Throwable) {
            }
        }

        val enc = route.pickEncoding(dev, outRate, outCh)
        val s = AudioTrackSink(route)
        if (!s.open(outRate, outCh, enc, dev)) {
            listener?.onError("打开输出失败：${outRate}Hz/${outCh}ch ${PcmCodec.nameOf(enc)}")
            return null
        }
        val name = dev?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "系统默认输出"
        listener?.onOutput(
            OutputInfo(
                Mode.SYSTEM, name, s.actualRate, s.actualChannels, s.actualEncoding,
                false, lastFallbackNote
            )
        )
        lastFallbackNote = ""
        return s
    }
}
