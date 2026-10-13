package com.liftr.di

import com.liftr.data.repository.VideoStorageRepositoryImpl
import com.liftr.domain.repository.VideoStorageRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for post-exercise video analysis dependencies (Feature 17).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VideoModule {

    @Binds
    @Singleton
    abstract fun bindVideoStorageRepository(
        impl: VideoStorageRepositoryImpl
    ): VideoStorageRepository
}
