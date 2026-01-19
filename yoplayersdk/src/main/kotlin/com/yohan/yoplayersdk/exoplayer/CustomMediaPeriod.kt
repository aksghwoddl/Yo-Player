package com.yohan.yoplayersdk.exoplayer

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.NullableType
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.extractor.AvcConfig
import com.yohan.yoplayersdk.demuxer.DemuxedSample
import com.yohan.yoplayersdk.demuxer.TrackFormat

private const val TAG = "CustomMediaPeriod"

@UnstableApi
internal class CustomMediaPeriod : MediaPeriod {

    private var callback: MediaPeriod.Callback? = null
    private var trackGroupArray: TrackGroupArray = TrackGroupArray.EMPTY

    private val sampleQueues = mutableMapOf<Int, CustomSampleQueue>()
    private val formats = mutableMapOf<Int, Format>()
    private val sampleStreams = mutableMapOf<Int, CustomSampleStream>()
    @Volatile
    private var isLoading = false

    /**
     * 트랙 정보를 설정하고 준비 완료를 알림
     */
    fun setTracks(tracks: List<TrackFormat>) {
        Log.d(
            TAG,
            "setTracks called, tracks=${tracks.size}, prepared=${trackGroupArray.length > 0}, callback=${callback != null}"
        )
        if (trackGroupArray.length > 0) return

        val trackGroups = tracks.map { track ->
            val format = track.toFormat()
            formats[track.trackType] = format
            sampleQueues[track.trackType] = CustomSampleQueue(track.trackType)
            Log.d(TAG, "Added track: ${track.mimeType}, trackType=${track.trackType}")
            TrackGroup(format)
        }

        trackGroupArray = TrackGroupArray(*trackGroups.toTypedArray())
        Log.d(TAG, "Calling onPrepared, callback=${callback != null}")
        callback?.onPrepared(this)
    }

    fun queueSample(sample: DemuxedSample): Boolean {
        if (sample.timeUs == C.TIME_UNSET) {
            return false
        }
        return queueSampleInternal(sample)
    }

    private fun queueSampleInternal(sample: DemuxedSample): Boolean {
        val adjustedSample = DemuxedSample(
            trackType = sample.trackType,
            timeUs = sample.timeUs,
            flags = sample.flags,
            data = if (sample.isVideo || shouldStripAdts().not()) {
                sample.data
            } else {
                stripAdtsHeader(sample.data) ?: sample.data
            }
        )

        return sampleQueues[sample.trackType]?.queueSample(adjustedSample) ?: false
    }

    private fun shouldStripAdts(): Boolean {
        val audioMimeType = formats[TrackFormat.TRACK_TYPE_AUDIO]?.sampleMimeType
        return audioMimeType == "audio/mp4a-latm" || audioMimeType == "audio/aac"
    }


    private fun hasAdtsHeader(data: ByteArray): Boolean {
        if (data.size < 7) return false
        return data[0] == 0xFF.toByte() && (data[1].toInt() and 0xF0) == 0xF0
    }

    private fun stripAdtsHeader(data: ByteArray): ByteArray? {
        if (hasAdtsHeader(data).not()) return null
        val protectionAbsent = data[1].toInt() and 0x01
        val headerLength = if (protectionAbsent == 1) 7 else 9
        if (data.size <= headerLength) {
            return null
        }
        return data.copyOfRange(headerLength, data.size)
    }

    /**
     * 스트림 종료 표시
     */
    fun signalEndOfStream() {
        Log.d(TAG, "signalEndOfStream called")
        sampleQueues.values.forEach { it.signalEndOfStream() }
    }

    private fun TrackFormat.toFormat(): Format {
        val builder = Format.Builder()
            .setSampleMimeType(this.mimeType)

        when (this.trackType) {
            TrackFormat.TRACK_TYPE_VIDEO -> {
                val width = if (this.width > 0) this.width else 1920
                val height = if (this.height > 0) this.height else 1080
                builder.setWidth(width).setHeight(height)
                Log.d(
                    TAG,
                    "Video format: ${this.mimeType}, ${width}x${height}, extraData=${this.extraData?.size ?: 0} bytes"
                )
            }

            TrackFormat.TRACK_TYPE_AUDIO -> {
                val sampleRate = if (this.sampleRate > 0) this.sampleRate else 44100
                val channelCount = if (this.channelCount > 0) this.channelCount else 2
                builder.setSampleRate(sampleRate).setChannelCount(channelCount)
                Log.d(
                    TAG,
                    "Audio format: ${this.mimeType}, ${sampleRate}Hz, ${channelCount}ch, extraData=${this.extraData?.size ?: 0} bytes"
                )
            }
        }

        // 초기화 데이터 (SPS/PPS, AudioSpecificConfig 등)
        buildInitializationData(this)?.let { initData ->
            if (initData.isNotEmpty()) {
                builder.setInitializationData(initData)
            }
        }

        return builder.build()
    }

