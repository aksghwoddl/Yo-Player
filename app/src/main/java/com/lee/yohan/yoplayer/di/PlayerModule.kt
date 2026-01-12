package com.lee.yohan.yoplayer.di

import com.yohan.yoplayersdk.demuxer.TsDemuxer
import com.yohan.yoplayersdk.m3u8.M3u8Downloader
import com.yohan.yoplayersdk.player.YoPlayer
import com.yohan.yoplayersdk.player.YoPlayerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideM3u8Downloader(): M3u8Downloader = M3u8Downloader()

    @Provides
    @Singleton
    fun provideTsDemuxer(): TsDemuxer = TsDemuxer()

    @Provides
    @Singleton
    fun provideYoPlayer(
        m3u8Downloader: M3u8Downloader,
        tsDemuxer: TsDemuxer
    ): YoPlayer = YoPlayerImpl(m3u8Downloader, tsDemuxer)
}
