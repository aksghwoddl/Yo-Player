package com.yohan.yoplayersdk.demuxer

/**
 * 디먹싱 진행 상황 리스너
 */
interface DemuxerListener {

    /**
     * 트랙 정보 발견됨
     *
     * @param tracks 발견된 트랙 포맷 목록 (비디오, 오디오)
     */
    fun onTracksFound(tracks: List<TrackFormat>)

    /**
     * 샘플 추출됨
     *
     * @param sample 추출된 샘플 데이터
     */
    fun onSampleExtracted(sample: DemuxedSample)

    /**
     * 세그먼트 디먹싱 완료
     *
     * @param segmentIndex 완료된 세그먼트 인덱스
     * @param sampleCount 해당 세그먼트에서 추출된 샘플 수
     */
    fun onSegmentCompleted(segmentIndex: Int, sampleCount: Int)

    /**
     * 전체 디먹싱 완료
     *
     * @param totalSegments 처리된 총 세그먼트 수
     * @param totalSamples 추출된 총 샘플 수
     */
    fun onDemuxingCompleted(totalSegments: Int, totalSamples: Int)

    /**
     * 에러 발생
     *
     * @param error 발생한 에러
     * @param segmentIndex 에러 발생 세그먼트 인덱스 (-1이면 전역 에러)
     */
    fun onError(error: Throwable, segmentIndex: Int = -1)
}
