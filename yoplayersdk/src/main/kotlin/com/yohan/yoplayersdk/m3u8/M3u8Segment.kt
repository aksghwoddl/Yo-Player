package com.yohan.yoplayersdk.m3u8

/**
 * M3U8 플레이리스트의 미디어 세그먼트를 나타냅니다.
 *
 * @property url 세그먼트의 URL (절대 경로 또는 상대 경로)
 * @property duration 세그먼트의 재생 시간 (초)
 * @property sequenceNumber 세그먼트의 시퀀스 번호
 * @property title 세그먼트 제목 (있으면)
 * @property byteRangeOffset 바이트 범위 시작 (없으면 null)
 * @property byteRangeLength 바이트 범위 길이 (없으면 null)
 * @property encryptionInfo 암호화 정보 (없으면 null)
 * @property hasDiscontinuity 불연속성 표시 (이전 세그먼트와 연속되지 않음)
 */
data class M3u8Segment(
    val url: String,
    val duration: Double,
    val sequenceNumber: Int,
    val title: String? = null,
    val byteRangeOffset: Long? = null,
    val byteRangeLength: Long? = null,
    val encryptionInfo: EncryptionInfo? = null,
    val hasDiscontinuity: Boolean = false
) {
    /**
     * 암호화 정보
     *
     * @property method 암호화 방식 (AES-128, SAMPLE-AES 등)
     * @property keyUrl 암호화 키 URL
     * @property iv 초기화 벡터 (16진수 문자열)
     */
    data class EncryptionInfo(
        val method: String,
        val keyUrl: String,
        val iv: String? = null
    )
}
