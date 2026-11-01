package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.model.FilterPreset
import eu.kanade.domain.source.model.FilterPresetList
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.model.FilterList
import logcat.LogPriority
import logcat.logcat

class ManageFilterPresets(
    private val preferences: SourcePreferences,
) {
    fun getPresets(sourceId: Long): FilterPresetList {
        val presets = preferences.filterPresets(sourceId).get()
        return presets
    }

    fun savePreset(
        sourceId: Long,
        name: String,
        filters: FilterList,
        setAsDefault: Boolean = false,
    ): Result<Unit> {
        return runCatching {
            val filterState = serializeFilters(filters)
            val currentPresets = preferences.filterPresets(sourceId).get()

            val newPreset = FilterPreset(
                id = System.currentTimeMillis(),
                sourceId = sourceId,
                name = name,
                filterState = filterState,
                isDefault = setAsDefault,
            )

            val updatedPresets = if (setAsDefault) {
                currentPresets.presets.map { it.copy(isDefault = false) } + newPreset
            } else {
                currentPresets.presets + newPreset
            }

            preferences.filterPresets(sourceId).set(
                FilterPresetList(updatedPresets),
            )
            logcat(LogPriority.INFO) { "FilterPresets: Saved preset '$name' for sourceId=$sourceId" }
        }.onFailure { error ->
            logcat(LogPriority.ERROR) { "FilterPresets: Failed to save preset: ${error.message}" }
        }
    }

    fun loadPresetState(sourceId: Long, presetId: Long): String? {
        val preset = getPresets(sourceId).presets.find { it.id == presetId }
        if (preset == null) {
            logcat(LogPriority.WARN) { "FilterPresets: Preset $presetId not found for sourceId=$sourceId" }
        }
        return preset?.filterState
    }

    fun deletePreset(sourceId: Long, presetId: Long) {
        val currentPresets = preferences.filterPresets(sourceId).get()
        val presetToDelete = currentPresets.presets.find { it.id == presetId }
        val updatedPresets = currentPresets.presets.filter { it.id != presetId }
        preferences.filterPresets(sourceId).set(
            FilterPresetList(updatedPresets),
        )
        logcat(LogPriority.INFO) { "FilterPresets: Deleted preset ${presetToDelete?.name ?: presetId}" }
    }

    fun setDefaultPreset(sourceId: Long, presetId: Long?) {
        val currentPresets = preferences.filterPresets(sourceId).get()
        val updatedPresets = currentPresets.presets.map { preset ->
            preset.copy(isDefault = preset.id == presetId)
        }
        preferences.filterPresets(sourceId).set(
            FilterPresetList(updatedPresets),
        )
        val defaultName = updatedPresets.find { it.isDefault }?.name
        logcat(LogPriority.INFO) { "FilterPresets: Set default preset to: $defaultName" }
    }

    fun getDefaultPresetState(sourceId: Long): String? {
        val preset = getPresets(sourceId).presets.find { it.isDefault }
        return preset?.filterState
    }

    fun getAutoApplyEnabled(): Boolean {
        return preferences.autoApplyFilterPresets.get()
    }

    fun setAutoApplyEnabled(enabled: Boolean) {
        preferences.autoApplyFilterPresets.set(enabled)
        logcat(LogPriority.INFO) { "FilterPresets: Auto-apply presets set to $enabled" }
    }

    companion object {
        fun serializeFilters(filters: FilterList): String = FilterPresetState.serialize(filters)

        fun applyPresetState(filters: FilterList, presetState: String) = FilterPresetState.restore(filters, presetState)
    }
}
