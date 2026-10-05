package vn.moduong.app.core.camera

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor

internal class CameraXRecorderEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : RecorderEngine {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: androidx.camera.video.Recording? = null

    override fun bind(onReady: (Result<RecorderProfile>) -> Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            runCatching {
                val cameraProvider = providerFuture.get()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                check(cameraProvider.hasCamera(selector)) { "Không tìm thấy camera sau có thể mở." }
                val cameraInfo = cameraProvider.availableCameraInfos.firstOrNull {
                    it.lensFacing == CameraSelector.LENS_FACING_BACK
                } ?: error("Không đọc được thông tin camera sau.")
                val supported = QualitySelector.getSupportedQualities(cameraInfo)
                val selectorQuality = QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(selectorQuality)
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, capture)
                provider = cameraProvider
                videoCapture = capture
                RecorderProfile(
                    requestedQuality = "FHD; FPS sẽ đo từ MP4",
                    supportedQualities = supported.map(::qualityName),
                )
            }.fold(
                onSuccess = { onReady(Result.success(it)) },
                onFailure = { onReady(Result.failure(it)) },
            )
        }, mainExecutor)
    }

    override fun startSegment(outputFile: File, onEvent: (RecorderEvent) -> Unit) {
        check(activeRecording == null) { "A video segment is already active" }
        val capture = checkNotNull(videoCapture) { "Camera is not bound" }
        val options = FileOutputOptions.Builder(outputFile).build()
        activeRecording = capture.output
            .prepareRecording(context, options)
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> onEvent(
                        RecorderEvent.Started(SystemClock.elapsedRealtimeNanos()),
                    )

                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        onEvent(
                            RecorderEvent.Finalized(
                                outputFile = outputFile,
                                errorCode = event.error,
                                errorMessage = event.cause?.message,
                                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                            ),
                        )
                    }

                    else -> Unit
                }
            }
    }

    override fun stopCurrentSegment() {
        activeRecording?.stop()
    }

    override fun unbind() {
        activeRecording?.stop()
        activeRecording = null
        videoCapture?.let { provider?.unbind(it) }
        videoCapture = null
        provider = null
    }

    private fun qualityName(quality: Quality): String = when (quality) {
        Quality.UHD -> "UHD"
        Quality.FHD -> "FHD"
        Quality.HD -> "HD"
        Quality.SD -> "SD"
        else -> quality.toString()
    }
}