    private fun buildInitializationData(track: TrackFormat): List<ByteArray>? {
        val extraData = track.extraData ?: return null
        if (extraData.isEmpty()) return null
        if (track.isVideo && track.mimeType == "video/avc") {
            return buildAvcInitializationData(extraData)
        }
        return listOf(extraData)
    }

    private fun buildAvcInitializationData(extraData: ByteArray): List<ByteArray>? {
        CodecSpecificDataUtil.splitNalUnits(extraData)?.let { nalUnits ->
            return nalUnits.toList()
        }
        return try {
            val avcConfig = AvcConfig.parse(ParsableByteArray(extraData))
            avcConfig.initializationData
        } catch (e: ParserException) {
            null
        }
    }

    // MediaPeriod interface implementation

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        Log.d(TAG, "prepare called, positionUs=$positionUs")
        this.callback = callback
    }

    override fun maybeThrowPrepareError() {
        // 현재 에러 없음
    }

    override fun getTrackGroups(): TrackGroupArray {
        return trackGroupArray
    }

    override fun selectTracks(
        selections: Array<@NullableType ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<@NullableType SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        selections.forEachIndexed { index, selection ->
            if (selection != null) {
                val trackGroup = selection.trackGroup
                val format = trackGroup.getFormat(0)
                val trackType = getTrackTypeFromFormat(format)
                val sampleQueue = sampleQueues[trackType]
                val trackFormat = formats[trackType]

                Log.d(
                    TAG,
                    "  selection[$index]: trackType=$trackType, mimeType=${format.sampleMimeType}"
                )

                if (sampleQueue != null && trackFormat != null) {
                    if (streams[index] == null || mayRetainStreamFlags[index].not()) {
                        val stream = CustomSampleStream(sampleQueue, trackFormat)
                        streams[index] = stream
                        sampleStreams[trackType] = stream
                        streamResetFlags[index] = true
                        Log.d(TAG, "  Created SampleStream for trackType=$trackType")
                    }
                }
            } else {
                streams[index] = null
            }
        }
        return positionUs
    }

    private fun getTrackTypeFromFormat(format: Format): Int {
        return when {
            format.sampleMimeType?.startsWith("video/") == true -> TrackFormat.TRACK_TYPE_VIDEO
            format.sampleMimeType?.startsWith("audio/") == true -> TrackFormat.TRACK_TYPE_AUDIO
            else -> 0
        }
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        sampleQueues.values.forEach {
            it.skipToPosition(positionUs, toKeyframe)
        }
    }

    override fun readDiscontinuity(): Long {
        return C.TIME_UNSET
    }

    override fun seekToUs(positionUs: Long): Long {
        // 간단한 seek 구현 - 현재는 지원하지 않음
        return positionUs
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
        return positionUs
    }

    override fun getBufferedPositionUs(): Long {
        var bufferedPosition = Long.MAX_VALUE
        sampleQueues.values.forEach { queue ->
            val queueBuffered = queue.getBufferedPositionUs()
            if (queueBuffered == C.TIME_END_OF_SOURCE) {
                return@forEach
            }
            if (queueBuffered == C.TIME_UNSET) {
                bufferedPosition = 0L
                return@forEach
            }
            bufferedPosition = minOf(bufferedPosition, queueBuffered)
        }
        return if (bufferedPosition == Long.MAX_VALUE) C.TIME_END_OF_SOURCE else bufferedPosition
    }

    override fun getNextLoadPositionUs(): Long {
        return if (isLoading) getBufferedPositionUs() else C.TIME_END_OF_SOURCE
    }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        // 로딩은 외부에서 처리
        return false
    }

    override fun isLoading(): Boolean {
        return isLoading
    }

    override fun reevaluateBuffer(positionUs: Long) {
        // 버퍼 재평가 불필요
    }

    fun setLoading(loading: Boolean) {
        isLoading = loading
    }

    fun hasCapacity(videoCount: Int, audioCount: Int): Boolean {
        val videoQueue = sampleQueues[TrackFormat.TRACK_TYPE_VIDEO]
        val audioQueue = sampleQueues[TrackFormat.TRACK_TYPE_AUDIO]

        val videoOk = videoQueue?.hasCapacity(videoCount) ?: true
        val audioOk = audioQueue?.hasCapacity(audioCount) ?: true

        return videoOk && audioOk
    }

    fun release() {
        sampleQueues.values.forEach { it.clear() }
        sampleQueues.clear()
        formats.clear()
        sampleStreams.clear()
        callback = null
    }
}
