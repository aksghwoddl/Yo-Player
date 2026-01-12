package com.yohan.yoplayersdk.demuxer

import com.yohan.yoplayersdk.m3u8.DownloadedSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MPEG-TS 세그먼트 디먹서
 * M3U8 다운로더로 받은 세그먼트들을 디먹싱하여 오디오/비디오 샘플을 추출합니다.
 */
class TsDemuxer {

    private val ffmpegDemuxer = FfmpegDemuxer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isCancelled = AtomicBoolean(false)
    private var isInitialized = false

    /**
     * FFmpeg 버전 정보
     */
    val version: String
        get() = ffmpegDemuxer.getVersion()

    /**
     * 디먹서 초기화
     * @return 초기화 성공 여부
     * @throws DemuxerException 초기화 실패 시
     */
    fun initialize() {
        if (isInitialized) return
        ffmpegDemuxer.initialize()
        isInitialized = true
    }

    /**
     * 단일 세그먼트의 트랙 정보 분석
     * @param data TS 세그먼트 바이트 배열
     * @return 트랙 포맷 목록
     */
    fun probeSegment(data: ByteArray): List<TrackFormat> {
        ensureInitialized()
        return ffmpegDemuxer.probeSegment(data)
    }

    /**
     * 단일 세그먼트 디먹싱 (동기)
     * @param data TS 세그먼트 바이트 배열
     * @return 추출된 샘플 목록
     */
    fun demuxSegmentSync(data: ByteArray): List<DemuxedSample> {
        ensureInitialized()
        return ffmpegDemuxer.demuxSegment(data)
    }

    /**
     * 다운로드된 세그먼트 목록 디먹싱 (비동기)
     *
     * @param segments 다운로드된 세그먼트 목록
     * @param listener 디먹싱 리스너
     */
    fun demuxSegments(
        segments: List<DownloadedSegment>,
        listener: DemuxerListener
    ) {
        isCancelled.set(false)

        scope.launch {
            try {
                ensureInitialized()

                if (segments.isEmpty()) {
                    listener.onError(DemuxerException("No segments to demux"))
                    return@launch
                }

                // 첫 번째 세그먼트에서 트랙 정보 분석
                val tracks = ffmpegDemuxer.probeSegment(segments.first().data)

                withContext(Dispatchers.Main) {
                    listener.onTracksFound(tracks)
                }

                var totalSamples = 0

                // 각 세그먼트 디먹싱
                segments.forEachIndexed { index, segment ->
                    if (isCancelled.get()) {
                        return@launch
                    }

                    try {
                        val samples = ffmpegDemuxer.demuxSegment(segment.data)

                        // 각 샘플을 콜백으로 전달
                        samples.forEach { sample ->
                            if (isCancelled.get()) return@forEach

                            withContext(Dispatchers.Main) {
                                listener.onSampleExtracted(sample)
                            }
                        }

                        totalSamples += samples.size

                        withContext(Dispatchers.Main) {
                            listener.onSegmentCompleted(index, samples.size)
                        }

                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            listener.onError(e, index)
                        }
                    }
                }

                if (isCancelled.get().not()) {
                    withContext(Dispatchers.Main) {
                        listener.onDemuxingCompleted(segments.size, totalSamples)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    listener.onError(e)
                }
            }
        }
    }

    /**
     * 다운로드된 세그먼트 목록을 디먹싱하고 모든 샘플 반환 (동기)
     *
     * @param segments 다운로드된 세그먼트 목록
     * @return 디먹싱 결과 (트랙 정보 + 모든 샘플)
     */
    fun demuxSegmentsSync(segments: List<DownloadedSegment>): DemuxResult {
        ensureInitialized()

        if (segments.isEmpty()) {
            return DemuxResult(emptyList(), emptyList())
        }

        // 첫 번째 세그먼트에서 트랙 정보 분석
        val tracks = ffmpegDemuxer.probeSegment(segments.first().data)

        // 모든 세그먼트 디먹싱
        val allSamples = buildList {
            segments.forEach { segment ->
                val samples = ffmpegDemuxer.demuxSegment(segment.data)
                addAll(samples)
            }
        }

        return DemuxResult(tracks, allSamples)
    }

    /**
     * 진행 중인 디먹싱 취소
     */
    fun cancel() {
        isCancelled.set(true)
    }

    /**
     * 리소스 해제
     */
    fun release() {
        cancel()
        ffmpegDemuxer.release()
        isInitialized = false
    }

    private fun ensureInitialized() {
        if (isInitialized.not()) {
            initialize()
        }
    }
}

/**
 * 디먹싱 결과
 *
 * @property tracks 트랙 포맷 목록
 * @property samples 모든 샘플 목록
 */
data class DemuxResult(
    val tracks: List<TrackFormat>,
    val samples: List<DemuxedSample>
) {
    val videoSamples: List<DemuxedSample>
        get() = samples.filter { it.isVideo }

    val audioSamples: List<DemuxedSample>
        get() = samples.filter { it.isAudio }

    val videoTrack: TrackFormat?
        get() = tracks.find { it.isVideo }

    val audioTrack: TrackFormat?
        get() = tracks.find { it.isAudio }
}

/**
 * 디먹서 예외
 */
class DemuxerException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
