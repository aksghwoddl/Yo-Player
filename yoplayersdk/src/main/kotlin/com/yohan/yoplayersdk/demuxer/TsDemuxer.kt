package com.yohan.yoplayersdk.demuxer

import androidx.media3.common.C

/**
 * MPEG-TS 세그먼트 디먹서
 * M3U8 다운로더로 받은 세그먼트들을 디먹싱하여 오디오/비디오 샘플을 추출합니다.
 */
class TsDemuxer {

    private val ffmpegDemuxer = FfmpegDemuxer()

    // AAC 프레임 duration (1024 samples per frame)
    // 48kHz: 21333us, 44.1kHz: 23220us - 일반적인 값 사용
    private var aacFrameDurationUs = 21333L
    private var nextAudioTimeUs = 0L  // 전역 오디오 타임스탬프 추적
    private var lastAudioTimeUs = C.TIME_UNSET

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
        nextAudioTimeUs = 0L
        lastAudioTimeUs = C.TIME_UNSET

        return tracks
    }

    /**
     * 단일 세그먼트 디먹싱 (동기)
     * PTS를 segmentStartUs 기준으로 정규화하여 반환
     *
     * @param data TS 세그먼트 바이트 배열
     * @param segmentStartUs 세그먼트 시작 시간 (마이크로초)
     * @return 정규화된 타임스탬프를 가진 샘플 목록
     */
    fun demuxSegmentSync(data: ByteArray, segmentStartUs: Long): List<DemuxedSample> {
        ensureInitialized()
        val rawSamples = ffmpegDemuxer.demuxSegment(data)
        val (normalizedAudioSamples, normalizedVideoSamples) =
            normalizeSamples(rawSamples, segmentStartUs)

        return normalizedAudioSamples + normalizedVideoSamples
    }

    /**
     * 단일 세그먼트를 스트리밍 방식으로 디먹싱하여 즉시 콜백 전달
     *
     * @param data TS 세그먼트 바이트 배열
     * @param segmentStartUs 세그먼트 시작 시간 (마이크로초)
     * @param onSample 정규화된 샘플 콜백
     */
    fun demuxSegmentStreaming(
        data: ByteArray,
        segmentStartUs: Long,
        onSample: (DemuxedSample) -> Unit
    ) {
        ensureInitialized()
        val rawSamples = ffmpegDemuxer.demuxSegment(data)
        val (normalizedAudioSamples, normalizedVideoSamples) =
            normalizeSamples(rawSamples, segmentStartUs)

        normalizedAudioSamples.forEach(onSample)
        normalizedVideoSamples.forEach(onSample)
    }

    /**
     * 리소스 해제
     */
    fun release() {
        ffmpegDemuxer.release()
        nextAudioTimeUs = 0L
        lastAudioTimeUs = C.TIME_UNSET
    }

    private fun ensureInitialized() {
        initialize()
    }

    private fun normalizeSamples(
        rawSamples: List<DemuxedSample>,
        segmentStartUs: Long
    ): Pair<List<DemuxedSample>, List<DemuxedSample>> {
        val audioSamples = rawSamples.filter { it.isAudio }
        val videoSamples = rawSamples.filter { it.isVideo }

        // 각 트랙의 basePts (세그먼트 내 첫 PTS)
        val audioBasePts = audioSamples.map { it.timeUs }.filter { it > 0L }.minOrNull() ?: 0L
        val videoBasePts = videoSamples.minOfOrNull { it.timeUs } ?: 0L

        // Video: 자체 basePts를 빼서 0부터 시작, segmentStartUs 더함
        val normalizedVideoSamples = videoSamples.map { sample ->
            val relativePts = (sample.timeUs - videoBasePts).coerceAtLeast(0L)
            sample.copy(timeUs = segmentStartUs + relativePts)
        }

        // Audio: FFmpeg PTS를 우선 사용하고, 누락/역행 시 프레임 duration 기반으로 보정
        val normalizedAudioSamples = normalizeAudioSamples(
            samples = audioSamples,
            audioBasePts = audioBasePts,
            segmentStartUs = segmentStartUs
        )

        return normalizedAudioSamples to normalizedVideoSamples
    }

    private fun normalizeAudioSamples(
        samples: List<DemuxedSample>,
        audioBasePts: Long,
        segmentStartUs: Long
    ): List<DemuxedSample> {
        return samples.map { sample ->
            val relativePts =
                if (sample.timeUs > 0L) (sample.timeUs - audioBasePts).coerceAtLeast(0L) else C.TIME_UNSET
            val baseTimeUs =
                if (relativePts != C.TIME_UNSET) segmentStartUs + relativePts else C.TIME_UNSET

            val targetTimeUs = if (baseTimeUs != C.TIME_UNSET) {
                baseTimeUs
            } else {
                if (nextAudioTimeUs == 0L && lastAudioTimeUs == C.TIME_UNSET) {
                    segmentStartUs
                } else {
                    nextAudioTimeUs
                }
            }

            val monotonicTimeUs =
                if (lastAudioTimeUs != C.TIME_UNSET && targetTimeUs <= lastAudioTimeUs) {
                    lastAudioTimeUs + aacFrameDurationUs
                } else {
                    targetTimeUs
                }

            lastAudioTimeUs = monotonicTimeUs
            nextAudioTimeUs = monotonicTimeUs + aacFrameDurationUs
            sample.copy(timeUs = monotonicTimeUs)
        }
    }
}
