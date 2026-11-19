package com.lcs.hifi.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lcs.hifi.MainActivity
import com.lcs.hifi.R

/**
 * 前台播放服务。持有 PlayerHub 的生命周期，保证息屏/切后台继续出声。
 */
class PlaybackService : Service(), PlaybackEngine.Listener {

    companion object {
        const val CH_ID = "lcs_playback"
        const val NOTIF_ID = 1001

        const val ACTION_TOGGLE = "com.lcs.hifi.action.TOGGLE"
        const val ACTION_NEXT = "com.lcs.hifi.action.NEXT"
        const val ACTION_PREV = "com.lcs.hifi.action.PREV"
        const val ACTION_STOP = "com.lcs.hifi.action.STOP"

        fun ensure(ctx: Context) {
            val i = Intent(ctx, PlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(ctx, i)
                } else {
                    ctx.startService(i)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private var foregrounded = false
    private var curTitle = "未在播放"
    private var curSub = "LittleSauce HiFi"
    private var curPlaying = false

    override fun onCreate() {
        super.onCreate()
        PlayerHub.init(this)
        PlayerHub.addListener(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregrounded) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
            foregrounded = true
        }
        val e = PlayerHub.engine
        when (intent?.action) {
            ACTION_TOGGLE -> e?.toggle()
            ACTION_NEXT -> e?.next()
            ACTION_PREV -> e?.previous()
            ACTION_STOP -> {
                e?.stop()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        PlayerHub.removeListener(this)
        super.onDestroy()
    }

    // ------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CH_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        ch.description = getString(R.string.notif_channel_desc)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    private fun pi(requestCode: Int, action: String): PendingIntent {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f = f or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, requestCode, i, f)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle(curTitle)
            .setContentText(curSub)
            .setSmallIcon(R.drawable.ic_note)
            .setContentIntent(open)
            .setOngoing(curPlaying)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "上一首", pi(1, ACTION_PREV))
            .addAction(0, if (curPlaying) "暂停" else "播放", pi(2, ACTION_TOGGLE))
            .addAction(0, "下一首", pi(3, ACTION_NEXT))
            .addAction(0, "停止", pi(4, ACTION_STOP))
            .build()
    }

    private fun refreshNotification() {
        if (!foregrounded) return
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------ 回调（已在主线程）

    override fun onTrack(index: Int, track: Track, durationMs: Long) {
        curTitle = track.title
        curSub = track.subtitle
        refreshNotification()
    }

    override fun onPlaying(playing: Boolean) {
        curPlaying = playing
        refreshNotification()
    }

    override fun onProgress(positionMs: Long) = Unit

    override fun onOutput(info: PlaybackEngine.OutputInfo?) = Unit

    override fun onError(message: String) = Unit
}
