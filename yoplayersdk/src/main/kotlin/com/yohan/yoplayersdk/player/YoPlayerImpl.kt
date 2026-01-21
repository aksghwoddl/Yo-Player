package com.yohan.yoplayersdk.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.yohan.yoplayersdk.exoplayer.CustomMediaSource

/**
 * @param context Android Context
 */
@UnstableApi
internal class YoPlayerImpl(
    private val context: Context
) : YoPlayer {

    private var exoPlayer: ExoPlayer? = null
    private var customMediaSource: CustomMediaSource? = null
    private var surface: Surface? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun setSurface(surface: Surface?) {
        Log.d(TAG, "setSurface called: surface=${surface != null}, exoPlayer=${exoPlayer != null}")
        this.surface = surface
        mainHandler.post {
            exoPlayer?.setVideoSurface(surface)
            Log.d(TAG, "setVideoSurface called on ExoPlayer")
        }
    }

    override fun play(url: String) {
        if (exoPlayer?.isPlaying == true) {
            Log.w(TAG, "Already playing, call stop() first")
            return
        }

        Log.d(TAG, "Starting playback: $url")

        // ExoPlayer 초기화 (메인 스레드에서)
        mainHandler.post {
            initializeExoPlayer(url)
        }
    }

    private fun initializeExoPlayer(url: String) {
        releaseExoPlayer()

        val player = ExoPlayer.Builder(context).build()
        exoPlayer = player

        surface?.let { player.setVideoSurface(it) }

        val mediaSource = CustomMediaSource(url)
        customMediaSource = mediaSource

        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true

        Log.d(TAG, "ExoPlayer initialized")
    }

    private fun releaseExoPlayer() {
        exoPlayer?.release()
        exoPlayer = null
        customMediaSource = null
    }

    override fun stop() {
        Log.d(TAG, "Stop")
        customMediaSource?.cancel()
        mainHandler.post {
            exoPlayer?.stop()
        }
    }

    override fun pause() {
        Log.d(TAG, "Pause")
        mainHandler.post {
            exoPlayer?.playWhenReady = false
        }
    }

    override fun resume() {
        Log.d(TAG, "Resume")
        mainHandler.post {
            exoPlayer?.playWhenReady = true
        }
    }

    override fun release() {
        Log.d(TAG, "Release")
        stop()
        mainHandler.post {
            releaseExoPlayer()
        }
    }

    companion object {
        private const val TAG = "YoPlayer"
    }
}
