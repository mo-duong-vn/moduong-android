package vn.moduong.app.core.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RecordingSessionController {
    private val mutableState = MutableStateFlow(RecordingState())

    val state = mutableState.asStateFlow()

    @Synchronized
    fun begin(sessionId: String, startingDetail: String): Boolean {
        if (mutableState.value.phase !in TERMINAL_PHASES) return false
        mutableState.value = RecordingState(
            sessionId = sessionId,
            phase = RecordingPhase.STARTING,
            detail = startingDetail,
        )
        return true
    }

    @Synchronized
    fun isCurrent(sessionId: String): Boolean = mutableState.value.sessionId == sessionId

    @Synchronized
    fun updateStarting(sessionId: String, detail: String): Boolean = update(sessionId) { current ->
        if (current.phase != RecordingPhase.STARTING) return@update null
        current.copy(detail = detail)
    }

    @Synchronized
    fun markRecording(
        sessionId: String,
        startedAtElapsedRealtimeNanos: Long,
        detail: String,
    ): Boolean =
        update(sessionId) { current ->
            if (current.phase != RecordingPhase.STARTING) return@update null
            current.copy(
                phase = RecordingPhase.RECORDING,
                detail = detail,
                startedAtElapsedRealtimeNanos = startedAtElapsedRealtimeNanos,
            )
        }

    @Synchronized
    fun beginFinalizing(sessionId: String, detail: String): Boolean =
        update(sessionId) { current ->
            if (current.phase !in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING)) {
                return@update null
            }
            current.copy(phase = RecordingPhase.FINALIZING, detail = detail)
        }

    @Synchronized
    fun markSegmentVerified(sessionId: String): Boolean = update(sessionId) { current ->
        if (current.phase !in setOf(RecordingPhase.RECORDING, RecordingPhase.FINALIZING)) {
            return@update null
        }
        current.copy(verifiedSegments = current.verifiedSegments + 1)
    }

    @Synchronized
    fun finish(sessionId: String, detail: String, noFileDetail: String): Boolean = update(sessionId) { current ->
        if (current.phase != RecordingPhase.FINALIZING) return@update null
        if (current.verifiedSegments == 0) {
            current.copy(
                phase = RecordingPhase.FAILED,
                detail = noFileDetail,
            )
        } else {
            current.copy(
                phase = RecordingPhase.SAVED,
                detail = detail,
            )
        }
    }

    @Synchronized
    fun fail(sessionId: String, reason: String): Boolean = update(sessionId) { current ->
        if (current.phase in TERMINAL_PHASES) return@update null
        current.copy(
            phase = if (current.verifiedSegments > 0) RecordingPhase.PARTIAL else RecordingPhase.FAILED,
            detail = reason,
        )
    }

    private inline fun update(
        sessionId: String,
        transform: (RecordingState) -> RecordingState?,
    ): Boolean {
        val current = mutableState.value
        if (current.sessionId != sessionId) return false
        val next = transform(current) ?: return false
        mutableState.value = next
        return true
    }

    private companion object {
        val TERMINAL_PHASES = setOf(
            RecordingPhase.IDLE,
            RecordingPhase.SAVED,
            RecordingPhase.PARTIAL,
            RecordingPhase.FAILED,
        )
    }
}
