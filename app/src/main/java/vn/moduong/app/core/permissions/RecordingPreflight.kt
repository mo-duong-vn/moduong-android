package vn.moduong.app.core.permissions

object RecordingPreflight {
    const val CAMERA = "android.permission.CAMERA"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    sealed interface Decision {
        data class Start(val notificationsDenied: Boolean) : Decision
        data object CameraDenied : Decision
    }

    fun permissionsToRequest(sdk: Int, isGranted: (String) -> Boolean): List<String> {
        val required = mutableListOf(CAMERA)
        if (sdk >= 33) {
            required += POST_NOTIFICATIONS
        }
        return required.filterNot(isGranted)
    }

    fun notificationsDenied(sdk: Int, isGranted: (String) -> Boolean): Boolean =
        sdk >= 33 && !isGranted(POST_NOTIFICATIONS)

    fun afterResult(sdk: Int, isGranted: (String) -> Boolean): Decision =
        if (isGranted(CAMERA)) {
            Decision.Start(notificationsDenied(sdk, isGranted))
        } else {
            Decision.CameraDenied
        }
}
