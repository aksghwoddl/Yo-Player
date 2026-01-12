package com.yohan.yoplayersdk.demuxer

import android.util.Log

/**
 * FFmpeg 네이티브 디먹서 JNI 래퍼
 */

private const val TAG = "FfmpegDemuxer"

internal class FfmpegDemuxer {

    companion object {
        private const val LIB_NAME = "ffmpegDemuxerJNI"

        private var isLibraryLoaded = false

        /**
         * 네이티브 라이브러리 로드 시도
         */
        @Synchronized
        fun loadLibrary() {
            try {
                System.loadLibrary(LIB_NAME)
                isLibraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.e("FfmpegDemuxer", "loadLibrary Error => $e")
            }
        }
    }

    private var nativeContext: Long = 0
    private val isInitialized: Boolean get() = nativeContext != 0L

    /**
     * 디먹서 초기화
     */
    fun initialize() {
        if (isLibraryLoaded.not()) {
            loadLibrary()
        }
        if (!isInitialized) {
            nativeContext = nativeInit()
        }
    }

    /**
     * 세그먼트 데이터를 분석하여 트랙 정보 반환
     * @param data TS 세그먼트 바이트 배열
     * @return 트랙 포맷 목록
     */
    fun probeSegment(data: ByteArray): List<TrackFormat> {
        if (!isInitialized) {
            throw IllegalStateException("Demuxer not initialized")
        }
        val tracks = nativeProbeSegment(nativeContext, data)
        return tracks?.toList() ?: emptyList()
    }

    /**
     * 세그먼트 데이터를 디먹싱하여 샘플 추출
     * @param data TS 세그먼트 바이트 배열
     * @return 추출된 샘플 목록
     */
    fun demuxSegment(data: ByteArray): List<DemuxedSample> {
        if (!isInitialized) {
            throw IllegalStateException("Demuxer not initialized")
        }
        val samples = nativeDemuxSegment(nativeContext, data)
        return samples?.toList() ?: emptyList()
    }

    /**
     * FFmpeg 버전 정보 반환
     */
    fun getVersion(): String {
        return if (isLibraryLoaded) nativeGetVersion() else "Library not loaded"
    }

    /**
     * 리소스 해제
     */
    fun release() {
        if (isInitialized) {
            nativeRelease(nativeContext)
            nativeContext = 0
        }
    }

    private external fun nativeInit(): Long
    private external fun nativeProbeSegment(context: Long, data: ByteArray): Array<TrackFormat>?
    private external fun nativeDemuxSegment(context: Long, data: ByteArray): Array<DemuxedSample>?
    private external fun nativeRelease(context: Long)
    private external fun nativeGetVersion(): String
}
