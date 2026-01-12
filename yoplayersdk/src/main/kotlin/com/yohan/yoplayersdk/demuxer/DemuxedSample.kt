package com.yohan.yoplayersdk.demuxer

/**
 * 디먹싱된 샘플 데이터
 *
 * @property trackType 트랙 타입 (TRACK_TYPE_VIDEO=2, TRACK_TYPE_AUDIO=1)
 * @property timeUs 프레젠테이션 타임스탬프 (마이크로초)
 * @property flags 샘플 플래그 (KEY_FRAME, DECODE_ONLY 등)
 * @property data 압축된 샘플 데이터
 */
data class DemuxedSample(
    val trackType: Int,
    val timeUs: Long,
    val flags: Int,
    val data: ByteArray
) {
    companion object {
        const val FLAG_KEY_FRAME = 1
        const val FLAG_DECODE_ONLY = 2
    }

    val isKeyFrame: Boolean
        get() = (flags and FLAG_KEY_FRAME) != 0

    val isDecodeOnly: Boolean
        get() = (flags and FLAG_DECODE_ONLY) != 0

    val isVideo: Boolean
        get() = trackType == TrackFormat.TRACK_TYPE_VIDEO

    val isAudio: Boolean
        get() = trackType == TrackFormat.TRACK_TYPE_AUDIO

    val size: Int
        get() = data.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DemuxedSample
        return trackType == other.trackType &&
                timeUs == other.timeUs &&
                flags == other.flags &&
                data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = trackType
        result = 31 * result + timeUs.hashCode()
        result = 31 * result + flags
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String {
        val type = if (isVideo) "VIDEO" else "AUDIO"
        val keyFrame = if (isKeyFrame) " [KEY]" else ""
        return "DemuxedSample($type, timeUs=$timeUs, size=${data.size}$keyFrame)"
    }
}
