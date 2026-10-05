package vn.moduong.app.core.recording

enum class RecordingPhase {
    IDLE,
    STARTING,
    RECORDING,
    FINALIZING,
    SAVED,
    PARTIAL,
    FAILED,
}

data class RecordingState(
    val sessionId: String? = null,
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val detail: String = "",
    val verifiedSegments: Int = 0,
    val startedAtElapsedRealtimeNanos: Long? = null,
)
