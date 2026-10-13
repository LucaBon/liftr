package com.liftr.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.liftr.domain.builder.AnthropometricDataBuilder
import com.liftr.domain.calculator.CoordinateCalculator
import com.liftr.domain.extractor.BodySegmentExtractor
import com.liftr.domain.logging.Logger
import com.liftr.domain.validation.MeasurementValidator
import com.liftr.domain.validation.PoseValidator
import com.liftr.infrastructure.logging.AndroidLogger
import com.liftr.vision.calculator.CameraAlignmentChecker
import dagger.Binds
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
    fun provideLensDistortionCorrector(logger: Logger): com.liftr.vision.camera.LensDistortionCorrector {
        return com.liftr.vision.camera.LensDistortionCorrector(logger)
    }

    @Provides
    @Singleton
    fun provideCameraManager(
        @ApplicationContext context: Context,
        @CameraExecutor executor: ExecutorService,
        lensDistortionCorrector: com.liftr.vision.camera.LensDistortionCorrector
    ): com.liftr.vision.camera.CameraManager {
        return com.liftr.vision.camera.CameraManager(context, executor, lensDistortionCorrector)
    }

    // Domain layer components
    @Provides
    @Singleton
    fun providePoseValidator(): PoseValidator {
        return PoseValidator()
    }

    @Provides
    @Singleton
    fun provideCoordinateCalculator(): CoordinateCalculator {
        return CoordinateCalculator()
    }

    @Provides
    @Singleton
    fun provideBodySegmentExtractor(
        coordinateCalculator: CoordinateCalculator
    ): BodySegmentExtractor {
        return BodySegmentExtractor(coordinateCalculator)
    }

    @Provides
    @Singleton
    fun provideMeasurementValidator(): MeasurementValidator {
        return MeasurementValidator()
    }

    @Provides
    @Singleton
    fun provideCameraAlignmentChecker(): CameraAlignmentChecker {
        return CameraAlignmentChecker()
    }

    @Provides
    @Singleton
    fun provideAnthropometricDataBuilder(
        poseValidator: PoseValidator,
        coordinateCalculator: CoordinateCalculator,
        segmentExtractor: BodySegmentExtractor,
        measurementValidator: MeasurementValidator,
        alignmentChecker: CameraAlignmentChecker,
        logger: Logger
    ): AnthropometricDataBuilder {
        return AnthropometricDataBuilder(
            poseValidator = poseValidator,
            coordinateCalculator = coordinateCalculator,
            segmentExtractor = segmentExtractor,
            measurementValidator = measurementValidator,
            alignmentChecker = alignmentChecker,
            logger = logger
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LoggerModule {

    @Binds
    @Singleton
    abstract fun bindLogger(impl: AndroidLogger): Logger
}
