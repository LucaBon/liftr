package com.liftr

import android.app.Application
import com.liftr.domain.repository.VideoStorageRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class LiftrApplication : Application() {

    /**
     * Injected lazily by Hilt after `super.onCreate()`, so it must not be
     * touched before that point.
     */
    @Inject lateinit var videoStorageRepository: VideoStorageRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.d("Liftr Application started")

        purgeStaleRecordings()
    }

    /**
     * Reclaims workout recordings left behind by interrupted analyses.
     *
     * Recordings are only deleted on the normal completion paths, so anything
     * killed mid-analysis — or an annotated export the user never dismissed —
     * stays on disk forever. These files are tens to hundreds of megabytes
     * each, so without a retention pass they accumulate silently in app-private
     * storage where the user cannot see or clear them individually.
     *
     * Runs on IO at startup: it only touches file metadata, and the window is
     * far longer than any in-flight recording could be.
     */
    private fun purgeStaleRecordings() {
        applicationScope.launch {
            runCatching {
                videoStorageRepository.cleanupOldRecordings(RECORDING_RETENTION_MS)
            }.onFailure { Timber.w(it, "Recording retention pass failed") }
        }
    }

    companion object {
        /**
         * How long a workout recording is kept before it is reclaimed.
         *
         * Long enough to review a session across a few days, short enough that
         * abandoned files cannot pile up indefinitely.
         */
        private val RECORDING_RETENTION_MS = TimeUnit.DAYS.toMillis(7)
    }
}
