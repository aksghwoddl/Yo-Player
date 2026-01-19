package com.yohan.yoplayersdk.demuxer

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi

/**
 * MPEG-TS 세그먼트 디먹서
 * M3U8 다운로더로 받은 세그먼트들을 디먹싱하여 오디오/비디오 샘플을 추출합니다.
 */
@UnstableApi
class TsDemuxer {

    private val ffmpegDemuxer = FfmpegDemuxer()

    // AAC 프레임 duration (1024 samples per frame)
    // 48kHz: 21333us, 44.1kHz: 23220us - 일반적인 값 사용
    private var aacFrameDurationUs = 21333L
    private var lastAudioTimeUs = C.TIME_UNSET
    private val timestampAdjuster = TimestampAdjuster(0)

    /**
     * FFmpeg 버전 정보
     */
    val version: String
        get() = ffmpegDemuxer.getVersion()

    /**
     * 디먹서 초기화
     */
    fun initialize() {
        ffmpegDemuxer.initialize()
    }

    /**
     * 단일 세그먼트의 트랙 정보 분석
     * @param data TS 세그먼트 바이트 배열
     * @return 트랙 포맷 목록
     */
    fun probeSegment(data: ByteArray): List<TrackFormat> {
        ensureInitialized()
        val tracks = ffmpegDemuxer.probeSegment(data)

        // 오디오 트랙의 샘플레이트로 AAC frame duration 계산
        tracks.find { it.isAudio }?.let { audioTrack ->
            if (audioTrack.sampleRate > 0) {
                // AAC: 1024 samples per frame
                aacFrameDurationUs = (1024L * 1_000_000L) / audioTrack.sampleRate
            }
        }

        // 타임스탬프 리셋
        lastAudioTimeUs = C.TIME_UNSET
        timestampAdjuster.reset(0)

        return tracks
    }

    /**
     * 단일 세그먼트 디먹싱 (동기)
     * PTS 기준으로 정규화하여 반환
     *
     * @param data TS 세그먼트 바이트 배열
     * @return 정규화된 타임스탬프를 가진 샘플 목록
     */
    fun demuxSegmentSync(data: ByteArray): List<DemuxedSample> {
        ensureInitialized()
        val rawSamples = ffmpegDemuxer.demuxSegment(data)
        val (normalizedAudioSamples, normalizedVideoSamples) = normalizeSamples(rawSamples)

        return normalizedAudioSamples + normalizedVideoSamples
    }

    /**
     * 단일 세그먼트를 스트리밍 방식으로 디먹싱하여 즉시 콜백 전달
     *
     * @param data TS 세그먼트 바이트 배열
     * @param onSample 정규화된 샘플 콜백
     */
    fun demuxSegmentStreaming(
        data: ByteArray,
        onSample: (DemuxedSample) -> Unit
    ) {
        ensureInitialized()
        val rawSamples = ffmpegDemuxer.demuxSegment(data)
        val (normalizedAudioSamples, normalizedVideoSamples) = normalizeSamples(rawSamples)

        normalizedAudioSamples.forEach(onSample)
        normalizedVideoSamples.forEach(onSample)
    }

    /**
     * 리소스 해제
     */
    fun release() {
        ffmpegDemuxer.release()
        lastAudioTimeUs = C.TIME_UNSET
    }

    /**
     * 불연속 구간에서 타임스탬프 기준을 재설정
     */
    fun resetForDiscontinuity() {
        val lastAdjustedUs = timestampAdjuster.lastAdjustedTimestampUs
        val baseUs = if (lastAdjustedUs != C.TIME_UNSET) lastAdjustedUs else 0L
        timestampAdjuster.reset(baseUs)
        lastAudioTimeUs = C.TIME_UNSET
    }

    private fun ensureInitialized() {
        initialize()
    }

    private fun normalizeSamples(
        rawSamples: List<DemuxedSample>
    ): Pair<List<DemuxedSample>, List<DemuxedSample>> {
        val audioSamples = rawSamples.filter { it.isAudio }.normalize().map {
            it.applyAudioCorrection()
        }
        val videoSamples = rawSamples.filter { it.isVideo }.normalize()

        return audioSamples to videoSamples
    }

    private fun adjustTimestamp(timeUs: Long): Long {
        if (timeUs == 0L && timestampAdjuster.isInitialized) {
            Log.w(
                "TsDemuxer", "timeUs is zero"
            )
            return C.TIME_UNSET
        }

        if (timeUs < 0) {
            Log.w(
                "TsDemuxer",
                "adjustTimestamp: previous = ${timestampAdjuster.lastAdjustedTimestampUs} , current = $timeUs"
            )
            return C.TIME_UNSET
        }

        return timestampAdjuster.adjustSampleTimestamp(timeUs)
    }

    private fun List<DemuxedSample>.normalize() = map { sample ->
        val adjustedTimeUs = adjustTimestamp(sample.timeUs)
        sample.copy(timeUs = adjustedTimeUs)
    }

    /**
     * 오디오 샘플 추가 보정 함수
     * 역행하거나 불연속적일때 보정하기 위해 사용
     */
    private fun DemuxedSample.applyAudioCorrection(): DemuxedSample {
        if (this.isAudio.not()) return this
        if (this.timeUs == C.TIME_UNSET) {
            val correctedTimeUs = if (lastAudioTimeUs != C.TIME_UNSET) {
                lastAudioTimeUs + aacFrameDurationUs
            } else {
                0L
            }
            lastAudioTimeUs = correctedTimeUs
            return this.copy(timeUs = correctedTimeUs)
        }

        if (lastAudioTimeUs != C.TIME_UNSET && this.timeUs <= lastAudioTimeUs) {
            val correctedTimeUs = lastAudioTimeUs + aacFrameDurationUs
            lastAudioTimeUs = correctedTimeUs
            return this.copy(timeUs = correctedTimeUs)
        }

        lastAudioTimeUs = this.timeUs
        return this
    }
}
