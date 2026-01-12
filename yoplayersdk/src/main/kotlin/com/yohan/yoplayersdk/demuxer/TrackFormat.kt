package com.yohan.yoplayersdk.demuxer

/**
 * 디먹싱된 트랙의 포맷 정보
 *
 * @property trackType 트랙 타입 (TRACK_TYPE_VIDEO=2, TRACK_TYPE_AUDIO=1)
 * @property mimeType MIME 타입 (예: "video/avc", "audio/mp4a-latm")
 * @property width 비디오 너비 (오디오는 0)
 * @property height 비디오 높이 (오디오는 0)
 * @property extraData 코덱 초기화 데이터 (SPS/PPS, AudioSpecificConfig 등)
 * @property sampleRate 오디오 샘플레이트 (비디오는 0)
 * @property channelCount 오디오 채널 수 (비디오는 0)
 */
data class TrackFormat(
    val trackType: Int,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val extraData: ByteArray?,
    val sampleRate: Int,
    val channelCount: Int
) {
    companion object {
        const val TRACK_TYPE_AUDIO = 1
        const val TRACK_TYPE_VIDEO = 2
    }

    val isVideo: Boolean
        get() = trackType == TRACK_TYPE_VIDEO

    val isAudio: Boolean
        get() = trackType == TRACK_TYPE_AUDIO

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TrackFormat
        return trackType == other.trackType &&
                mimeType == other.mimeType &&
                width == other.width &&
                height == other.height &&
                sampleRate == other.sampleRate &&
                channelCount == other.channelCount &&
                extraData.contentEquals(other.extraData)
    }

    override fun hashCode(): Int {
        var result = trackType
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + (extraData?.contentHashCode() ?: 0)
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        return result
    }

    override fun toString(): String {
        return if (isVideo) {
            "TrackFormat(VIDEO, $mimeType, ${width}x${height}, extraData=${extraData?.size ?: 0} bytes)"
        } else {
            "TrackFormat(AUDIO, $mimeType, ${sampleRate}Hz, ${channelCount}ch, extraData=${extraData?.size ?: 0} bytes)"
        }
    }
}
