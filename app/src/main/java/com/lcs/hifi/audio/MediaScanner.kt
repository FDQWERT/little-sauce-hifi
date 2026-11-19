package com.lcs.hifi.audio

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** 曲目元数据。sampleRate/channels/bits 可能为 0，播放时由 MediaExtractor 兜底探测。 */
data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mime: String,
    val displayName: String,
    val path: String,
    val sampleRate: Int = 0,
    val channels: Int = 0
) {
    val subtitle: String
        get() = buildString {
            append(if (artist.isBlank() || artist == "<unknown>") "未知艺术家" else artist)
            if (album.isNotBlank() && album != "<unknown>") append(" · ").append(album)
        }

    val ext: String
        get() = displayName.substringAfterLast('.', "").uppercase()
}

object MediaScanner {

    private val COLS = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DATA
    )

    fun scan(ctx: Context, limit: Int = 3000): List<Track> {
        val out = ArrayList<Track>(256)
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 3000"
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                COLS, sel, null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(iId)
                    val name = c.getString(iName) ?: "track"
                    val mime = c.getString(iMime) ?: ""
                    if (!isSupported(name, mime)) continue
                    out += Track(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                        artist = c.getString(iArtist) ?: "",
                        album = c.getString(iAlbum) ?: "",
                        durationMs = c.getLong(iDur),
                        sizeBytes = c.getLong(iSize),
                        mime = mime,
                        displayName = name,
                        path = c.getString(iData) ?: ""
                    )
                }
            }
        } catch (t: Throwable) {
            // 权限缺失或 MediaStore 异常：返回已扫到的部分
        }
        return out
    }

    private val OK_EXT = setOf(
        "MP3", "FLAC", "WAV", "M4A", "AAC", "OGG", "OPUS", "APE", "ALAC", "DSF", "DFF", "AIFF", "AIFFC", "WMA", "MP4"
    )

    fun isSupported(name: String, mime: String): Boolean {
        if (mime.startsWith("audio/")) return true
        val ext = name.substringAfterLast('.', "").uppercase()
        return ext in OK_EXT
    }
}
