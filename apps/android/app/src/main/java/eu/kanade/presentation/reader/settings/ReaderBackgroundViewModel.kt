package eu.kanade.presentation.reader.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.data.background.ReaderBackgroundImage
import eu.kanade.tachiyomi.data.background.ReaderBackgroundManager
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.backgroundSettings
import eu.kanade.tachiyomi.ui.reader.setting.setBackgroundSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ReaderBackgroundViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = ReaderBackgroundManager(application)
    private val preferences = Injekt.get<ReaderPreferences>()
    private val _images = MutableStateFlow<List<ReaderBackgroundImage>>(emptyList())
    val images = _images.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()

    fun refresh() = operation { _images.value = manager.images() }

    fun openFolder(open: (Uri) -> Unit) = operation { open(manager.folderUri()) }

    fun importImage(uri: Uri) = operation {
        val image = manager.importImage(uri)
        preferences.setBackgroundSettings(preferences.backgroundSettings().copy(image = image.id))
        _images.value = manager.images()
    }

    fun rename(image: ReaderBackgroundImage, name: String) = operation {
        val renamed = manager.rename(image.id, name)
        val settings = preferences.backgroundSettings()
        if (settings.image == image.id) preferences.setBackgroundSettings(settings.copy(image = renamed.id))
        _images.value = manager.images()
    }

    fun delete(image: ReaderBackgroundImage) = operation {
        manager.delete(image.id)
        val settings = preferences.backgroundSettings()
        if (settings.image == image.id) preferences.setBackgroundSettings(settings.copy(image = ""))
        _images.value = manager.images()
    }

    private fun operation(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _failed.value = false
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _failed.value = true
            } finally {
                _busy.value = false
            }
        }
    }
}
