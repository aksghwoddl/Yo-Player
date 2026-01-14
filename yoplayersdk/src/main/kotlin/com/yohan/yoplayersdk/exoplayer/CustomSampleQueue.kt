package com.yohan.yoplayersdk.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import com.yohan.yoplayersdk.demuxer.DemuxedSample
import java.util.concurrent.ConcurrentLinkedQueue

internal class CustomSampleQueue(
    private val trackType: Int
) {
    companion object {
        // 메모리 보호를 위한 최대 샘플 수 (약 30초 분량)
        private const val MAX_VIDEO_SAMPLES = 1000  // 30fps * 30초
        private const val MAX_AUDIO_SAMPLES = 1500  // 약 30초 분량
    }

    private val sampleQueue = ConcurrentLinkedQueue<DemuxedSample>()
    private val maxSamples = if (trackType == 2) MAX_VIDEO_SAMPLES else MAX_AUDIO_SAMPLES

    @Volatile
    private var isEndOfStream = false

    @Volatile
    private var lastTimeUs = C.TIME_UNSET

    /**
     * 큐에 샘플 추가
     */
    fun queueSample(sample: DemuxedSample): Boolean {
        if (sample.trackType == trackType) {
            if (sampleQueue.size >= maxSamples) {
                return false
            }
            sampleQueue.offer(sample)
            lastTimeUs = sample.timeUs
        }
        return true
    }

    /**
     * 스트림 종료 표시
     */
    fun signalEndOfStream() {
        isEndOfStream = true
    }

    /**
     * 샘플 읽기
     *
     * @return C.RESULT_BUFFER_READ: 버퍼 읽기 성공
     *         C.RESULT_NOTHING_READ: 읽을 샘플 없음
     */
    @OptIn(UnstableApi::class)
    fun read(buffer: DecoderInputBuffer, omitSampleData: Boolean, peek: Boolean): Int {
        val sample = sampleQueue.peek()

        if (sample == null) {
            if (isEndOfStream) {
                buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }
            return C.RESULT_NOTHING_READ
        }

        buffer.timeUs = sample.timeUs

        // 플래그 설정
        var flags = 0
        if (sample.isKeyFrame) {
            flags = flags or C.BUFFER_FLAG_KEY_FRAME
        }
        buffer.setFlags(flags)

        if (omitSampleData.not()) {
            // 데이터 복사
            buffer.ensureSpaceForWrite(sample.data.size)
            buffer.data?.put(sample.data)
        }

        // omit/peek 여부와 무관하게 읽기 포인터는 전진해야 함
        if (peek.not()) {
            sampleQueue.poll()
        }

        return C.RESULT_BUFFER_READ
    }

    /**
     * 특정 위치까지 샘플 스킵
     */
    fun skipToPosition(positionUs: Long, toKeyframe: Boolean): Int {
        var skipped = 0
        while (true) {
            val sample = sampleQueue.peek() ?: break

            if (sample.timeUs >= positionUs) {
                break
            }

            if (toKeyframe && sample.isKeyFrame) {
                // 키프레임 전까지만 스킵
                break
            }

            sampleQueue.poll()
            skipped++
        }
        return skipped
    }

    /**
     * 버퍼링된 가장 마지막 샘플의 타임스탬프 반환
     */
    fun getBufferedPositionUs(): Long {
        return if (isEndOfStream && sampleQueue.isEmpty()) {
            C.TIME_END_OF_SOURCE
        } else {
            lastTimeUs
        }
    }

    /**
     * 데이터 사용 가능 여부
     */
    fun isReady(): Boolean {
        return sampleQueue.isNotEmpty() || isEndOfStream
    }

    fun hasCapacity(count: Int): Boolean {
        return sampleQueue.size + count <= maxSamples
    }

    /**
     * 큐 초기화
     */
    fun clear() {
        sampleQueue.clear()
        isEndOfStream = false
        lastTimeUs = C.TIME_UNSET
    }

    /**
     * 큐에 있는 샘플 수
     */
    fun getSampleCount(): Int = sampleQueue.size
}
