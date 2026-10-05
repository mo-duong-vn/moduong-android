package vn.moduong.app.core.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

internal class Camera2RecorderEngine(context: Context) : RecorderEngine {
    private data class EncoderOption(val size: Size, val codecNames: List<String>)

    private data class EncodedSample(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    ) {
        val isKeyFrame: Boolean
            get() = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
    }

    private class Segment(
        val outputFile: File,
        val onEvent: (RecorderEvent) -> Unit,
    ) {
        var muxer: MediaMuxer? = null
        var trackIndex: Int = -1
        var muxerStarted: Boolean = false
        var firstPresentationTimeUs: Long? = null
        var lastPresentationTimeUs: Long? = null
        var sampleCount: Long = 0L
        var startedEventSent: Boolean = false
    }

    private val appContext = context.applicationContext
    private val handlerThread = HandlerThread("moduong-camera2-recorder").apply { start() }
    private val engineHandler = Handler(handlerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bindRequested = false
    private var readyCompleted = false
    private var readySucceeded = false
    private var readyCallback: ((Result<RecorderProfile>) -> Unit)? = null
    private var unbindRequested = false
    private var unbound = false

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraOpenPending = false
    private var captureSession: CameraCaptureSession? = null
    private var captureSessionPending = false
    private var recordRequest: CaptureRequest? = null
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var encoderStarted = false
    private var repeatingRequestStarted = false
    private var outputFormat: MediaFormat? = null
    private var profile: RecorderProfile? = null
    private var orientationHintDegrees = 0
    private var selectedSize: Size? = null

    private var activeSegment: Segment? = null
    private var cutPending = false
    private var bufferingAfterCut = false
    private var lastEncoderPresentationTimeUs: Long? = null
    private val cutSamples = mutableListOf<EncodedSample>()
    private val samplesBeforeOutputFormat = mutableListOf<EncodedSample>()
    private var cutTimeout: Runnable? = null

    override fun bind(onReady: (Result<RecorderProfile>) -> Unit) {
        if (!engineHandler.post {
                safely { bindOnEngineThread(onReady) }
            }
        ) {
            mainHandler.post {
                onReady(Result.failure(IllegalStateException("Camera2 engine is unavailable")))
            }
        }
    }

    override fun startSegment(outputFile: File, onEvent: (RecorderEvent) -> Unit) {
        if (!engineHandler.post {
                safely { startSegmentOnEngineThread(outputFile, onEvent) }
            }
        ) {
            emitFinalized(outputFile, onEvent, IllegalStateException("Camera2 engine is unavailable"))
        }
    }

    override fun stopCurrentSegment() {
        engineHandler.post {
            safely { requestSegmentCut() }
        }
    }

    override fun unbind() {
        engineHandler.post {
            safely { unbindOnEngineThread() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bindOnEngineThread(onReady: (Result<RecorderProfile>) -> Unit) {
        check(!bindRequested) { "Camera2 engine is already bound" }
        bindRequested = true
        readyCallback = onReady

        val manager = appContext.getSystemService(CameraManager::class.java)
            ?: error("Camera service is unavailable")
        cameraManager = manager
        val backCameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: error("No back camera is available")

        val characteristics = manager.getCameraCharacteristics(backCameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Back camera has no stream configuration map")
        orientationHintDegrees = orientationHint(
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
        )

        val options = encoderOptions(streamMap)
        configureEncoder(options)
        val actualSize = checkNotNull(selectedSize)
        profile = RecorderProfile(
            requestedQuality = "Camera2 ${actualSize.width}x${actualSize.height}@30 AVC continuous",
            supportedQualities = options.map { "${it.size.width}x${it.size.height}" },
        )

        cameraOpenPending = true
        manager.openCamera(backCameraId, cameraStateCallback(), engineHandler)
    }

    private fun encoderOptions(streamMap: StreamConfigurationMap): List<EncoderOption> {
        val mediaCodecSizes = runCatching {
            streamMap.getOutputSizes(MediaCodec::class.java)?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        val cameraSizes = mediaCodecSizes.ifEmpty {
            runCatching {
                streamMap.getOutputSizes(MediaRecorder::class.java)?.toSet().orEmpty()
            }.getOrDefault(emptySet())
        }
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val options = REQUESTED_SIZES.mapNotNull { size ->
            if (size !in cameraSizes) return@mapNotNull null
            val names = codecInfos.mapNotNull { codecInfo ->
                if (!codecInfo.isEncoder) return@mapNotNull null
                val avcType = codecInfo.supportedTypes.firstOrNull {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                } ?: return@mapNotNull null
                val capabilities = runCatching { codecInfo.getCapabilitiesForType(avcType) }
                    .getOrNull() ?: return@mapNotNull null
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in capabilities.colorFormats) {
                    return@mapNotNull null
                }
                val videoCapabilities = capabilities.videoCapabilities ?: return@mapNotNull null
                if (!videoCapabilities.bitrateRange.contains(VIDEO_BIT_RATE)) return@mapNotNull null
                val supportsSizeAndRate = runCatching {
                    videoCapabilities.areSizeAndRateSupported(size.width, size.height, FRAME_RATE.toDouble())
                }.getOrDefault(false)
                codecInfo.name.takeIf { supportsSizeAndRate }
            }
            EncoderOption(size, names).takeIf { names.isNotEmpty() }
        }
        check(options.isNotEmpty()) { "No supported AVC encoder size was found" }
        return options
    }

    private fun configureEncoder(options: List<EncoderOption>) {
        var lastFailure: Exception? = null
        for (option in options) {
            for (codecName in option.codecNames) {
                var candidate: MediaCodec? = null
                try {
                    candidate = MediaCodec.createByCodecName(codecName)
                    encoder = candidate
                    candidate.setCallback(codecCallback(candidate), engineHandler)
                    candidate.configure(videoFormat(option.size), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoderInputSurface = candidate.createInputSurface()
                    selectedSize = option.size
                    return
                } catch (error: Exception) {
                    lastFailure = error
                    if (encoder === candidate) encoder = null
                    runCatching { candidate?.release() }
                }
            }
        }
        throw lastFailure ?: IllegalStateException("Could not configure an AVC encoder")
    }

    private fun videoFormat(size: Size): MediaFormat = MediaFormat.createVideoFormat(
        MediaFormat.MIMETYPE_VIDEO_AVC,
        size.width,
        size.height,
    ).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
    }

    private fun cameraStateCallback(): CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            safely {
                cameraOpenPending = false
                if (unbound || unbindRequested) {
                    camera.close()
                    if (unbindRequested) shutdownEngine()
                    return@safely
                }
                cameraDevice = camera
                val surface = checkNotNull(encoderInputSurface)
                captureSessionPending = true
                try {
                    camera.createCaptureSession(
                        listOf(surface),
                        captureSessionStateCallback(camera),
                        engineHandler,
                    )
                } catch (error: Exception) {
                    captureSessionPending = false
                    throw error
                }
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenPending = false
            camera.close()
            if (cameraDevice === camera) cameraDevice = null
            if (unbindRequested) {
                shutdownEngine()
                return
            }
            handleFatal(IllegalStateException("Back camera disconnected"))
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenPending = false
            camera.close()
            if (cameraDevice === camera) cameraDevice = null
            if (unbindRequested) {
                shutdownEngine()
                return
            }
            handleFatal(IllegalStateException("Back camera error $error"))
        }
    }

    private fun captureSessionStateCallback(camera: CameraDevice): CameraCaptureSession.StateCallback =
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                safely {
                    captureSessionPending = false
                    if (unbound || unbindRequested) {
                        session.close()
                        if (unbindRequested) shutdownEngine()
                        return@safely
                    }
                    captureSession = session
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    builder.addTarget(checkNotNull(encoderInputSurface))
                    val cameraId = camera.id
                    val ranges = cameraManager
                        ?.getCameraCharacteristics(cameraId)
                        ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        .orEmpty()
                    if (ranges.any { it.lower == FRAME_RATE && it.upper == FRAME_RATE }) {
                        builder.set(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            Range(FRAME_RATE, FRAME_RATE),
                        )
                    }
                    recordRequest = builder.build()
                    completeReady(Result.success(checkNotNull(profile)))
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                captureSessionPending = false
                session.close()
                if (unbindRequested) {
                    shutdownEngine()
                    return
                }
                handleFatal(IllegalStateException("Could not configure the camera capture session"))
            }
        }

    private fun codecCallback(callbackCodec: MediaCodec): MediaCodec.Callback =
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (codec !== encoder || unbound) {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    return
                }
                var sample: EncodedSample? = null
                var failure: Exception? = null
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val output = codec.getOutputBuffer(index)
                            ?: throw IOException("Encoder returned an empty output buffer")
                        val data = ByteArray(info.size)
                        output.duplicate().apply {
                            position(info.offset)
                            limit(info.offset + info.size)
                            get(data)
                        }
                        sample = EncodedSample(data, info.presentationTimeUs, info.flags)
                    }
                } catch (error: Exception) {
                    failure = error
                }
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (error: Exception) {
                    if (failure == null) failure = error
                }
                val receivedSample = sample
                if (failure != null) {
                    handleFatal(failure)
                } else if (receivedSample != null) {
                    safely { processEncodedSample(receivedSample) }
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (codec !== callbackCodec || codec !== encoder || unbound) return
                safely {
                    check(outputFormat == null) { "Encoder output format changed unexpectedly" }
                    outputFormat = format
                    activeSegment?.let(::startMuxerIfReady)
                    if (samplesBeforeOutputFormat.isNotEmpty()) {
                        val pending = samplesBeforeOutputFormat.toList()
                        samplesBeforeOutputFormat.clear()
                        pending.forEach(::processEncodedSample)
                    }
                }
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                if (codec !== callbackCodec || codec !== encoder || unbound) return
                handleFatal(error)
            }
        }

