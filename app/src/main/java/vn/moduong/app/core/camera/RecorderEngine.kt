package vn.moduong.app.core.camera

import java.io.File

data class RecorderProfile(
    val requestedQuality: String,
    val supportedQualities: List<String>,
)

sealed interface RecorderEvent {
    data class Started(val elapsedRealtimeNanos: Long) : RecorderEvent

    data class Finalized(
        val outputFile: File,
        val errorCode: Int,
        val errorMessage: String?,
        val elapsedRealtimeNanos: Long,
    ) : RecorderEvent
}

interface RecorderEngine {
    fun bind(onReady: (Result<RecorderProfile>) -> Unit)

    fun startSegment(outputFile: File, onEvent: (RecorderEvent) -> Unit)

    fun stopCurrentSegment()

    fun unbind()
}
