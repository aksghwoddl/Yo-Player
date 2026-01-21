package com.yohan.yoplayersdk.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream

@UnstableApi
internal class CustomSampleStream(
    private val sampleQueue: CustomSampleQueue,
    private var format: Format
) : SampleStream {

    private var formatSent = false

    override fun isReady(): Boolean {
        return sampleQueue.isReady()
    }

    override fun maybeThrowError() {
        // 에러 없음
    }

    override fun readData(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int
    ): Int {
        val requireFormat = (readFlags and SampleStream.FLAG_REQUIRE_FORMAT) != 0
        val omitSampleData = (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA) != 0
        val peek = (readFlags and SampleStream.FLAG_PEEK) != 0

        // 포맷을 먼저 전달해야 함
        if (formatSent.not() || requireFormat) {
            formatHolder.format = format
            formatSent = true
            return C.RESULT_FORMAT_READ
        }

        // 샘플 읽기
        return sampleQueue.read(buffer, omitSampleData, peek)
    }

    override fun skipData(positionUs: Long): Int {
        return sampleQueue.skipToPosition(positionUs, toKeyframe = false)
    }
}
