package vn.moduong.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import vn.moduong.app.core.permissions.RecordingPreflight
import vn.moduong.app.core.recording.RecordingPhase
import vn.moduong.app.core.recording.RecordingState
import vn.moduong.app.core.recording.RideRecordingService
import vn.moduong.app.feature.home.HomeScreen
import vn.moduong.app.feature.home.HomeSessionUiState

class MainActivity : ComponentActivity() {
    private val screenState = MutableStateFlow(
        HomeSessionUiState(
            status = "Sẵn sàng quay",
            detail = "Camera chỉ hoạt động sau khi bạn bắt đầu quay.",
            isRecording = false,
            isBusy = false,
        ),
    )
    private var notificationsDenied = false

    private var recordingService: RideRecordingService? = null
    private var recordingStateJob: Job? = null
    private var bound = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val decision = RecordingPreflight.afterResult(Build.VERSION.SDK_INT, ::isPermissionGranted)
        when (decision) {
            is RecordingPreflight.Decision.Start -> {
                notificationsDenied = decision.notificationsDenied
                startRecordingService()
            }
            RecordingPreflight.Decision.CameraDenied -> {
                notificationsDenied = RecordingPreflight.notificationsDenied(Build.VERSION.SDK_INT, ::isPermissionGranted)
                screenState.value = HomeSessionUiState(
                    status = getString(R.string.state_failed),
                    detail = getString(R.string.permission_camera_denied),
                    isRecording = false,
                    isBusy = false,
                )
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? RideRecordingService.LocalBinder ?: return
            recordingService = localBinder.service
            recordingStateJob?.cancel()
            recordingStateJob = lifecycleScope.launch {
                localBinder.service.recordingState.collect { state ->
                    screenState.value = state.toHomeUiState()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingStateJob?.cancel()
            recordingStateJob = null
            recordingService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenState.value = HomeSessionUiState(
            status = getString(R.string.state_idle),
            detail = getString(R.string.detail_ready),
            isRecording = false,
            isBusy = false,
        )
        setContent {
            val state by screenState.collectAsState()
            HomeScreen(
                state = state,
                onStart = { requestPermissionsAndStart() },
                onStop = { recordingService?.stopActiveSession() },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, RideRecordingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        recordingStateJob?.cancel()
        recordingStateJob = null
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        recordingService = null
        super.onStop()
    }

    private fun requestPermissionsAndStart() {
        val missing = RecordingPreflight.permissionsToRequest(Build.VERSION.SDK_INT, ::isPermissionGranted)
        if (missing.isEmpty()) {
            startRecordingService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startRecordingService() {
        screenState.value = HomeSessionUiState(
            status = getString(R.string.state_starting),
            detail = getString(R.string.detail_initializing),
            isRecording = false,
            isBusy = true,
        )
        val startIntent = Intent(this, RideRecordingService::class.java)
            .setAction(RideRecordingService.ACTION_START)
            .putExtra(
                RideRecordingService.EXTRA_REQUESTED_START_ELAPSED_NANOS,
                SystemClock.elapsedRealtimeNanos(),
            )
            .apply {
                intent.getStringExtra(RideRecordingService.EXTRA_ENGINE)?.let {
                    putExtra(RideRecordingService.EXTRA_ENGINE, it)
                }
            }
        try {
            ContextCompat.startForegroundService(this, startIntent)
        } catch (error: SecurityException) {
            screenState.value = HomeSessionUiState(
                status = getString(R.string.state_failed),
                detail = getString(R.string.detail_camera_unavailable),
                isRecording = false,
                isBusy = false,
            )
        }
    }

    private fun RecordingState.toHomeUiState(): HomeSessionUiState {
        val (status, busy, recording) = when (phase) {
            RecordingPhase.IDLE -> Triple(R.string.state_idle, false, false)
            RecordingPhase.STARTING -> Triple(R.string.state_starting, true, false)
            RecordingPhase.RECORDING -> Triple(R.string.state_recording, false, true)
            RecordingPhase.FINALIZING -> Triple(R.string.state_finalizing, true, false)
            RecordingPhase.SAVED -> Triple(R.string.state_saved, false, false)
            RecordingPhase.PARTIAL -> Triple(R.string.state_partial, false, false)
            RecordingPhase.FAILED -> Triple(R.string.state_failed, false, false)
        }
        val stateDetail = when {
            this.detail.isNotBlank() -> this.detail
            phase == RecordingPhase.IDLE -> getString(R.string.detail_ready)
            else -> getString(status)
        }
        val detail = if (notificationsDenied && phase in setOf(
                RecordingPhase.STARTING,
                RecordingPhase.RECORDING,
                RecordingPhase.FINALIZING,
            )
        ) {
            "$stateDetail\n\n${getString(R.string.detail_notification_permission)}"
        } else {
            stateDetail
        }
        return HomeSessionUiState(
            status = getString(status),
            detail = detail,
            isRecording = recording,
            isBusy = busy,
        )
    }

}
