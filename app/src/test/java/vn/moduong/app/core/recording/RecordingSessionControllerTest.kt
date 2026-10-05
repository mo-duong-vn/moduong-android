package vn.moduong.app.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionControllerTest {
    @Test
    fun duplicateStartStopAndFinalizeAreRejected() {
        val controller = RecordingSessionController()
        val sessionId = "session-one"

        assertTrue(controller.begin(sessionId, "Starting"))
        assertFalse(controller.begin(sessionId, "Duplicate start"))
        assertTrue(controller.markRecording(sessionId, 123L, "Recording"))
        assertFalse(controller.markRecording(sessionId, 456L, "Duplicate recording callback"))

        assertTrue(controller.beginFinalizing(sessionId, "Stopping"))
        assertFalse(controller.beginFinalizing(sessionId, "Duplicate stop"))
        assertTrue(controller.markSegmentVerified(sessionId))
        assertTrue(controller.finish(sessionId, "Saved", "No file"))
        assertFalse(controller.finish(sessionId, "Duplicate finalize", "No file"))
        assertFalse(controller.fail(sessionId, "Late failure"))

        assertEquals(RecordingPhase.SAVED, controller.state.value.phase)
        assertEquals(1, controller.state.value.verifiedSegments)
        assertEquals(123L, controller.state.value.startedAtElapsedRealtimeNanos)
    }

    @Test
    fun failureKeepsPreviouslyVerifiedSegmentsAsPartialRecording() {
        val controller = RecordingSessionController()
        val sessionId = "session-with-segment"

        assertTrue(controller.begin(sessionId, "Starting"))
        assertTrue(controller.markRecording(sessionId, 100L, "Recording"))
        assertTrue(controller.markSegmentVerified(sessionId))
        assertTrue(controller.fail(sessionId, "Camera disconnected"))

        assertEquals(RecordingPhase.PARTIAL, controller.state.value.phase)
        assertEquals(1, controller.state.value.verifiedSegments)
        assertEquals("Camera disconnected", controller.state.value.detail)
        assertFalse(controller.markSegmentVerified(sessionId))
    }

    @Test
    fun callbacksFromPreviousSessionCannotChangeCurrentSession() {
        val controller = RecordingSessionController()
        val oldSessionId = "old-session"
        val currentSessionId = "current-session"

        assertTrue(controller.begin(oldSessionId, "Old start"))
        assertTrue(controller.fail(oldSessionId, "Old session ended"))
        assertTrue(controller.begin(currentSessionId, "Current start"))
        val currentState = controller.state.value

        assertFalse(controller.isCurrent(oldSessionId))
        assertFalse(controller.updateStarting(oldSessionId, "Stale start"))
        assertFalse(controller.markRecording(oldSessionId, 999L, "Stale recording"))
        assertFalse(controller.beginFinalizing(oldSessionId, "Stale stop"))
        assertFalse(controller.markSegmentVerified(oldSessionId))
        assertFalse(controller.finish(oldSessionId, "Stale saved", "Stale empty"))
        assertFalse(controller.fail(oldSessionId, "Stale error"))

        assertEquals(currentState, controller.state.value)
        assertEquals(currentSessionId, controller.state.value.sessionId)
        assertNull(controller.state.value.startedAtElapsedRealtimeNanos)
    }
}
