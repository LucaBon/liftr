package com.liftr.vision.video

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Debug-only entry point: probes the metadata of video files without running an analysis.
 *
 * adb shell am broadcast -n com.liftr/.vision.video.VideoMetadataProbeReceiver \
 *     --es path <file or dir; relative paths resolve under the app's external files dir>
 * With no `path`, probes every video in the external files dir (recursively).
 */
class VideoMetadataProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val base = context.getExternalFilesDir(null) ?: return
        val arg = intent.getStringExtra("path")
        val target = when {
            arg == null -> base
            arg.startsWith("/") -> File(arg)
            else -> File(base, arg)
        }
        val files = target.walkTopDown().filter { it.isFile && it.extension.lowercase() in VIDEO_EXT }.toList()
        Log.i(VideoMetadataProbe.TAG, "probing ${files.size} file(s) under $target")
        files.forEach { VideoMetadataProbe.probe(it) }
    }

    private companion object {
        val VIDEO_EXT = setOf("mp4", "mov", "3gp", "mkv", "webm")
    }
}
