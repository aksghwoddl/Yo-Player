package com.yohan.yoplayersdk

import com.yohan.yoplayersdk.demuxer.TsDemuxer
import com.yohan.yoplayersdk.m3u8.M3u8Downloader
import com.yohan.yoplayersdk.player.YoPlayer
import com.yohan.yoplayersdk.player.YoPlayerImpl

object YoPlayerSdk {

    fun buildPlayer(): YoPlayer {
        return YoPlayerImpl(
            m3u8Downloader = M3u8Downloader(),
            tsDemuxer = TsDemuxer()
        )
    }
}