package com.yohan.yoplayersdk.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import com.yohan.yoplayersdk.demuxer.DemuxedSample
import com.yohan.yoplayersdk.demuxer.TrackFormat
import com.yohan.yoplayersdk.demuxer.TsDemuxer
import com.yohan.yoplayersdk.exoplayer.CustomMediaSource
import com.yohan.yoplayersdk.m3u8.DownloadedSegment
import com.yohan.yoplayersdk.m3u8.M3u8DownloadListener
import com.yohan.yoplayersdk.m3u8.M3u8Downloader
import com.yohan.yoplayersdk.m3u8.M3u8Playlist
import com.yohan.yoplayersdk.m3u8.M3u8Segment
import kotlin.math.roundToLong

/**
 * @param context Android Context
 * @param m3u8Downloader M3U8 다운로더
 * @param tsDemuxer TS 디먹서
 */
internal class YoPlayerImpl(
    private val context: Context,
    private val m3u8Downloader: M3u8Downloader,
    private val tsDemuxer: TsDemuxer
) : YoPlayer {

    private var exoPlayer: ExoPlayer? = null
    private var customMediaSource: CustomMediaSource? = null
    private var surface: Surface? = null

    private var isPlaying = false
    private var tracksFound = false
    private var nextSegmentStartUs = 0L

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
        if (isPlaying) {
            Log.w(TAG, "Already playing, call stop() first")
            return
        }

        isPlaying = true
        tracksFound = false
        nextSegmentStartUs = 0L

        Log.d(TAG, "Starting playback: $url")

        // ExoPlayer 초기화 (메인 스레드에서)
        mainHandler.post {
            initializeExoPlayer(url)
        }

        // M3U8 다운로드 시작
        m3u8Downloader.download(url, object : M3u8DownloadListener {

            override fun onDownloadStarted(playlist: M3u8Playlist.Media, totalSegments: Int) {
                Log.d(TAG, "Download started: $totalSegments segments")
                mainHandler.post {
                    customMediaSource?.setLoading(true)
                }
            }

            override fun onSegmentDownloaded(
                segment: M3u8Segment,
                data: ByteArray,
                currentIndex: Int,
                totalSegments: Int
            ) {
                Log.d(TAG, "Segment downloaded: ${currentIndex + 1}/$totalSegments, size=${data.size} bytes")

                // 첫 번째 세그먼트에서 트랙 정보 추출
                if (tracksFound.not()) {
                    val tracks = tsDemuxer.probeSegment(data)
                    tracksFound = true
                    logTracks(tracks)

                    mainHandler.post {
                        customMediaSource?.setTracks(tracks)
                    }
                }

                // 세그먼트 디먹싱
                val samples = tsDemuxer.demuxSegmentSync(data)
                logSamples(samples, currentIndex)

                val segmentStartUs = nextSegmentStartUs
                val segmentDurationUs = (segment.duration * 1_000_000.0).roundToLong()
                nextSegmentStartUs += segmentDurationUs

                val videoCount = samples.count { it.isVideo }
                val audioCount = samples.count { it.isAudio }

                // 백프레셔: 버퍼가 가득 차면 대기 (OOM 방지)
                var waitCount = 0
                while (customMediaSource?.hasCapacity(videoCount, audioCount)?.not() == true && isPlaying) {
                    Thread.sleep(100)
                    waitCount++
                    if (waitCount % 10 == 0) {
                        Log.d(TAG, "Backpressure: waiting for buffer to drain (${waitCount * 100}ms)")
                    }
                }

                // 샘플을 MediaSource에 전달
                customMediaSource?.queueSamples(samples, segmentStartUs)
            }

            override fun onProgressUpdate(
                progress: Float,
                downloadedSegments: Int,
                totalSegments: Int
            ) {
                Log.d(TAG, "Progress: ${(progress * 100).toInt()}% ($downloadedSegments/$totalSegments)")
            }

            override fun onDownloadCompleted(
                segments: List<DownloadedSegment>,
                totalBytes: Long,
                elapsedTimeMs: Long
            ) {
                Log.d(TAG, "Download completed: ${segments.size} segments, $totalBytes bytes, ${elapsedTimeMs}ms")
                mainHandler.post {
                    customMediaSource?.setLoading(false)
                    customMediaSource?.signalEndOfStream()
                }
                isPlaying = false
            }

            override fun onDownloadError(error: Throwable, segment: M3u8Segment?) {
                Log.e(TAG, "Download error: ${error.message}", error)
                mainHandler.post {
                    customMediaSource?.setLoading(false)
                }
                isPlaying = false
            }

            override fun onDownloadCancelled() {
                Log.d(TAG, "Download cancelled")
                mainHandler.post {
                    customMediaSource?.setLoading(false)
                }
                isPlaying = false
            }
        })
    }

    private fun initializeExoPlayer(url: String) {
        // 기존 플레이어 정리
        releaseExoPlayer()

        // ExoPlayer 생성
        val player = ExoPlayer.Builder(context).build()
        exoPlayer = player

        // Surface 설정
        surface?.let { player.setVideoSurface(it) }

        // CustomMediaSource 생성 및 설정
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
        m3u8Downloader.cancel()
        mainHandler.post {
            exoPlayer?.stop()
        }
        isPlaying = false
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
        m3u8Downloader.release()
        tsDemuxer.release()
        mainHandler.post {
            releaseExoPlayer()
        }
    }

    private fun logTracks(tracks: List<TrackFormat>) {
        Log.d(TAG, "=== Tracks Found: ${tracks.size} ===")
        tracks.forEach { track ->
            Log.d(TAG, "  $track")
        }
    }

    private fun logSamples(samples: List<DemuxedSample>, segmentIndex: Int) {
        val videoSamples = samples.filter { it.isVideo }
        val audioSamples = samples.filter { it.isAudio }
        val keyFrames = samples.count { it.isKeyFrame }

        Log.d(
            TAG,
            "Segment[$segmentIndex] demuxed: ${samples.size} samples " +
                "(video=${videoSamples.size}, audio=${audioSamples.size}, keyframes=$keyFrames)"
        )
    }


    companion object {
        private const val TAG = "YoPlayer"
    }
}
