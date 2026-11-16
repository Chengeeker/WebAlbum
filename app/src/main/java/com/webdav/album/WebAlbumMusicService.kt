package com.webdav.album

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Adapted from GreenMusic's PlaybackService: MediaSessionService provides the
 * media notification, lock-screen / Bluetooth controls and foreground lifecycle.
 * The app's existing same-origin WebDAV authentication remains authoritative.
 */
@UnstableApi
class WebAlbumMusicService : MediaSessionService() {
    private var musicSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as AlbumApplication
        val calls = okhttp3.Call.Factory { request -> app.client.newCall(request) }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(app.musicStreamCache.factory)).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true,
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
            }
        val activity = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        musicSession = MediaSession.Builder(this, player).setSessionActivity(activity).build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("com.webdav.album.music")
                .setChannelName(R.string.music_playback_channel)
                .build().apply { setSmallIcon(R.drawable.ic_stat_music) }
        )
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = musicSession
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = musicSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }
    override fun onDestroy() {
        musicSession?.let { it.player.release(); it.release() }
        musicSession = null
        super.onDestroy()
    }
}
