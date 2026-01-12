package com.yohan.yoplayersdk.player

import android.util.Log
import com.yohan.yoplayersdk.demuxer.DemuxedSample
import com.yohan.yoplayersdk.demuxer.TrackFormat
import com.yohan.yoplayersdk.demuxer.TsDemuxer
import com.yohan.yoplayersdk.m3u8.DownloadedSegment
import com.yohan.yoplayersdk.m3u8.M3u8DownloadListener
import com.yohan.yoplayersdk.m3u8.M3u8Downloader
import com.yohan.yoplayersdk.m3u8.M3u8Playlist
import com.yohan.yoplayersdk.m3u8.M3u8Segment

/**
 * @param m3u8Downloader M3U8 다운로더
 * @param tsDemuxer TS 디먹서
 */
internal class YoPlayerImpl(
    private val m3u8Downloader: M3u8Downloader,
    private val tsDemuxer: TsDemuxer
) : YoPlayer {

    private var isPlaying = false
    private var isPaused = false
    private var tracksFound = false

    override fun play(url: String) {
        if (isPlaying) {
            Log.w(TAG, "Already playing, call stop() first")
            return
        }

        isPlaying = true
        isPaused = false
        tracksFound = false

        Log.d(TAG, "Starting playback: $url")

        m3u8Downloader.download(url, object : M3u8DownloadListener {

            override fun onDownloadStarted(playlist: M3u8Playlist.Media, totalSegments: Int) {
                Log.d(TAG, "Download started: $totalSegments segments")
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
                }

                // 세그먼트 디먹싱
                val samples = tsDemuxer.demuxSegmentSync(data)
                logSamples(samples, currentIndex)
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
                isPlaying = false
            }

            override fun onDownloadError(error: Throwable, segment: M3u8Segment?) {
                Log.e(TAG, "Download error: ${error.message}", error)
                isPlaying = false
            }

            override fun onDownloadCancelled() {
                Log.d(TAG, "Download cancelled")
                isPlaying = false
            }
        })
    }

    override fun stop() {
        Log.d(TAG, "Stop")
        m3u8Downloader.cancel()
        isPlaying = false
        isPaused = false
    }

    override fun pause() {
        Log.d(TAG, "Pause")
        isPaused = true
    }

    override fun resume() {
        Log.d(TAG, "Resume")
        isPaused = false
    }

    override fun release() {
        Log.d(TAG, "Release")
        stop()
        m3u8Downloader.release()
        tsDemuxer.release()
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

        Log.d(TAG, "Segment[$segmentIndex] demuxed: " +
                "${samples.size} samples (video=${videoSamples.size}, audio=${audioSamples.size}, keyframes=$keyFrames)")

        // 처음 몇 개 샘플만 상세 로그
        samples.take(3).forEach { sample ->
            Log.v(TAG, "  $sample")
        }
        if (samples.size > 3) {
            Log.v(TAG, "  ... and ${samples.size - 3} more samples")
        }
    }

    companion object {
        private const val TAG = "YoPlayer"
    }
}
