package com.yohan.yoplayersdk

import android.content.Context
import com.yohan.yoplayersdk.demuxer.TsDemuxer
import com.yohan.yoplayersdk.m3u8.M3u8Downloader
import com.yohan.yoplayersdk.player.YoPlayer
import com.yohan.yoplayersdk.player.YoPlayerImpl

object YoPlayerSdk {

    /**
     * YoPlayer 인스턴스 생성
     *
     * @param context Android Context (Application 또는 Activity)
     * @return YoPlayer 인스턴스
     */
    fun buildPlayer(context: Context): YoPlayer {
        return YoPlayerImpl(
            context = context.applicationContext,
            m3u8Downloader = M3u8Downloader(),
            tsDemuxer = TsDemuxer()
        )
    }
}
