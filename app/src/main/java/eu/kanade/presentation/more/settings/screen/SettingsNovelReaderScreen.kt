package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.reader.settings.NovelFontPickerDialog
import eu.kanade.presentation.reader.settings.NovelTtsEnginePreference
import eu.kanade.presentation.reader.settings.NovelTtsVoicePreference
import eu.kanade.presentation.reader.settings.novelThemes
import eu.kanade.presentation.reader.settings.rememberNovelFontOptions
import eu.kanade.tachiyomi.ui.reader.setting.NovelPageEffect
import eu.kanade.tachiyomi.ui.reader.setting.NovelPageSpread
import eu.kanade.tachiyomi.ui.reader.setting.NovelReadingLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsNovelReaderScreen : SearchableSettings {

    override val supportsReset: Boolean get() = true

    @Composable
    override fun getAdditionalResetPreferences(): List<tachiyomi.core.common.preference.Preference<*>> {
        val readerPref = remember { Injekt.get<ReaderPreferences>() }
        return listOf(
            readerPref.novelFontSize,
            readerPref.novelReadingLayout,
            readerPref.novelPageSpread,
            readerPref.novelPageEffect,
            readerPref.novelPagedSwipeNavigation,
            readerPref.novelAutoPageIntervalSeconds,
            readerPref.novelFontFamily,
            readerPref.novelFontColor,
            readerPref.novelBackgroundColor,
            readerPref.novelLineHeight,
            readerPref.novelAutoScrollSpeed,
            readerPref.novelVolumeKeysScrollDistance,
            readerPref.novelParagraphIndent,
            readerPref.novelParagraphSpacing,
            readerPref.novelMarginLeft,
            readerPref.novelMarginRight,
            readerPref.novelMarginTop,
            readerPref.novelMarginBottom,
            readerPref.novelAutoLoadNextChapterAt,
            readerPref.novelSourceCssPriority,
            readerPref.novelTtsSpeed,
            readerPref.novelTtsPitch,
            readerPref.novelTtsEngine,
            readerPref.novelTtsVoice,
            readerPref.novelTtsTikTokVoice,
            readerPref.novelStatusBarOrder,
        )
    }

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = TDMR.strings.pref_category_novel

    @Composable
    override fun getPreferences(): List<Preference> {
        val readerPref = remember { Injekt.get<ReaderPreferences>() }
        val navigator = LocalNavigator.currentOrThrow

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(TDMR.strings.pref_novel_reader_preview),
                subtitle = stringResource(TDMR.strings.pref_novel_reader_preview_summary),
                onClick = { navigator.push(NovelReaderPreviewScreen()) },
            ),
            getDisplayGroup(readerPref),
            getTextGroup(readerPref, navigator),
            getFormattingGroup(readerPref),
            getNavigationGroup(readerPref),
            getAutoScrollGroup(readerPref),
            getContentGroup(readerPref),
            getStatusBarGroup(readerPref, navigator),
            getTtsGroup(readerPref),
        )
    }

    @Composable
    private fun getStatusBarGroup(
        readerPreferences: ReaderPreferences,
        navigator: Navigator,
    ): Preference.PreferenceGroup {
        val enabled = readerPreferences.novelStatusBarEnabled.collectAsState().value

        val items = buildList {
            add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelStatusBarEnabled,
                    title = stringResource(TDMR.strings.pref_novel_status_bar),
                ),
            )
            if (enabled) {
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = readerPreferences.novelStatusBarPosition,
                        entries = mapOf(
                            "bottom" to stringResource(TDMR.strings.novel_status_bar_position_bottom),
                            "top" to stringResource(TDMR.strings.novel_status_bar_position_top),
                        ),
                        title = stringResource(TDMR.strings.pref_novel_status_bar_position),
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = readerPreferences.novelStatusBarSize,
                        entries = mapOf(
                            "small" to stringResource(TDMR.strings.novel_status_bar_size_small),
                            "medium" to stringResource(TDMR.strings.novel_status_bar_size_medium),
                        ),
                        title = stringResource(TDMR.strings.pref_novel_status_bar_size),
                    ),
                )
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.novelStatusBarShowChapterNumber,
                        title = stringResource(TDMR.strings.pref_novel_status_bar_show_chapter_number),
                    ),
                )
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.novelStatusBarShowChapterTitle,
                        title = stringResource(TDMR.strings.pref_novel_status_bar_show_chapter_title),
                    ),
                )
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.novelStatusBarShowCharging,
                        title = stringResource(TDMR.strings.pref_novel_status_bar_show_charging),
                        subtitle = stringResource(TDMR.strings.pref_novel_status_bar_show_charging_summary),
                    ),
                )
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(TDMR.strings.pref_novel_status_bar_customize),
                        subtitle = stringResource(TDMR.strings.pref_novel_status_bar_customize_summary),
                        onClick = { navigator.push(StatusBarElementsScreen()) },
                    ),
                )
            }
        }

        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_novel_status_bar),
            preferenceItems = items,
        )
    }

    @Composable
    private fun getDisplayGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val readingLayout = readerPreferences.novelReadingLayout.collectAsState().value
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = readerPreferences.novelReadingLayout,
                        entries = mapOf(
                            NovelReadingLayout.SCROLL to stringResource(TDMR.strings.novel_reading_layout_scroll),
                            NovelReadingLayout.PAGED to stringResource(TDMR.strings.novel_reading_layout_paged),
                        ),
                        title = stringResource(TDMR.strings.pref_novel_reading_layout),
                    ),
                )
                if (readingLayout == NovelReadingLayout.PAGED) {
                    add(
                        Preference.PreferenceItem.ListPreference(
                            preference = readerPreferences.novelPageSpread,
                            entries = mapOf(
                                NovelPageSpread.AUTO to stringResource(TDMR.strings.novel_page_spread_auto),
                                NovelPageSpread.SINGLE to stringResource(TDMR.strings.novel_page_spread_single),
                                NovelPageSpread.DOUBLE to stringResource(TDMR.strings.novel_page_spread_double),
                            ),
                            title = stringResource(TDMR.strings.pref_novel_page_spread),
                        ),
                    )
                    add(
                        Preference.PreferenceItem.ListPreference(
                            preference = readerPreferences.novelPageEffect,
                            entries = mapOf(
                                NovelPageEffect.NONE to stringResource(MR.strings.none),
                                NovelPageEffect.HORIZONTAL to
                                    stringResource(TDMR.strings.novel_page_effect_horizontal),
                                NovelPageEffect.SLIDE to stringResource(TDMR.strings.novel_page_effect_slide),
                                NovelPageEffect.CURL to stringResource(TDMR.strings.novel_page_effect_curl),
                            ),
                            title = stringResource(TDMR.strings.pref_novel_page_effect),
                        ),
                    )
                    add(
                        Preference.PreferenceItem.SwitchPreference(
                            preference = readerPreferences.flashOnPageChange,
                            title = stringResource(MR.strings.pref_flash_page),
                        ),
                    )
                }
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = readerPreferences.novelTheme,
                        entries = novelThemes.associate { (label, value) -> value to stringResource(label) },
                        title = stringResource(TDMR.strings.pref_novel_theme),
                    ),
                )
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.fullscreen,
                        title = stringResource(MR.strings.pref_fullscreen),
                    ),
                )
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.novelShowProgressSlider,
                        title = stringResource(TDMR.strings.pref_novel_progress_slider),
                    ),
                )
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.novelKeepScreenOn,
                        title = stringResource(MR.strings.pref_keep_screen_on),
                    ),
                )
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.novelCustomBrightness,
                        title = stringResource(MR.strings.pref_custom_brightness),
                    ),
                )
            },
        )
    }

    @Composable
    private fun getTextGroup(
        readerPreferences: ReaderPreferences,
        navigator: cafe.adriel.voyager.navigator.Navigator,
    ): Preference.PreferenceGroup {
        val fontSize = readerPreferences.novelFontSize.collectAsState().value
        val lineHeight = readerPreferences.novelLineHeight.collectAsState().value

        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.settings_reader_text_group),
            preferenceItems = listOf(
                Preference.PreferenceItem.SliderPreference(
                    value = fontSize,
                    valueRange = 10..40,
                    title = stringResource(TDMR.strings.pref_font_size),
                    valueString = "${fontSize}px",
                    onValueChanged = {
                        readerPreferences.novelFontSize.set(it)
                    },
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(TDMR.strings.pref_font_family),
                ) {
                    val title = stringResource(TDMR.strings.pref_font_family)
                    val selected by readerPreferences.novelFontFamily.collectAsState()
                    val options = rememberNovelFontOptions()
                    val selectedOption = options.find { it.value == selected }
                    var showDialog by remember { mutableStateOf(false) }

                    TextPreferenceWidget(
                        title = title,
                        subtitle = selectedOption?.label ?: selected,
                        onPreferenceClick = { showDialog = true },
                    )
                    if (showDialog) {
                        NovelFontPickerDialog(
                            title = title,
                            options = options,
                            selected = selected,
                            onSelect = {
                                readerPreferences.novelFontFamily.set(it)
                                showDialog = false
                            },
                            onDismissRequest = { showDialog = false },
                        )
                    }
                },
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(TDMR.strings.settings_font_manager_title),
                    subtitle = stringResource(TDMR.strings.settings_reader_font_manager_summary),
                    onClick = { navigator.push(FontManagerScreen()) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (lineHeight * 10).toInt(),
                    valueRange = 10..30,
                    title = stringResource(TDMR.strings.pref_novel_line_height),
                    valueString = "${lineHeight}x",
                    onValueChanged = {
                        readerPreferences.novelLineHeight.set(it / 10f)
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.novelTextAlign,
                    entries = mapOf(
                        "left" to "Left",
                        "center" to "Center",
                        "right" to "Right",
                        "justify" to "Justify",
                    ).toMap(),
                    title = stringResource(TDMR.strings.pref_novel_text_align),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelForceTextLowercase,
                    title = stringResource(TDMR.strings.settings_reader_force_lowercase),
                    subtitle = stringResource(TDMR.strings.settings_reader_force_lowercase_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelUseOriginalFonts,
                    title = stringResource(TDMR.strings.settings_reader_use_original_fonts_title),
                    subtitle = stringResource(TDMR.strings.settings_reader_use_original_fonts_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelBionicReading,
                    title = stringResource(TDMR.strings.pref_novel_bionic_reading),
                ),
            ),
        )
    }

    @Composable
    private fun getNavigationGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val readingLayout = readerPreferences.novelReadingLayout.collectAsState().value
        val volumeKeysScroll = readerPreferences.novelVolumeKeysScroll.collectAsState().value
        val volumeKeysScrollDistance = readerPreferences.novelVolumeKeysScrollDistance.collectAsState().value

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_reader_navigation),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.novelVolumeKeysScroll,
                        title = stringResource(
                            if (readingLayout == NovelReadingLayout.PAGED) {
                                TDMR.strings.pref_novel_volume_keys_page
                            } else {
                                TDMR.strings.pref_novel_volume_keys_scroll
                            },
                        ),
                    ),
                )
                if (volumeKeysScroll && readingLayout == NovelReadingLayout.SCROLL) {
                    add(
                        Preference.PreferenceItem.SliderPreference(
                            value = volumeKeysScrollDistance,
                            valueRange = ReaderPreferences.VolumeKeyScrollDistanceRange,
                            steps = ReaderPreferences.VOLUME_KEY_SCROLL_DISTANCE_SLIDER_STEPS,
                            title = stringResource(TDMR.strings.pref_novel_volume_keys_scroll_distance),
                            valueString = "$volumeKeysScrollDistance%",
                            preference = readerPreferences.novelVolumeKeysScrollDistance,
                            onValueChanged = readerPreferences.novelVolumeKeysScrollDistance::set,
                        ),
                    )
                }
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = if (readingLayout == NovelReadingLayout.PAGED) {
                            readerPreferences.novelPagedSwipeNavigation
                        } else {
                            readerPreferences.novelSwipeNavigation
                        },
                        title = stringResource(
                            if (readingLayout == NovelReadingLayout.PAGED) {
                                TDMR.strings.pref_novel_paged_swipe_navigation
                            } else {
                                TDMR.strings.settings_reader_swipe_navigation_title
                            },
                        ),
                        subtitle = stringResource(
                            if (readingLayout == NovelReadingLayout.PAGED) {
                                TDMR.strings.pref_novel_paged_swipe_navigation_summary
                            } else {
                                TDMR.strings.settings_reader_swipe_navigation_summary
                            },
                        ),
                    ),
                )
            },
        )
    }

    @Composable
    private fun getAutoScrollGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val readingLayout = readerPreferences.novelReadingLayout.collectAsState().value
        val autoScrollSpeed = readerPreferences.novelAutoScrollSpeed.collectAsState().value
        val autoPageInterval = readerPreferences.novelAutoPageIntervalSeconds.collectAsState().value.coerceIn(2, 60)

        return Preference.PreferenceGroup(
            title = stringResource(
                if (readingLayout == NovelReadingLayout.PAGED) {
                    TDMR.strings.pref_novel_auto_page
                } else {
                    TDMR.strings.pref_novel_auto_scroll
                },
            ),
            preferenceItems = if (readingLayout == NovelReadingLayout.PAGED) {
                listOf(
                    Preference.PreferenceItem.SliderPreference(
                        value = autoPageInterval,
                        valueRange = 2..60,
                        title = stringResource(TDMR.strings.pref_novel_auto_page_interval),
                        valueString = stringResource(
                            TDMR.strings.novel_auto_page_interval_seconds,
                            autoPageInterval,
                        ),
                        onValueChanged = readerPreferences.novelAutoPageIntervalSeconds::set,
                    ),
                )
            } else {
                listOf(
                    Preference.PreferenceItem.SliderPreference(
                        value = autoScrollSpeed,
                        valueRange = 2..20,
                        title = stringResource(TDMR.strings.pref_novel_auto_scroll_speed),
                        valueString = "${autoScrollSpeed / 2f}",
                        onValueChanged = readerPreferences.novelAutoScrollSpeed::set,
                    ),
                )
            },
        )
    }

    @Composable
    private fun getFormattingGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val paragraphIndent = readerPreferences.novelParagraphIndent.collectAsState().value
        val paragraphSpacing = readerPreferences.novelParagraphSpacing.collectAsState().value
        val marginLeft = readerPreferences.novelMarginLeft.collectAsState().value
        val marginRight = readerPreferences.novelMarginRight.collectAsState().value
        val marginTop = readerPreferences.novelMarginTop.collectAsState().value
        val marginBottom = readerPreferences.novelMarginBottom.collectAsState().value

        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.settings_reader_formatting_group),
            preferenceItems = listOf(
                Preference.PreferenceItem.SliderPreference(
                    value = (paragraphIndent * 10).toInt(),
                    valueRange = 0..50,
                    title = stringResource(TDMR.strings.pref_novel_paragraph_indent),
                    valueString = "${paragraphIndent}em",
                    onValueChanged = { readerPreferences.novelParagraphIndent.set(it / 10f) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (paragraphSpacing * 10).toInt(),
                    valueRange = 0..30,
                    title = stringResource(TDMR.strings.pref_novel_paragraph_spacing),
                    valueString = "${paragraphSpacing}em",
                    onValueChanged = { readerPreferences.novelParagraphSpacing.set(it / 10f) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = marginLeft,
                    valueRange = 0..100,
                    title = stringResource(TDMR.strings.pref_novel_margin_left),
                    valueString = "${marginLeft}dp",
                    onValueChanged = { readerPreferences.novelMarginLeft.set(it) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = marginRight,
                    valueRange = 0..100,
                    title = stringResource(TDMR.strings.pref_novel_margin_right),
                    valueString = "${marginRight}dp",
                    onValueChanged = { readerPreferences.novelMarginRight.set(it) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = marginTop,
                    valueRange = 0..300,
                    title = stringResource(TDMR.strings.pref_novel_margin_top),
                    valueString = "${marginTop}dp",
                    onValueChanged = { readerPreferences.novelMarginTop.set(it) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = marginBottom,
                    valueRange = 0..300,
                    title = stringResource(TDMR.strings.pref_novel_margin_bottom),
                    valueString = "${marginBottom}dp",
                    onValueChanged = { readerPreferences.novelMarginBottom.set(it) },
                ),
            ),
        )
    }

    @Composable
    private fun getContentGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val autoLoadNextAt = readerPreferences.novelAutoLoadNextChapterAt.collectAsState().value
        val markAsReadThreshold = readerPreferences.novelMarkAsReadThreshold.collectAsState().value

        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.settings_reader_content_group),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelInfiniteScroll,
                    title = stringResource(TDMR.strings.settings_reader_infinite_scroll_title),
                    subtitle = stringResource(TDMR.strings.settings_reader_infinite_scroll_summary),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = autoLoadNextAt,
                    // 0 keeps one chapter ready ahead at all times instead of waiting for a scroll
                    // position, which is what makes a run of short chapters append without stalling.
                    valueRange = 0..100 step 5,
                    title = stringResource(TDMR.strings.settings_reader_auto_load_next_at_title),
                    valueString = if (autoLoadNextAt > 0) {
                        "$autoLoadNextAt%"
                    } else {
                        stringResource(TDMR.strings.pref_novel_auto_load_next_always)
                    },
                    onValueChanged = { readerPreferences.novelAutoLoadNextChapterAt.set(it) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = markAsReadThreshold,
                    valueRange = 50..100,
                    title = stringResource(TDMR.strings.settings_reader_mark_as_read_at_title),
                    valueString = "$markAsReadThreshold%",
                    onValueChanged = { readerPreferences.novelMarkAsReadThreshold.set(it) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelMarkShortChapterAsRead,
                    title = stringResource(TDMR.strings.settings_reader_auto_mark_short_title),
                    subtitle = stringResource(TDMR.strings.settings_reader_auto_mark_short_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelHideChapterTitle,
                    title = stringResource(TDMR.strings.settings_reader_hide_chapter_title),
                    subtitle = stringResource(TDMR.strings.settings_reader_hide_chapter_title_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelBlockMedia,
                    title = stringResource(TDMR.strings.settings_reader_block_media_title),
                    subtitle = stringResource(TDMR.strings.settings_reader_block_media_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelTextSelectable,
                    title = stringResource(TDMR.strings.settings_reader_text_selectable_title),
                    subtitle = stringResource(TDMR.strings.settings_reader_text_selectable_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelSourceCssPriority,
                    title = stringResource(TDMR.strings.settings_reader_source_css_priority_title),
                    subtitle = stringResource(TDMR.strings.settings_reader_source_css_priority_summary),
                ),
            ),
        )
    }

    @Composable
    private fun getTtsGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val ttsSpeed = readerPreferences.novelTtsSpeed.collectAsState().value
        val ttsPitch = readerPreferences.novelTtsPitch.collectAsState().value

        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_novel_tts_section),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelTtsEnabled,
                    title = stringResource(TDMR.strings.pref_novel_tts_enabled),
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(TDMR.strings.pref_novel_tts_engine),
                ) { NovelTtsEnginePreference(readerPreferences) },
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(TDMR.strings.pref_novel_tts_voice),
                ) { NovelTtsVoicePreference(readerPreferences) },
                Preference.PreferenceItem.SliderPreference(
                    value = (ttsSpeed * 10).toInt(),
                    valueRange = 1..30,
                    title = stringResource(TDMR.strings.settings_reader_tts_speed_title),
                    valueString = "${ttsSpeed}x",
                    onValueChanged = { readerPreferences.novelTtsSpeed.set(it / 10f) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (ttsPitch * 10).toInt(),
                    valueRange = 1..30,
                    title = stringResource(TDMR.strings.settings_reader_tts_pitch_title),
                    valueString = "${ttsPitch}x",
                    onValueChanged = { readerPreferences.novelTtsPitch.set(it / 10f) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelTtsAutoNextChapter,
                    title = stringResource(TDMR.strings.settings_reader_tts_auto_next_title),
                    subtitle = stringResource(TDMR.strings.settings_reader_tts_auto_next_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelTtsBackgroundPlayback,
                    title = stringResource(TDMR.strings.novel_tts_background_playback),
                    subtitle = stringResource(TDMR.strings.novel_tts_background_playback_summary),
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(TDMR.strings.novel_tts_advanced_settings_info),
                ),
            ),
        )
    }
}
