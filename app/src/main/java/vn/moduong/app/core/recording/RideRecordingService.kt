package vn.moduong.app.core.recording

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.moduong.app.R
import vn.moduong.app.core.camera.Camera2RecorderEngine
import vn.moduong.app.core.camera.CameraXRecorderEngine
import vn.moduong.app.core.camera.MediaProbe
import vn.moduong.app.core.camera.RecorderEngine
import vn.moduong.app.core.camera.RecorderEvent
import vn.moduong.app.core.camera.RecorderProfile
import vn.moduong.app.core.camera.VideoFileProfile
import vn.moduong.app.core.device.DeviceDiagnostics
import vn.moduong.app.core.storage.RecordingSessionFiles
import vn.moduong.app.core.storage.SegmentStore
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

class RideRecordingService : LifecycleService() {
    inner class LocalBinder : Binder() {
        val service: RideRecordingService
            get() = this@RideRecordingService
    }

    private data class ActiveSegment(
        val index: Int,
        val stagingFile: File,
        var startCallbackElapsedRealtimeNanos: Long? = null,
    )

    private data class PendingProbe(
        val index: Int,
        val stagingFile: File,
        val startCallbackElapsedRealtimeNanos: Long,
        val finalizeCallbackElapsedRealtimeNanos: Long,
    )

