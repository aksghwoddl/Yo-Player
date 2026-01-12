package com.lee.yohan.yoplayer.di

import com.yohan.yoplayersdk.player.YoPlayer
import com.yohan.yoplayersdk.YoPlayerSdk
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
    fun provideYoPlayer(): YoPlayer = YoPlayerSdk.buildPlayer()
}