    private fun startSegmentOnEngineThread(outputFile: File, onEvent: (RecorderEvent) -> Unit) {
        if (unbound || unbindRequested) {
            emitFinalized(outputFile, onEvent, IllegalStateException("Camera2 engine is unbound"))
            return
        }
        if (!readySucceeded || captureSession == null || encoder == null) {
            val error = IllegalStateException("Camera2 engine is not ready")
            emitFinalized(outputFile, onEvent, error)
            handleFatal(error)
            return
        }
        if (activeSegment != null) {
            val error = IllegalStateException("A video segment is already active")
            emitFinalized(outputFile, onEvent, error)
            handleFatal(error)
            return
        }

        val nextSegment = Segment(outputFile, onEvent)
        activeSegment = nextSegment
        try {
            nextSegment.muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            ).also { it.setOrientationHint(orientationHintDegrees) }
            startMuxerIfReady(nextSegment)

            if (bufferingAfterCut) {
                check(cutSamples.isNotEmpty()) { "No boundary samples are available for the next segment" }
                cancelCutTimeout()
                val buffered = cutSamples.toList()
                cutSamples.clear()
                bufferingAfterCut = false
                buffered.forEach(::writeSampleToActiveSegment)
            } else {
                check(!encoderStarted) { "Encoder is running without a pending segment cut" }
                encoder?.start()
                encoderStarted = true
                val request = checkNotNull(recordRequest)
                captureSession?.setRepeatingRequest(request, null, engineHandler)
                repeatingRequestStarted = true
            }
        } catch (error: Exception) {
            handleFatal(error)
        }
    }

    private fun startMuxerIfReady(segment: Segment) {
        if (segment.muxerStarted) return
        val format = outputFormat ?: return
        val muxer = checkNotNull(segment.muxer)
        segment.trackIndex = muxer.addTrack(format)
        muxer.start()
        segment.muxerStarted = true
    }

    private fun processEncodedSample(sample: EncodedSample) {
        if (outputFormat == null) {
            samplesBeforeOutputFormat += sample
            return
        }
        val previousPtsUs = lastEncoderPresentationTimeUs
        check(previousPtsUs == null || sample.presentationTimeUs >= previousPtsUs) {
            "Encoder output timestamps are not chronological"
        }
        lastEncoderPresentationTimeUs = sample.presentationTimeUs
        val segment = activeSegment
        if (segment == null) {
            if (bufferingAfterCut) cutSamples += sample
            return
        }
        if (cutPending && sample.isKeyFrame) {
            val finalized = finalizeActiveSegment()
            if (!finalized) {
                handleFatal(IllegalStateException("Could not finalize the segment at its keyframe boundary"))
                return
            }
            cutPending = false
            bufferingAfterCut = true
            // The cut keyframe starts the next file, so it is buffered once and never duplicated.
            cutSamples.clear()
            cutSamples += sample
            scheduleCutTimeout()
            return
        }
        if (segment.firstPresentationTimeUs == null && !sample.isKeyFrame) return
        writeSampleToActiveSegment(sample)
    }

    private fun writeSampleToActiveSegment(sample: EncodedSample) {
        val segment = checkNotNull(activeSegment)
        check(segment.muxerStarted) { "Muxer is not ready to write samples" }
        val muxer = checkNotNull(segment.muxer)
        val firstPtsUs = segment.firstPresentationTimeUs ?: sample.presentationTimeUs
        val sampleInfo = MediaCodec.BufferInfo().apply {
            set(0, sample.data.size, sample.presentationTimeUs - firstPtsUs, sample.flags)
        }
        muxer.writeSampleData(segment.trackIndex, ByteBuffer.wrap(sample.data), sampleInfo)
        if (segment.firstPresentationTimeUs == null) segment.firstPresentationTimeUs = firstPtsUs
        segment.lastPresentationTimeUs = sample.presentationTimeUs
        segment.sampleCount++
        if (!segment.startedEventSent) {
            segment.startedEventSent = true
            postEvent(segment.onEvent, RecorderEvent.Started(SystemClock.elapsedRealtimeNanos()))
        }
    }

    private fun requestSegmentCut() {
        val segment = activeSegment ?: return
        if (cutPending) return
        cutPending = true
        val parameters = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
        checkNotNull(encoder).setParameters(parameters)
        check(segment.muxerStarted || outputFormat == null) { "Active segment muxer is unavailable" }
    }

    private fun finalizeActiveSegment(error: Throwable? = null): Boolean {
        val segment = activeSegment ?: return true
        activeSegment = null
        cutPending = false

        var failure = error
        val muxer = segment.muxer
        if (failure == null && (segment.sampleCount == 0L || !segment.muxerStarted)) {
            failure = IOException("Segment has no written video samples")
        }
        if (muxer != null && segment.muxerStarted) {
            try {
                muxer.stop()
            } catch (stopError: Exception) {
                if (failure == null) failure = stopError
            }
        }
        if (muxer != null) {
            try {
                muxer.release()
            } catch (releaseError: Exception) {
                if (failure == null) failure = releaseError
            }
        }

        emitFinalized(
            segment.outputFile,
            segment.onEvent,
            failure,
            firstAbsPtsUs = segment.firstPresentationTimeUs ?: 0L,
            lastAbsPtsUs = segment.lastPresentationTimeUs ?: 0L,
            samples = segment.sampleCount,
        )
        return failure == null
    }

    private fun emitFinalized(
        outputFile: File,
        onEvent: (RecorderEvent) -> Unit,
        error: Throwable?,
        firstAbsPtsUs: Long = 0L,
        lastAbsPtsUs: Long = 0L,
        samples: Long = 0L,
    ) {
        Log.i(
            TAG,
            "SEGMENT_PTS file=${outputFile.name} firstAbsPtsUs=$firstAbsPtsUs " +
                "lastAbsPtsUs=$lastAbsPtsUs samples=$samples",
        )
        postEvent(
            onEvent,
            RecorderEvent.Finalized(
                outputFile = outputFile,
                errorCode = if (error == null) ERROR_NONE else ERROR_FAILURE,
                errorMessage = error?.message ?: error?.toString(),
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            ),
        )
    }

    private fun scheduleCutTimeout() {
        cancelCutTimeout()
        val timeout = Runnable {
            if (bufferingAfterCut && activeSegment == null) {
                cutSamples.clear()
                bufferingAfterCut = false
                shutdownEngine()
            }
        }
        cutTimeout = timeout
        engineHandler.postDelayed(timeout, CUT_START_TIMEOUT_MILLIS)
    }

    private fun cancelCutTimeout() {
        cutTimeout?.let(engineHandler::removeCallbacks)
        cutTimeout = null
    }

    private fun unbindOnEngineThread() {
        if (unbound || unbindRequested) return
        unbindRequested = true
        if (!readyCompleted) {
            completeReady(Result.failure(IllegalStateException("Camera2 engine was unbound before ready")))
        }
        finalizeActiveSegment()
        cutSamples.clear()
        samplesBeforeOutputFormat.clear()
        bufferingAfterCut = false
        if (!cameraOpenPending && !captureSessionPending) shutdownEngine()
    }

    private fun handleFatal(error: Throwable) {
        if (unbound) return
        if (!readyCompleted) completeReady(Result.failure(error))
        finalizeActiveSegment(error)
        cutSamples.clear()
        samplesBeforeOutputFormat.clear()
        bufferingAfterCut = false
        shutdownEngine()
    }

    private fun shutdownEngine() {
        if (unbound) return
        unbound = true
        cancelCutTimeout()
        if (repeatingRequestStarted) {
            runCatching { captureSession?.stopRepeating() }
            repeatingRequestStarted = false
        }
        if (encoderStarted) {
            runCatching { encoder?.stop() }
            encoderStarted = false
        }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { encoderInputSurface?.release() }
        encoderInputSurface = null
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        recordRequest = null
        cameraManager = null
        handlerThread.quitSafely()
    }

    private fun completeReady(result: Result<RecorderProfile>) {
        if (readyCompleted) return
        readyCompleted = true
        readySucceeded = result.isSuccess
        val callback = readyCallback ?: return
        readyCallback = null
        mainHandler.post { callback(result) }
    }

    private fun postEvent(callback: (RecorderEvent) -> Unit, event: RecorderEvent) {
        mainHandler.post { callback(event) }
    }

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            handleFatal(error)
        }
    }

    private fun orientationHint(sensorOrientation: Int): Int {
        val rotation = appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation ?: Surface.ROTATION_0
        val deviceDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation - deviceDegrees + FULL_ROTATION_DEGREES) % FULL_ROTATION_DEGREES
    }

    companion object {
        private const val TAG = "MoDuongCamera2"
        private const val FRAME_RATE = 30
        private const val VIDEO_BIT_RATE = 20_000_000
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val CUT_START_TIMEOUT_MILLIS = 5_000L
        private const val FULL_ROTATION_DEGREES = 360
        private const val ERROR_NONE = 0
        private const val ERROR_FAILURE = 1
        private val REQUESTED_SIZES = listOf(
            Size(1920, 1080),
            Size(1280, 720),
            Size(720, 480),
        )
    }
}
