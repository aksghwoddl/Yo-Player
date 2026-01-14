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
import androidx.media3.extractor.AacUtil
import androidx.media3.extractor.AvcConfig
import com.yohan.yoplayersdk.demuxer.DemuxedSample
import com.yohan.yoplayersdk.demuxer.TrackFormat

private const val TAG = "CustomMediaPeriod"
private const val MAX_AV_SYNC_OFFSET_US = 500_000L
private const val MIN_SAMPLE_SPACING_US = 1_000L

@UnstableApi
internal class CustomMediaPeriod : MediaPeriod {

    private var callback: MediaPeriod.Callback? = null
    private var trackGroupArray: TrackGroupArray = TrackGroupArray.EMPTY

    private val sampleQueues = mutableMapOf<Int, CustomSampleQueue>()
    private val formats = mutableMapOf<Int, Format>()
    private val sampleStreams = mutableMapOf<Int, CustomSampleStream>()
    private var prepared = false

    @Volatile
    private var isLoading = false

    @Volatile
    private var lastVideoTimeUs: Long = C.TIME_UNSET  // 마지막 비디오 타임스탬프

    @Volatile
    private var lastAudioTimeUs: Long = C.TIME_UNSET  // 마지막 오디오 타임스탬프

    @Volatile
    private var audioNextTimeUs: Long = C.TIME_UNSET

    /**
     * 트랙 정보를 설정하고 준비 완료를 알림
     */
    fun setTracks(tracks: List<TrackFormat>) {
        Log.d(
            TAG,
            "setTracks called, tracks=${tracks.size}, prepared=$prepared, callback=${callback != null}"
        )
        if (prepared) return

        val trackGroups = tracks.map { track ->
            val format = convertToFormat(track)
            formats[track.trackType] = format
            sampleQueues[track.trackType] = CustomSampleQueue(track.trackType)

            Log.d(TAG, "Added track: ${track.mimeType}, trackType=${track.trackType}")
            TrackGroup(format)
        }

        trackGroupArray = TrackGroupArray(*trackGroups.toTypedArray())
        prepared = true
        Log.d(TAG, "Calling onPrepared, callback=${callback != null}")
        callback?.onPrepared(this)
    }

    /**
     * 샘플을 큐에 추가
     * 세그먼트 간 타임스탬프 연속성을 유지
     */
    fun queueSamples(samples: List<DemuxedSample>, segmentStartUs: Long) {
        if (samples.isEmpty()) return

        val validSamples = samples.filter { it.timeUs != C.TIME_UNSET }
        if (validSamples.isEmpty()) {
            Log.w(TAG, "queueSamples: invalid timestamps only, samples=${samples.size}")
            return
        }

        val audioSamples = validSamples.filter { it.isAudio }
        val videoSamples = validSamples.filter { it.isVideo }
        val audioMinTimeUs = audioSamples.minOfOrNull { it.timeUs }
        val videoMinTimeUs = videoSamples.minOfOrNull { it.timeUs }

        val audioFormat = formats[TrackFormat.TRACK_TYPE_AUDIO]
        val audioFrameDurationUs = getAudioFrameDurationUs(audioFormat)
        val audioMimeType = audioFormat?.sampleMimeType
        val shouldStripAdts = audioMimeType == "audio/mp4a-latm" || audioMimeType == "audio/aac"
        val segmentBaseUs = if (audioSamples.isNotEmpty()) {
            if (audioNextTimeUs == C.TIME_UNSET) {
                audioNextTimeUs = segmentStartUs
            }
            audioNextTimeUs
        } else {
            segmentStartUs
        }
        val avOffsetUs = if (audioMinTimeUs != null && videoMinTimeUs != null) {
            val offset = videoMinTimeUs - audioMinTimeUs
            if (kotlin.math.abs(offset) <= MAX_AV_SYNC_OFFSET_US) offset else 0L
        } else {
            0L
        }

        // 샘플 처리
        var videoCount = 0
        var audioCount = 0
        var dropped = 0
        var audioIndex = 0

        validSamples.forEach { sample ->
            val isVideo = sample.isVideo

            val normalizedTimeUs = if (isVideo) {
                val baseVideoTimeUs = videoMinTimeUs ?: sample.timeUs
                val relativeTimeUs = (sample.timeUs - baseVideoTimeUs).coerceAtLeast(0L)
                adjustVideoTimeUs(segmentBaseUs + avOffsetUs + relativeTimeUs)
            } else {
                val timeUs = segmentBaseUs + (audioIndex * audioFrameDurationUs)
                audioIndex++
                adjustAudioTimeUs(timeUs, audioFrameDurationUs)
            }

            // 로그 (처음 몇 개만)
            if (isVideo && videoCount < 3) {
                Log.d(
                    TAG,
                    "[V] ${sample.timeUs} -> $normalizedTimeUs (segmentStartUs=$segmentBaseUs, avOffsetUs=$avOffsetUs)"
                )
                videoCount++
            } else if (isVideo.not() && audioCount < 3) {
                Log.d(
                    TAG,
                    "[A] ${sample.timeUs} -> $normalizedTimeUs (segmentStartUs=$segmentBaseUs)"
                )
                audioCount++
            }

            val adjustedSample = DemuxedSample(
                trackType = sample.trackType,
                timeUs = normalizedTimeUs,
                flags = sample.flags,
                data = if (isVideo || shouldStripAdts.not()) {
                    sample.data
                } else {
                    stripAdtsHeader(sample.data) ?: sample.data
                }
            )
            val queued = sampleQueues[sample.trackType]?.queueSample(adjustedSample) ?: false
            if (queued.not()) {
                Log.w(TAG, "Queue full, dropped sample: trackType=${sample.trackType}")
                dropped++
                return@forEach
            }

            // 마지막 타임스탬프 업데이트
            if (isVideo) {
                lastVideoTimeUs = updateLastTimeUs(lastVideoTimeUs, normalizedTimeUs)
            } else {
                lastAudioTimeUs = updateLastTimeUs(lastAudioTimeUs, normalizedTimeUs)
            }
        }

        if (audioSamples.isNotEmpty()) {
            audioNextTimeUs = segmentBaseUs + (audioIndex * audioFrameDurationUs)
        }

        val invalidCount = samples.size - validSamples.size
        if (invalidCount > 0) {
            Log.d(TAG, "Skipped $invalidCount samples (invalid timestamps)")
        }
        if (dropped > 0) {
            Log.w(TAG, "Dropped $dropped samples (queue full)")
        }

        // 큐 상태 로그
        val videoQueueSize = sampleQueues[TrackFormat.TRACK_TYPE_VIDEO]?.getSampleCount() ?: 0
        val audioQueueSize = sampleQueues[TrackFormat.TRACK_TYPE_AUDIO]?.getSampleCount() ?: 0
        Log.v(TAG, "Queue: video=$videoQueueSize, audio=$audioQueueSize")
    }

