package com.liftr.vision.video

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * Debug probe: logs which container/format keys carry a video's capture frame
 * rate (logcat tag [TAG]). Used to tell 60/120/240 fps and slow-motion sources apart.
 */
object VideoMetadataProbe {
    const val TAG = "SlowMoProbe"
    private const val PTS_SAMPLES = 11

    fun probe(file: File) {
        Log.i(TAG, "=== probe ${file.name} (${file.length()} bytes) ===")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            fun key(name: String, k: Int) =
                Log.i(TAG, "retriever $name = ${runCatching { retriever.extractMetadata(k) }.getOrNull()}")
            key("CAPTURE_FRAMERATE", MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            key("VIDEO_FRAME_COUNT", MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            key("DURATION", MediaMetadataRetriever.METADATA_KEY_DURATION)
            key("VIDEO_WIDTH", MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            key("VIDEO_HEIGHT", MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            key("VIDEO_ROTATION", MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            key("BITRATE", MediaMetadataRetriever.METADATA_KEY_BITRATE)
        } catch (e: Exception) {
            Log.w(TAG, "retriever failed", e)
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                Log.i(TAG, "track $i mime=$mime")
                if (videoTrack < 0 && mime?.startsWith("video/") == true) videoTrack = i
            }
            if (videoTrack < 0) {
                Log.w(TAG, "no video track")
                return
            }
            // MediaFormat.toString() lists every key (frame-rate, capture-rate, ...).
            Log.i(TAG, "videoTrackFormat = ${extractor.getTrackFormat(videoTrack)}")
            extractor.selectTrack(videoTrack)
            val ptsUs = ArrayList<Long>()
            while (ptsUs.size < PTS_SAMPLES) {
                ptsUs.add(extractor.sampleTime)
                if (!extractor.advance()) break
            }
            Log.i(TAG, "first ${ptsUs.size} PTS (us) = $ptsUs")
            Log.i(TAG, "first PTS deltas (us) = ${ptsUs.zipWithNext { a, b -> b - a }}")
        } catch (e: Exception) {
            Log.w(TAG, "extractor failed", e)
        } finally {
            runCatching { extractor.release() }
        }
    }
}
