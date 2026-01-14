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

private const val TAG = "CustomMediaSource"

/**
 * 커스텀 MediaSource 구현
 * M3U8 다운로드 + FFmpeg 디먹싱 결과를 ExoPlayer에 공급
 */
@UnstableApi
internal class CustomMediaSource(
    private val url: String
) : BaseMediaSource() {

    private val mediaItem: MediaItem = MediaItem.Builder()
        .setMediaId(url)
        .setUri(url.toUri())
        .build()

    private var mediaPeriod: CustomMediaPeriod? = null
    private var durationUs: Long = C.TIME_UNSET
    private var prepared = false

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
        mediaPeriod?.release()
        mediaPeriod = null
        prepared = false
    }

    /**
     * 트랙 정보 설정 및 Timeline 갱신
     */
    fun setTracks(tracks: List<TrackFormat>) {
        Log.d(TAG, "setTracks called, tracks=${tracks.size}, mediaPeriod=${mediaPeriod != null}")
        mediaPeriod?.setTracks(tracks)

        if (prepared.not()) {
            prepared = true
            refreshTimeline()
        }
    }

    /**
     * Duration 설정 (선택적)
     */
    fun setDuration(durationUs: Long) {
        this.durationUs = durationUs
        if (prepared) {
            refreshTimeline()
        }
    }

    /**
     * 샘플 추가
     */
    fun queueSamples(samples: List<DemuxedSample>, segmentStartUs: Long) {
        Log.v(TAG, "queueSamples called, samples=${samples.size}, segmentStartUs=$segmentStartUs")
        mediaPeriod?.queueSamples(samples, segmentStartUs)
    }

    /**
     * 스트림 종료 알림
     */
    fun signalEndOfStream() {
        mediaPeriod?.signalEndOfStream()
    }

    /**
     * 로딩 상태 설정
     */
    fun setLoading(loading: Boolean) {
        mediaPeriod?.setLoading(loading)
    }

    /**
     * 백프레셔를 위한 큐 여유 확인
     */
    fun hasCapacity(videoCount: Int, audioCount: Int): Boolean {
        return mediaPeriod?.hasCapacity(videoCount, audioCount) ?: true
    }

    private fun refreshTimeline() {
        Log.d(TAG, "refreshTimeline: durationUs=$durationUs, prepared=$prepared")
        val timeline = SinglePeriodTimeline(
            /* durationUs= */ durationUs,
            /* isSeekable= */ false,
            /* isDynamic= */ true,  // 라이브 스트림처럼 동작하여 버퍼링 즉시 시작
            /* useLiveConfiguration= */ false,
            /* manifest= */ null,
            /* mediaItem= */ mediaItem
        )
        refreshSourceInfo(timeline)
    }
}