    private fun updateLastTimeUs(current: Long, candidate: Long): Long {
        return if (current == C.TIME_UNSET || candidate > current) {
            candidate
        } else {
            current
        }
    }

    private fun adjustAudioTimeUs(timeUs: Long, frameDurationUs: Long): Long {
        if (lastAudioTimeUs == C.TIME_UNSET) {
            return timeUs
        }
        return if (timeUs <= lastAudioTimeUs) {
            lastAudioTimeUs + frameDurationUs
        } else {
            timeUs
        }
    }

    private fun adjustVideoTimeUs(timeUs: Long): Long {
        if (lastVideoTimeUs == C.TIME_UNSET) {
            return timeUs
        }
        return if (timeUs <= lastVideoTimeUs) {
            lastVideoTimeUs + MIN_SAMPLE_SPACING_US
        } else {
            timeUs
        }
    }

    private fun getAudioFrameDurationUs(format: Format?): Long {
        if (format == null || format.sampleRate <= 0) {
            return MIN_SAMPLE_SPACING_US
        }
        val frameCount = getAacFrameSampleCount(format)
        return (1_000_000L * frameCount) / format.sampleRate
    }

    private fun getAacFrameSampleCount(format: Format): Int {
        val init =
            format.initializationData.firstOrNull() ?: return AacUtil.AAC_LC_AUDIO_SAMPLE_COUNT
        val audioObjectType = try {
            getAudioObjectTypeFromConfig(AacUtil.parseAudioSpecificConfig(init))
        } catch (e: ParserException) {
            null
        }
        return when (audioObjectType) {
            AacUtil.AUDIO_OBJECT_TYPE_AAC_SBR,
            AacUtil.AUDIO_OBJECT_TYPE_AAC_PS -> AacUtil.AAC_HE_AUDIO_SAMPLE_COUNT

            AacUtil.AUDIO_OBJECT_TYPE_AAC_ELD -> AacUtil.AAC_LD_AUDIO_SAMPLE_COUNT
            AacUtil.AUDIO_OBJECT_TYPE_AAC_XHE -> AacUtil.AAC_XHE_AUDIO_SAMPLE_COUNT
            AacUtil.AUDIO_OBJECT_TYPE_AAC_LC -> AacUtil.AAC_LC_AUDIO_SAMPLE_COUNT
            else -> AacUtil.AAC_LC_AUDIO_SAMPLE_COUNT
        }
    }

    private fun getAudioObjectTypeFromConfig(config: AacUtil.Config): Int? {
        val suffix = config.codecs.substringAfterLast('.', "")
        return suffix.toIntOrNull()
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

    /**
     * TrackFormat을 Media3 Format으로 변환
     */
    private fun convertToFormat(track: TrackFormat): Format {
        val builder = Format.Builder()
            .setSampleMimeType(track.mimeType)

        if (track.isVideo) {
            // width/height가 0이면 기본값 설정 (나중에 코덱에서 자동 감지)
            val width = if (track.width > 0) track.width else 1920
            val height = if (track.height > 0) track.height else 1080
            builder.setWidth(width)
                .setHeight(height)
            Log.d(
                TAG,
                "Video format: ${track.mimeType}, ${width}x${height}, extraData=${track.extraData?.size ?: 0} bytes"
            )
        } else if (track.isAudio) {
            val sampleRate = if (track.sampleRate > 0) track.sampleRate else 44100
            val channelCount = if (track.channelCount > 0) track.channelCount else 2
            builder.setSampleRate(sampleRate)
                .setChannelCount(channelCount)
            Log.d(
                TAG,
                "Audio format: ${track.mimeType}, ${sampleRate}Hz, ${channelCount}ch, extraData=${track.extraData?.size ?: 0} bytes"
            )
        }

        // 초기화 데이터 (SPS/PPS, AudioSpecificConfig 등)
        buildInitializationData(track)?.let { initData ->
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
        // 트랙 정보가 설정되면 setTracks에서 onPrepared 호출됨
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
        Log.d(TAG, "selectTracks called, selections=${selections.size}, positionUs=$positionUs")
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
        lastVideoTimeUs = C.TIME_UNSET
        lastAudioTimeUs = C.TIME_UNSET
        audioNextTimeUs = C.TIME_UNSET
    }
}
