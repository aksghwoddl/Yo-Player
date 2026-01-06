package com.yohan.yoplayersdk.m3u8

/**
 * M3U8 플레이리스트를 나타냅니다.
 * 마스터 플레이리스트 또는 미디어 플레이리스트 둘 다 표현할 수 있습니다.
 */
sealed interface M3u8Playlist {

    /**
     * 마스터 플레이리스트 - 여러 화질의 미디어 플레이리스트 목록을 포함
     *
     * @property baseUrl 플레이리스트의 기본 URL
     * @property variants 각 화질별 variant 스트림 목록
     */
    data class Master(
        val baseUrl: String,
        val variants: List<Variant>
    ) : M3u8Playlist {

        /**
         * Variant 스트림 정보
         *
         * @property url variant 플레이리스트 URL
         * @property bandwidth 대역폭 (bits/sec)
         * @property resolution 해상도 (예: "1920x1080")
         * @property codecs 코덱 정보
         * @property name 스트림 이름
         */
        data class Variant(
            val url: String,
            val bandwidth: Long,
            val resolution: String? = null,
            val codecs: String? = null,
            val name: String? = null
        )
    }

    /**
     * 미디어 플레이리스트 - 실제 미디어 세그먼트 목록을 포함
     *
     * @property baseUrl 플레이리스트의 기본 URL
     * @property targetDuration 최대 세그먼트 길이 (초)
     * @property mediaSequence 첫 번째 세그먼트의 시퀀스 번호
     * @property segments 미디어 세그먼트 목록
     * @property isEndList 마지막 플레이리스트 여부 (VOD인 경우 true)
     * @property playlistType 플레이리스트 타입 (VOD, EVENT 등)
     * @property totalDuration 전체 재생 시간 (초)
     */
    data class Media(
        val baseUrl: String,
        val targetDuration: Int,
        val mediaSequence: Int = 0,
        val segments: List<M3u8Segment>,
        val isEndList: Boolean = false,
        val playlistType: String? = null
    ) : M3u8Playlist {

        /** 전체 재생 시간 계산 */
        val totalDuration: Double
            get() = segments.sumOf { it.duration }

        /** 전체 세그먼트 개수 */
        val segmentCount: Int
            get() = segments.size
    }
}