    private val binder = LocalBinder()
    private val controller = RecordingSessionController()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probeDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "moduong-media-probe")
    }.asCoroutineDispatcher()
    private val segmentStore by lazy { SegmentStore(File(filesDir, "recordings")) }
    private val notification by lazy { RecordingNotification(this) }

    val recordingState = controller.state

    fun stopActiveSession() {
        controller.state.value.sessionId?.let(::requestStop)
    }

    private var engine: RecorderEngine? = null
    private var engineName: String = ENGINE_CAMERAX
    private var activeSession: RecordingSessionFiles? = null
    private var diagnostics: DeviceDiagnostics? = null
    private var activeSegment: ActiveSegment? = null
    private var segmentIndex = 0
    private var pendingProbes = 0
    private var rotationPending = false
    private var stopRequested = false
    private var pendingFailure: String? = null
    private var sessionStartElapsedRealtimeNanos = 0L
    private var sessionStartUtcMillis = 0L
    private var previousFinalizeCallbackElapsedRealtimeNanos = 0L
    private var boundaryRunnable: Runnable? = null

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startSession(
                requestedStartElapsedRealtimeNanos = intent.getLongExtra(EXTRA_REQUESTED_START_ELAPSED_NANOS, 0L),
                requestedEngine = intent.getStringExtra(EXTRA_ENGINE),
            )
            ACTION_STOP -> intent.getStringExtra(EXTRA_SESSION_ID)?.let(::requestStop)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        boundaryRunnable?.let(mainHandler::removeCallbacks)
        engine?.unbind()
        engine = null
        probeDispatcher.close()
        super.onDestroy()
    }

    private fun selectEngineName(requested: String?): String {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable && requested == ENGINE_CAMERA2) ENGINE_CAMERA2 else ENGINE_CAMERAX
    }

    private fun startSession(requestedStartElapsedRealtimeNanos: Long, requestedEngine: String?) {
        val sessionId = UUID.randomUUID().toString()
        if (!controller.begin(sessionId, getString(R.string.detail_initializing))) return
        sessionStartElapsedRealtimeNanos = requestedStartElapsedRealtimeNanos.takeIf { it > 0L }
            ?: SystemClock.elapsedRealtimeNanos()
        sessionStartUtcMillis = System.currentTimeMillis()
        stopRequested = false
        rotationPending = false
        pendingFailure = null
        pendingProbes = 0
        segmentIndex = 0
        activeSegment = null
        previousFinalizeCallbackElapsedRealtimeNanos = 0L

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            controller.fail(sessionId, getString(R.string.permission_camera_denied))
            stopSelf()
            return
        }

        val foregroundNotification = notification.build(
            sessionId = sessionId,
            phase = RecordingPhase.STARTING,
            detail = getString(R.string.detail_initializing),
        )
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this,
                RecordingNotification.NOTIFICATION_ID,
                foregroundNotification,
                foregroundServiceType,
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "Could not start camera foreground service", error)
            controller.fail(sessionId, getString(R.string.detail_camera_unavailable))
            stopSelf()
            return
        }

        val session = runCatching { segmentStore.createSession(sessionId) }.getOrElse { error ->
            Log.e(TAG, "Could not create recording directory", error)
            controller.fail(sessionId, getString(R.string.detail_storage_error))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        activeSession = session
        engineName = selectEngineName(requestedEngine)
        engine = when (engineName) {
            ENGINE_CAMERA2 -> Camera2RecorderEngine(this)
            else -> CameraXRecorderEngine(this, this)
        }
        notification.update(sessionId, RecordingPhase.STARTING, getString(R.string.detail_initializing))
        engine?.bind { result ->
            if (!controller.isCurrent(sessionId)) {
                engine?.unbind()
                return@bind
            }
            result.fold(
                onSuccess = { profile -> onCameraReady(session, profile) },
                onFailure = { error ->
                    Log.e(TAG, "Could not bind back camera", error)
                    controller.fail(sessionId, getString(R.string.detail_camera_unavailable))
                    releaseAfterTerminalState()
                },
            )
        }
    }

    private fun onCameraReady(session: RecordingSessionFiles, profile: RecorderProfile) {
        if (stopRequested) {
            controller.fail(session.id, getString(R.string.detail_no_file))
            releaseAfterTerminalState()
            return
        }
        diagnostics = DeviceDiagnostics(session).also { log ->
            runCatching {
                log.begin(
                    profile = profile,
                    engineName = engineName,
                    startedAtUtcMillis = sessionStartUtcMillis,
                    startedAtElapsedRealtimeNanos = sessionStartElapsedRealtimeNanos,
                )
            }.onFailure { error ->
                Log.e(TAG, "Could not write diagnostic header", error)
                pendingFailure = getString(R.string.detail_storage_error)
                stopRequested = true
            }
        }
        startNextSegment(session)
    }

    private fun startNextSegment(session: RecordingSessionFiles) {
        if (stopRequested || pendingFailure != null || activeSegment != null) {
            finishWhenReady()
            return
        }
        val index = ++segmentIndex
        val stagingFile = segmentStore.stagingFile(session, index)
        activeSegment = ActiveSegment(index, stagingFile)
        controller.updateStarting(session.id, getString(R.string.detail_initializing))
        try {
            engine?.startSegment(stagingFile) { event -> handleRecorderEvent(session, event) }
                ?: error("Recorder engine is unavailable")
        } catch (error: Exception) {
            Log.e(TAG, "Could not start video segment $index", error)
            activeSegment = null
            pendingFailure = getString(R.string.detail_camera_unavailable)
            stopRequested = true
            controller.beginFinalizing(session.id, getString(R.string.detail_finalizing))
            finishWhenReady()
        }
    }

    private fun handleRecorderEvent(session: RecordingSessionFiles, event: RecorderEvent) {
        if (!controller.isCurrent(session.id)) return
        when (event) {
            is RecorderEvent.Started -> onSegmentStarted(session, event.elapsedRealtimeNanos)
            is RecorderEvent.Finalized -> onSegmentFinalized(session, event)
        }
    }

    private fun onSegmentStarted(session: RecordingSessionFiles, elapsedRealtimeNanos: Long) {
        val segment = activeSegment ?: return
        segment.startCallbackElapsedRealtimeNanos = elapsedRealtimeNanos
        if (segment.index == 1) {
            runCatching {
                diagnostics?.recordStartupLatency(
                    ((elapsedRealtimeNanos - sessionStartElapsedRealtimeNanos).coerceAtLeast(0L) / 1_000_000L),
                )
            }.onFailure { error ->
                Log.e(TAG, "Could not record camera startup latency", error)
            }
        }
        if (segment.index > 1 && previousFinalizeCallbackElapsedRealtimeNanos > 0L) {
            val callbackGapMillis = (elapsedRealtimeNanos - previousFinalizeCallbackElapsedRealtimeNanos)
                .coerceAtLeast(0L) / 1_000_000L
            runCatching {
                diagnostics?.recordBoundaryCallbackGap(segment.index - 1, callbackGapMillis)
            }.onFailure { error ->
                Log.e(TAG, "Could not record segment callback boundary", error)
            }
        }
        if (controller.state.value.phase == RecordingPhase.STARTING) {
            controller.markRecording(
                sessionId = session.id,
                startedAtElapsedRealtimeNanos = elapsedRealtimeNanos,
                detail = getString(R.string.detail_recording),
            )
            notification.update(session.id, RecordingPhase.RECORDING, getString(R.string.detail_recording))
        }

        if (stopRequested) {
            controller.beginFinalizing(session.id, getString(R.string.detail_finalizing))
            engine?.stopCurrentSegment()
            return
        }

        boundaryRunnable?.let(mainHandler::removeCallbacks)
        val segmentNumber = segment.index
        boundaryRunnable = Runnable {
            val active = activeSegment
            if (active?.index == segmentNumber && !stopRequested && controller.state.value.phase == RecordingPhase.RECORDING) {
                rotationPending = true
                engine?.stopCurrentSegment()
            }
        }.also { mainHandler.postDelayed(it, SEGMENT_DURATION_MILLIS) }
    }

    private fun onSegmentFinalized(session: RecordingSessionFiles, event: RecorderEvent.Finalized) {
        boundaryRunnable?.let(mainHandler::removeCallbacks)
        boundaryRunnable = null
        val segment = activeSegment ?: return
        activeSegment = null
        previousFinalizeCallbackElapsedRealtimeNanos = event.elapsedRealtimeNanos
        if (event.errorCode != ERROR_NONE) {
            Log.e(TAG, "CameraX finalized segment with error ${event.errorCode}: ${event.errorMessage.orEmpty()}")
            pendingFailure = getString(R.string.detail_recording_error)
            stopRequested = true
            controller.beginFinalizing(session.id, getString(R.string.detail_finalizing))
            finishWhenReady()
            return
        }

        val startElapsed = segment.startCallbackElapsedRealtimeNanos ?: sessionStartElapsedRealtimeNanos
        pendingProbes++
        val pending = PendingProbe(
            index = segment.index,
            stagingFile = event.outputFile,
            startCallbackElapsedRealtimeNanos = startElapsed,
            finalizeCallbackElapsedRealtimeNanos = event.elapsedRealtimeNanos,
        )
        lifecycleScope.launch(probeDispatcher) {
            val result = runCatching { MediaProbe.inspectVideo(pending.stagingFile) }
            withContext(Dispatchers.Main.immediate) {
                onSegmentProbed(session, pending, result)
            }
        }

        val rotate = rotationPending
        rotationPending = false
        if (stopRequested) {
            controller.beginFinalizing(session.id, getString(R.string.detail_finalizing))
            finishWhenReady()
        } else if (rotate) {
            startNextSegment(session)
        }
    }

    private fun onSegmentProbed(
        session: RecordingSessionFiles,
        pending: PendingProbe,
        result: Result<VideoFileProfile>,
    ) {
        pendingProbes = (pendingProbes - 1).coerceAtLeast(0)
        result.fold(
            onSuccess = { profile -> finalizeVerifiedSegment(session, pending, profile) },
            onFailure = { error ->
                Log.e(TAG, "Could not inspect staging video segment ${pending.index}", error)
                pendingFailure = getString(R.string.detail_probe_failed)
                requestStopForFailure(session.id)
            },
        )
        finishWhenReady()
    }

    private fun finalizeVerifiedSegment(
        session: RecordingSessionFiles,
        pending: PendingProbe,
        profile: VideoFileProfile,
    ) {
        val finalizedFile = try {
            segmentStore.finalizeSegment(session, pending.stagingFile)
        } catch (error: IOException) {
            Log.e(TAG, "Could not finalize verified video segment", error)
            pendingFailure = getString(R.string.detail_storage_error)
            requestStopForFailure(session.id)
            return
        }

        try {
            diagnostics?.recordSegment(
                index = pending.index,
                file = finalizedFile,
                profile = profile,
                startCallbackElapsedRealtimeNanos = pending.startCallbackElapsedRealtimeNanos,
                finalizeCallbackElapsedRealtimeNanos = pending.finalizeCallbackElapsedRealtimeNanos,
            )
            controller.markSegmentVerified(session.id)
            Log.i(
                TAG,
                "SEGMENT_PROFILE index=${pending.index} mime=${profile.codecMime} " +
                    "size=${profile.width}x${profile.height} avgFps=${"%.2f".format(java.util.Locale.US, profile.averageFramesPerSecond)} " +
                    "durationUs=${profile.durationUs} ptsUs=${profile.firstSamplePtsUs}..${profile.lastSamplePtsUs} " +
                    "samples=${profile.videoSamples} bytes=${profile.sizeBytes}",
            )
        } catch (error: IOException) {
            Log.e(TAG, "Could not write segment diagnostics", error)
            pendingFailure = getString(R.string.detail_storage_error)
            requestStopForFailure(session.id)
        }
    }

    private fun requestStopForFailure(sessionId: String) {
        stopRequested = true
        controller.beginFinalizing(sessionId, getString(R.string.detail_finalizing))
        if (activeSegment != null && !rotationPending) {
            engine?.stopCurrentSegment()
        }
    }

    private fun requestStop(sessionId: String) {
        if (!controller.isCurrent(sessionId)) return
        when (controller.state.value.phase) {
            RecordingPhase.STARTING -> {
                stopRequested = true
                controller.updateStarting(sessionId, getString(R.string.detail_stop_requested))
                if (activeSegment == null && engine == null) {
                    controller.fail(sessionId, getString(R.string.detail_no_file))
                    releaseAfterTerminalState()
                }
            }

            RecordingPhase.RECORDING -> {
                stopRequested = true
                controller.beginFinalizing(sessionId, getString(R.string.detail_finalizing))
                notification.update(sessionId, RecordingPhase.FINALIZING, getString(R.string.detail_finalizing))
                if (!rotationPending) engine?.stopCurrentSegment()
            }

            RecordingPhase.FINALIZING,
            RecordingPhase.SAVED,
            RecordingPhase.PARTIAL,
            RecordingPhase.FAILED,
            RecordingPhase.IDLE,
            -> Unit
        }
        finishWhenReady()
    }

    private fun finishWhenReady() {
        val session = activeSession ?: return
        if (!stopRequested || activeSegment != null || pendingProbes != 0) return

        val failure = pendingFailure
        if (failure != null) {
            controller.fail(session.id, failure)
        } else {
            controller.finish(
                sessionId = session.id,
                detail = getString(R.string.detail_saved_segments, controller.state.value.verifiedSegments),
                noFileDetail = getString(R.string.detail_no_file),
            )
        }
        releaseAfterTerminalState()
    }

    private fun releaseAfterTerminalState() {
        boundaryRunnable?.let(mainHandler::removeCallbacks)
        boundaryRunnable = null
        engine?.unbind()
        engine = null
        activeSegment = null
        val current = controller.state.value
        current.sessionId?.let { sessionId ->
            notification.update(sessionId, current.phase, current.detail)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "vn.moduong.app.action.START_RECORDING"
        const val ACTION_STOP = "vn.moduong.app.action.STOP_RECORDING"
        const val EXTRA_SESSION_ID = "vn.moduong.app.extra.SESSION_ID"
        const val EXTRA_REQUESTED_START_ELAPSED_NANOS = "vn.moduong.app.extra.REQUESTED_START_ELAPSED_NANOS"
        const val EXTRA_ENGINE = "vn.moduong.app.extra.ENGINE"
        const val ENGINE_CAMERAX = "camerax"
        const val ENGINE_CAMERA2 = "camera2"

        private const val TAG = "MoDuongDiagnostics"
        private const val SEGMENT_DURATION_MILLIS = 30_000L
        private const val ERROR_NONE = 0
    }
}
