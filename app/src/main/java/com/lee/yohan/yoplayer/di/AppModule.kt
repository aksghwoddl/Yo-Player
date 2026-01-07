package com.lee.yohan.yoplayer.di

import com.yohan.yoplayersdk.m3u8.M3u8Downloader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideM3u8Downloader(): M3u8Downloader {
        return M3u8Downloader()
    }
}
