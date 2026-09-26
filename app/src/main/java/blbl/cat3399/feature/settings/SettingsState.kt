package blbl.cat3399.feature.settings

class SettingsState {
    var currentSectionIndex: Int = -1

    var lastFocusedLeftIndex: Int = 0
    var pendingRestoreRightId: SettingId? = null
    var pendingRestoreLeftIndex: Int? = null
    var pendingRestoreBack: Boolean = false
    var focusRequestToken: Int = 0
    var sectionRenderToken: Int = 0
    private val lastFocusedRightIds = mutableMapOf<Int, SettingId>()

    var cacheSizeBytes: Long? = null

    fun rememberFocusedRightId(id: SettingId) {
        val sectionIndex = currentSectionIndex
        if (sectionIndex >= 0) {
            lastFocusedRightIds[sectionIndex] = id
        }
    }

    fun lastFocusedRightIdForCurrentSection(): SettingId? {
        return lastFocusedRightIds[currentSectionIndex]
    }
}
