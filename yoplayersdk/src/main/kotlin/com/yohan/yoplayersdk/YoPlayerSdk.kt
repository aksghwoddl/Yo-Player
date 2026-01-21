package com.yohan.yoplayersdk

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.yohan.yoplayersdk.player.YoPlayer
import com.yohan.yoplayersdk.player.YoPlayerImpl

object YoPlayerSdk {

    /**
     * YoPlayer 인스턴스 생성
     *
     * @param context Android Context (Application 또는 Activity)
     * @return YoPlayer 인스턴스
     */
    @OptIn(UnstableApi::class)
    fun buildPlayer(context: Context): YoPlayer {
        return YoPlayerImpl(
            context = context.applicationContext
        )
    }
}
