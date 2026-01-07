package com.yohan.yoplayersdk.m3u8

/**
 * M3U8 다운로드 진행 상황 리스너
 */
interface M3u8DownloadListener {

    /**
     * 다운로드 시작
     *
     * @param playlist 다운로드할 플레이리스트
     * @param totalSegments 전체 세그먼트 수
     */
    fun onDownloadStarted(playlist: M3u8Playlist.Media, totalSegments: Int)

    /**
     * 세그먼트 다운로드 완료 - 바이트 데이터와 함께 전달
     *
     * @param segment 완료된 세그먼트 정보
     * @param data 다운로드된 바이트 데이터
     * @param currentIndex 현재 인덱스 (0부터 시작)
     * @param totalSegments 전체 세그먼트 수
     */
    fun onSegmentDownloaded(
        segment: M3u8Segment,
        data: ByteArray,
        currentIndex: Int,
        totalSegments: Int
    )

    /**
     * 전체 다운로드 진행률
     *
     * @param progress 진행률 (0.0 ~ 1.0)
     * @param downloadedSegments 다운로드 완료된 세그먼트 수
     * @param totalSegments 전체 세그먼트 수
     */
    fun onProgressUpdate(progress: Float, downloadedSegments: Int, totalSegments: Int)

    /**
     * 전체 다운로드 완료
     *
     * @param segments 다운로드된 모든 세그먼트 데이터 목록
     * @param totalBytes 다운로드된 전체 바이트
     * @param elapsedTimeMs 소요 시간 (밀리초)
     */
    fun onDownloadCompleted(
        segments: List<DownloadedSegment>,
        totalBytes: Long,
        elapsedTimeMs: Long
    )

    /**
     * 다운로드 오류 발생
     *
     * @param error 오류 정보
     * @param segment 오류 발생한 세그먼트 (세그먼트 관련 오류인 경우)
     */
    fun onDownloadError(error: Throwable, segment: M3u8Segment? = null)

    /**
     * 다운로드 취소됨
     */
    fun onDownloadCancelled()
}

/**
 * 다운로드된 세그먼트 데이터
 *
 * @property segment 세그먼트 정보
 * @property data 바이트 데이터
 */
data class DownloadedSegment(
    val segment: M3u8Segment,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DownloadedSegment
        return segment == other.segment && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = segment.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}