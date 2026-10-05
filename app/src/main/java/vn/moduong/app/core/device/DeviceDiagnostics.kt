package vn.moduong.app.core.device

import android.os.Build
import vn.moduong.app.core.camera.RecorderProfile
import vn.moduong.app.core.camera.VideoFileProfile
import vn.moduong.app.core.storage.RecordingSessionFiles
import java.io.File
import java.io.IOException

internal class DeviceDiagnostics(private val session: RecordingSessionFiles) {
    private val logFile = File(session.directory, "diagnostics.tsv")

    @Throws(IOException::class)
    fun begin(
        profile: RecorderProfile,
        engineName: String,
        startedAtUtcMillis: Long,
        startedAtElapsedRealtimeNanos: Long,
    ) {
        logFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("format\t1")
            writer.appendLine("sessionId\t${session.id}")
            writer.appendLine("deviceModel\t${safe(Build.MODEL)}")
            writer.appendLine("manufacturer\t${safe(Build.MANUFACTURER)}")
            writer.appendLine("androidRelease\t${safe(Build.VERSION.RELEASE)}")
            writer.appendLine("apiLevel\t${Build.VERSION.SDK_INT}")
            writer.appendLine("firmwareBuild\t${safe(Build.DISPLAY)}")
            writer.appendLine("recorderEngine\t${safe(engineName)}")
            writer.appendLine("requestedQuality\t${safe(profile.requestedQuality)}")
            writer.appendLine("cameraXSupportedQualities\t${profile.supportedQualities.joinToString(",")}")
            writer.appendLine("sessionStartUtcMillis\t$startedAtUtcMillis")
            writer.appendLine("sessionStartElapsedRealtimeNanos\t$startedAtElapsedRealtimeNanos")
            writer.appendLine(
                "segment\tindex\tfile\tcodecMime\twidth\theight\tdurationUs\tfirstSamplePtsUs\tlastSamplePtsUs\tvideoSamples\taverageFps\tsizeBytes\tstartCallbackElapsedNs\tfinalizeCallbackElapsedNs",
            )
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun recordSegment(
        index: Int,
        file: File,
        profile: VideoFileProfile,
        startCallbackElapsedRealtimeNanos: Long,
        finalizeCallbackElapsedRealtimeNanos: Long,
    ) {
        logFile.appendText(
            listOf(
                "segment",
                index.toString(),
                file.name,
                safe(profile.codecMime),
                profile.width.toString(),
                profile.height.toString(),
                profile.durationUs.toString(),
                profile.firstSamplePtsUs.toString(),
                profile.lastSamplePtsUs.toString(),
                profile.videoSamples.toString(),
                "%.2f".format(java.util.Locale.US, profile.averageFramesPerSecond),
                profile.sizeBytes.toString(),
                startCallbackElapsedRealtimeNanos.toString(),
                finalizeCallbackElapsedRealtimeNanos.toString(),
            ).joinToString("\t") + "\n",
            Charsets.UTF_8,
        )
    }

    @Synchronized
    @Throws(IOException::class)
    fun recordStartupLatency(elapsedMillis: Long) {
        logFile.appendText("startupRequestToCameraStartMs\t$elapsedMillis\n", Charsets.UTF_8)
    }

    @Synchronized
    @Throws(IOException::class)
    fun recordBoundaryCallbackGap(previousSegmentIndex: Int, elapsedMillis: Long) {
        logFile.appendText(
            "segmentBoundaryCallbackGapMs\t$previousSegmentIndex\t$elapsedMillis\n",
            Charsets.UTF_8,
        )
    }

    private fun safe(value: String): String = value.replace('\t', ' ').replace('\n', ' ')
}
