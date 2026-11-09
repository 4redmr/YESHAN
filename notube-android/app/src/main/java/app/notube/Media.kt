package app.notube

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * '알림 접근' 권한을 받기 위한 서비스. 안드로이드는 다른 앱의 재생 상태를 보려면 이 권한을 요구해요.
 * 알림 내용은 전혀 읽지 않아요 (onNotificationPosted를 구현하지 않음).
 */
class MediaWatcher : NotificationListenerService()

object Media {
    fun hasPermission(ctx: Context): Boolean =
        ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)

    private fun youtubeSessions(ctx: Context): List<MediaController> {
        if (!hasPermission(ctx)) return emptyList()
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(ComponentName(ctx, MediaWatcher::class.java))
                .filter { it.packageName in Engine.YOUTUBE_PACKAGES }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** 유튜브가 지금 소리를 재생 중인지 (화면이 꺼져 있거나 백그라운드여도). */
    fun youtubePlaying(ctx: Context): Boolean =
        youtubeSessions(ctx).any { it.playbackState?.state == PlaybackState.STATE_PLAYING }

    /** 유튜브 재생을 멈춰요. 시간을 다 썼을 때 써요. */
    fun pauseYoutube(ctx: Context) {
        youtubeSessions(ctx).forEach { c ->
            try { c.transportControls.pause() } catch (_: Exception) { }
        }
    }
}
