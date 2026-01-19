package com.yohan.yoplayersdk.exoplayer

import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.Allocator
import com.yohan.yoplayersdk.demuxer.DemuxedSample
import com.yohan.yoplayersdk.demuxer.TrackFormat
import com.yohan.yoplayersdk.demuxer.TsDemuxer
import com.yohan.yoplayersdk.m3u8.DownloadedSegment
import com.yohan.yoplayersdk.m3u8.M3u8DownloadListener
import com.yohan.yoplayersdk.m3u8.M3u8Downloader
import com.yohan.yoplayersdk.m3u8.M3u8Playlist
import com.yohan.yoplayersdk.m3u8.M3u8Segment

private const val TAG = "CustomMediaSource"

/**
 * 커스텀 MediaSource 구현
 * M3U8 다운로드 + FFmpeg 디먹싱 결과를 ExoPlayer에 공급
 */
@UnstableApi
internal class CustomMediaSource(
    private val url: String,
    private val m3u8Downloader: M3u8Downloader = M3u8Downloader(),
    private val tsDemuxer: TsDemuxer = TsDemuxer()
) : BaseMediaSource() {

    private val mediaItem: MediaItem = MediaItem.Builder()
        .setMediaId(url)
        .setUri(url.toUri())
        .build()

    private var mediaPeriod: CustomMediaPeriod? = null
    private var nextSegmentStartUs = 0L

    override fun getMediaItem(): MediaItem = mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        Log.d(TAG, "prepareSourceInternal called")
        // 초기 Timeline 설정 - ExoPlayer가 createPeriod를 호출할 수 있도록
        refreshTimeline()
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        Log.d(TAG, "createPeriod called, startPositionUs=$startPositionUs")
        val period = CustomMediaPeriod()
        mediaPeriod = period
        startDownload()
        return period
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as? CustomMediaPeriod)?.release()
        if (this.mediaPeriod == mediaPeriod) {
            this.mediaPeriod = null
        }
    }

    override fun maybeThrowSourceInfoRefreshError() {
        // 에러 없음
    }

    override fun releaseSourceInternal() {
        m3u8Downloader.release()
        tsDemuxer.release()
        mediaPeriod?.release()
        mediaPeriod = null
    }

    /**
     * 트랙 정보 설정 및 Timeline 갱신
     */
    private fun setTracks(tracks: List<TrackFormat>) {
        Log.d(TAG, "setTracks called, tracks=${tracks.size}, mediaPeriod=${mediaPeriod != null}")
        mediaPeriod?.setTracks(tracks)
        refreshTimeline()
    }

    fun cancel() {
        m3u8Downloader.cancel()
        mediaPeriod?.setLoading(false)
        mediaPeriod?.signalEndOfStream()
    }

    private fun startDownload() {
        nextSegmentStartUs = 0L
        m3u8Downloader.download(url, downloadListener)
    }

    private val downloadListener = object : M3u8DownloadListener {

        override fun onDownloadStarted(playlist: M3u8Playlist.Media, totalSegments: Int) {
            Log.d(TAG, "Download started: $totalSegments segments")
            mediaPeriod?.setLoading(true)
        }

        override fun onSegmentDownloaded(
            segment: M3u8Segment,
            data: ByteArray,
            currentIndex: Int,
            totalSegments: Int
        ) {
            val period = mediaPeriod ?: return
            if (period.isLoading().not()) return
            Log.d(
                TAG,
                "Segment downloaded: ${currentIndex + 1}/$totalSegments, size=${data.size} bytes"
            )

            if (period.getTrackGroups().length == 0) {
                val tracks = tsDemuxer.probeSegment(data)
                logTracks(tracks)
                setTracks(tracks)
            }

            val segmentStartUs = nextSegmentStartUs
            var videoEndUs = segmentStartUs
            var videoCount = 0
            var audioCount = 0
            var keyFrameCount = 0

            tsDemuxer.demuxSegmentStreaming(data, segmentStartUs) { sample ->
                if (sample.isVideo) {
                    videoCount++
                    if (sample.isKeyFrame) {
                        keyFrameCount++
                    }
                    if (sample.timeUs > videoEndUs) {
                        videoEndUs = sample.timeUs
                    }
                } else if (sample.isAudio) {
                    audioCount++
                }
                queueSampleWithBackpressure(sample)
            }

            logSamples(videoCount, audioCount, keyFrameCount, currentIndex)
            nextSegmentStartUs = videoEndUs + 1000L
        }

        override fun onProgressUpdate(
            progress: Float,
            downloadedSegments: Int,
            totalSegments: Int
        ) {
            Log.d(
                TAG,
                "Progress: ${(progress * 100).toInt()}% ($downloadedSegments/$totalSegments)"
            )
        }

        override fun onDownloadCompleted(
            segments: List<DownloadedSegment>,
            totalBytes: Long,
            elapsedTimeMs: Long
        ) {
            Log.d(
                TAG,
                "Download completed: ${segments.size} segments, $totalBytes bytes, ${elapsedTimeMs}ms"
            )
            mediaPeriod?.setLoading(false)
            mediaPeriod?.signalEndOfStream()
        }

        override fun onDownloadError(error: Throwable, segment: M3u8Segment?) {
            Log.e(TAG, "Download error: ${error.message}", error)
            mediaPeriod?.setLoading(false)
        }

        override fun onDownloadCancelled() {
            Log.d(TAG, "Download cancelled")
            mediaPeriod?.setLoading(false)
        }
    }

    private fun queueSampleWithBackpressure(sample: DemuxedSample) {
        val videoNeed = if (sample.isVideo) 1 else 0
        val audioNeed = if (sample.isAudio) 1 else 0

        var waitCount = 0
        while (true) {
            val period = mediaPeriod ?: break
            if (period.isLoading.not()) break
            if (period.hasCapacity(videoNeed, audioNeed)) {
                if (period.queueSample(sample)) break
            }

            Thread.sleep(10)
            waitCount++
            if (waitCount % 50 == 0) {
                Log.d(TAG, "Backpressure: waiting for buffer to drain (${waitCount * 10}ms)")
            }
        }
    }

    private fun logTracks(tracks: List<TrackFormat>) {
        Log.d(TAG, "=== Tracks Found: ${tracks.size} ===")
        tracks.forEach { track ->
            Log.d(TAG, "  $track")
        }
    }

    private fun logSamples(videoCount: Int, audioCount: Int, keyFrames: Int, segmentIndex: Int) {
        Log.d(
            TAG,
            "Segment[$segmentIndex] demuxed: ${(videoCount + audioCount)} samples " +
                    "(video=$videoCount, audio=$audioCount, keyframes=$keyFrames)"
        )
    }

    private fun refreshTimeline() {
        Log.d(TAG, "refreshTimeline: durationUs=${C.TIME_UNSET}")
        val timeline = SinglePeriodTimeline(
            /* durationUs= */ C.TIME_UNSET,
            /* isSeekable= */ false,
            /* isDynamic= */ false,  // false로 변경 - live stream이 아님
            /* useLiveConfiguration= */ false,
            /* manifest= */ null,
            /* mediaItem= */ mediaItem
        )
        refreshSourceInfo(timeline)
    }
}
