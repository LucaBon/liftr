package com.lifthelper.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CameraExecutor

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    @Provides
    @Singleton
    @CameraExecutor
    fun provideCameraExecutor(): ExecutorService {
        return Executors.newSingleThreadExecutor()
    }

    @Provides
    @Singleton
    fun provideCameraManager(
        @ApplicationContext context: Context,
        @CameraExecutor executor: ExecutorService
    ): com.lifthelper.vision.camera.CameraManager {
        return com.lifthelper.vision.camera.CameraManager(context, executor)
    }
}
