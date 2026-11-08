package eu.kanade.tachiyomi.data

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce

@OptIn(FlowPreview::class)
open class BannerProgressStatus internal constructor(
    visibilityDebounceMillis: Long = 1_000L,
) {
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.debounce(visibilityDebounceMillis)

    private val _progress = MutableStateFlow<Float?>(null)
    val progress = _progress.asStateFlow()

    private var activeRuns = 0

    @Synchronized
    fun start() {
        activeRuns++
        if (activeRuns == 1) {
            _progress.value = null
            _isRunning.value = true
        }
    }

    @Synchronized
    fun stop() {
        if (activeRuns == 0) return

        activeRuns--
        if (activeRuns == 0) {
            _isRunning.value = false
        }
    }

    fun updateProgress(current: Int, total: Int) {
        _progress.value = if (total > 0) {
            current.coerceIn(0, total).toFloat() / total
        } else {
            null
        }
    }
}

class LibraryUpdateStatus : BannerProgressStatus()
class BackupRestoreStatus : BannerProgressStatus()
