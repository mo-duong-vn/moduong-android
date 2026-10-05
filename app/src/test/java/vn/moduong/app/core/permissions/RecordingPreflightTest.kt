package vn.moduong.app.core.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecordingPreflightTest {
    @Test
    fun allGrantedStartsWithoutRequest() {
        assertEquals(emptyList<String>(), RecordingPreflight.permissionsToRequest(33) { true })
        assertEquals(RecordingPreflight.Decision.Start(false), RecordingPreflight.afterResult(33) { true })
    }

    @Test
    fun missingCameraIsRequested() {
        assertEquals(
            listOf(RecordingPreflight.CAMERA),
            RecordingPreflight.permissionsToRequest(33) { it != RecordingPreflight.CAMERA },
        )
    }

    @Test
    fun missingNotificationsAreRequestedOnApi33AndAbove() {
        for (sdk in listOf(33, 36)) {
            assertEquals(
                listOf(RecordingPreflight.POST_NOTIFICATIONS),
                RecordingPreflight.permissionsToRequest(sdk) { it == RecordingPreflight.CAMERA },
            )
        }
    }

    @Test
    fun bothMissingPermissionsAreRequestedInOrder() {
        assertEquals(
            listOf(RecordingPreflight.CAMERA, RecordingPreflight.POST_NOTIFICATIONS),
            RecordingPreflight.permissionsToRequest(33) { false },
        )
    }

    @Test
    fun apiBelow33NeverChecksOrRequestsNotifications() {
        for (sdk in listOf(29, 32)) {
            val checked = mutableListOf<String>()
            assertEquals(
                listOf(RecordingPreflight.CAMERA),
                RecordingPreflight.permissionsToRequest(sdk) {
                    checked += it
                    false
                },
            )
            assertEquals(listOf(RecordingPreflight.CAMERA), checked)
            assertEquals(
                emptyList<String>(),
                RecordingPreflight.permissionsToRequest(sdk) { it == RecordingPreflight.CAMERA },
            )
            assertEquals(
                RecordingPreflight.Decision.Start(false),
                RecordingPreflight.afterResult(sdk) { it == RecordingPreflight.CAMERA },
            )
            assertFalse(RecordingPreflight.notificationsDenied(sdk) { error("Unexpected permission check") })
        }
    }

    @Test
    fun cameraDeniedAfterResultPreventsStart() {
        for (sdk in listOf(32, 33)) {
            assertEquals(RecordingPreflight.Decision.CameraDenied, RecordingPreflight.afterResult(sdk) { false })
            assertEquals(
                RecordingPreflight.Decision.CameraDenied,
                RecordingPreflight.afterResult(sdk) { it == RecordingPreflight.POST_NOTIFICATIONS },
            )
        }
    }

    @Test
    fun notificationsDeniedWithCameraGrantedStillStarts() {
        assertEquals(
            RecordingPreflight.Decision.Start(true),
            RecordingPreflight.afterResult(33) { it == RecordingPreflight.CAMERA },
        )
    }
}
