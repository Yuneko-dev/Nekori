package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.reader.settings.ColorPickerDialog
import eu.kanade.presentation.reader.settings.NovelFontPickerDialog
import eu.kanade.presentation.reader.settings.NovelTtsEnginePreference
import eu.kanade.presentation.reader.settings.NovelTtsVoicePreference
import eu.kanade.presentation.reader.settings.novelTapZoneInvertOptions
import eu.kanade.presentation.reader.settings.novelTapZoneOptions
import eu.kanade.presentation.reader.settings.novelThemes
import eu.kanade.presentation.reader.settings.rememberNovelFontOptions
import eu.kanade.presentation.reader.settings.setNovelScrollbarMode
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.NovelPageEffect
import eu.kanade.tachiyomi.ui.reader.setting.NovelPageSpread
import eu.kanade.tachiyomi.ui.reader.setting.NovelReadingLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsNovelReaderScreen : SearchableSettings {

    override val supportsReset: Boolean get() = true

    @Composable
    override fun getAdditionalResetPreferences(): List<tachiyomi.core.common.preference.Preference<*>> {
        val readerPref = remember { Injekt.get<ReaderPreferences>() }
        // Preserve user-authored snippets and replacement rules when resetting reader controls.
        return listOf(
            readerPref.novelFontSize,
            readerPref.novelReadingLayout,
            readerPref.novelPageSpread,
            readerPref.novelPageEffect,
            readerPref.novelPagedSwipeNavigation,
            readerPref.novelSwipeNavigation,
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
            readerPref.navigationModeNovel,
            readerPref.novelNavInverted,
            readerPref.novelBottomZoneHeight,
            readerPref.flashOnPageChange,
            readerPref.flashDurationMillis,
            readerPref.flashPageInterval,
            readerPref.flashColor,
            readerPref.novelVerticalScrollbar,
            readerPref.novelVerticalScrollbarPosition,
            readerPref.novelVerticalProgressSliderSize,
            readerPref.novelShowProgressSlider,
            readerPref.novelTheme,
            readerPref.novelCustomBrightnessValue,
            readerPref.novelAutoSplitText,
            readerPref.novelAutoSplitWordCount,
            readerPref.novelChapterTitleDisplay,
            readerPref.novelMarkAsReadThreshold,
            readerPref.novelMarkShortChapterAsRead,
            readerPref.novelStatusBarEnabled,
            readerPref.novelStatusBarPosition,
            readerPref.novelStatusBarSize,
            readerPref.novelStatusBarShowTime,
            readerPref.novelStatusBarShowBattery,
            readerPref.novelStatusBarShowProgress,
            readerPref.novelStatusBarShowChapterNumber,
            readerPref.novelStatusBarShowChapterTitle,
            readerPref.novelStatusBarShowCharging,
            readerPref.drawUnderCutout,
        )
    }

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = TDMR.strings.pref_category_novel

    @Composable
    override fun getPreferences(): List<Preference> {
        val readerPref = remember { Injekt.get<ReaderPreferences>() }
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val readingLayout by readerPref.novelReadingLayout.collectAsState()

        return listOfNotNull(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(TDMR.strings.pref_novel_reader_preview),
                subtitle = stringResource(TDMR.strings.pref_novel_reader_preview_summary),
                onClick = { context.startActivity(ReaderActivity.newPreviewIntent(context)) },
            ),
            getDisplayGroup(readerPref),
            getTextGroup(readerPref, navigator),
            getFormattingGroup(readerPref),
            getNavigationGroup(readerPref),
            getReadingGroup(readerPref),
            getAutoScrollGroup(readerPref),
            getContentGroup(readerPref),
            getStatusBarGroup(readerPref, navigator),
            getTtsGroup(readerPref),
            getEInkGroup(readerPref).takeIf { readingLayout == NovelReadingLayout.PAGED },
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
        val fullscreen by readerPreferences.fullscreen.collectAsState()
        val customBrightness by readerPreferences.novelCustomBrightness.collectAsState()
        val brightness by readerPreferences.novelCustomBrightnessValue.collectAsState()
        val theme by readerPreferences.novelTheme.collectAsState()
        val showProgress by readerPreferences.novelShowProgressSlider.collectAsState()
        val verticalScrollbar by readerPreferences.novelVerticalScrollbar.collectAsState()
        val scrollbarPosition by readerPreferences.novelVerticalScrollbarPosition.collectAsState()
        val scrollbarMode = when {
            !showProgress -> "none"
            !verticalScrollbar -> "horizontal"
            scrollbarPosition == "left" -> "vertical_left"
            else -> "vertical_right"
        }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = readerPreferences.novelReadingLayout,
                        entries = mapOf(
                            NovelReadingLayout.SCROLL to stringResource(MR.strings.webtoon_viewer),
                            NovelReadingLayout.PAGED to stringResource(MR.strings.pager_viewer),
                        ),
                        title = stringResource(MR.strings.pref_viewer_type),
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
                            title = stringResource(MR.strings.pref_page_transitions),
                        ),
                    )
                }
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = readerPreferences.defaultOrientationType,
                        entries = ReaderOrientation.entries.filter { it != ReaderOrientation.DEFAULT }
                            .associate { it.flagValue to stringResource(it.stringRes) },
                        title = stringResource(MR.strings.pref_rotation_type),
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = readerPreferences.novelTheme,
                        entries = novelThemes.associate { (label, value) -> value to stringResource(label) },
                        title = stringResource(TDMR.strings.pref_novel_theme),
                    ),
                )
                if (theme == "custom") {
                    add(colorPreference(readerPreferences, background = false))
                    add(colorPreference(readerPreferences, background = true))
                }
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.fullscreen,
                        title = stringResource(MR.strings.pref_fullscreen),
                    ),
                )
                if (fullscreen) {
                    add(
                        Preference.PreferenceItem.SwitchPreference(
                            preference = readerPreferences.drawUnderCutout,
                            title = stringResource(MR.strings.pref_cutout_short),
                        ),
                    )
                }
                add(
                    Preference.PreferenceItem.BasicListPreference(
                        value = scrollbarMode,
                        title = stringResource(TDMR.strings.pref_novel_scrollbar_mode),
                        entries = mapOf(
                            "none" to stringResource(MR.strings.none),
                            "horizontal" to stringResource(TDMR.strings.novel_scrollbar_horizontal),
                            "vertical_left" to stringResource(TDMR.strings.novel_vertical_scrollbar_left),
                            "vertical_right" to stringResource(TDMR.strings.novel_vertical_scrollbar_right),
                        ),
                        onValueChanged = readerPreferences::setNovelScrollbarMode,
                    ),
                )
                if (showProgress && verticalScrollbar) {
                    add(
                        Preference.PreferenceItem.ListPreference(
                            preference = readerPreferences.novelVerticalProgressSliderSize,
                            title = stringResource(TDMR.strings.pref_novel_vertical_progress_slider_size),
                            entries = mapOf(
                                "half" to stringResource(TDMR.strings.novel_vertical_progress_slider_half),
                                "full" to stringResource(TDMR.strings.novel_vertical_progress_slider_full),
                            ),
                        ),
                    )
                }
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
                if (customBrightness) {
                    add(
                        Preference.PreferenceItem.SliderPreference(
                            value = brightness,
                            valueRange = -75..100,
                            title = stringResource(MR.strings.pref_custom_brightness),
                            valueString = "$brightness%",
                            preference = readerPreferences.novelCustomBrightnessValue,
                            onValueChanged = readerPreferences.novelCustomBrightnessValue::set,
                        ),
                    )
                }
            },
        )
    }

    @Composable
    private fun getEInkGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val flashPageState by readerPreferences.flashOnPageChange.collectAsState()

        val flashMillisPref = readerPreferences.flashDurationMillis
        val flashMillis by flashMillisPref.collectAsState()

        val flashIntervalPref = readerPreferences.flashPageInterval
        val flashInterval by flashIntervalPref.collectAsState()

        val flashColorPref = readerPreferences.flashColor

        return Preference.PreferenceGroup(
            title = "E-Ink",
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.flashOnPageChange,
                    title = stringResource(MR.strings.pref_flash_page),
                    subtitle = stringResource(MR.strings.pref_flash_page_summ),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = flashMillis / ReaderPreferences.MILLI_CONVERSION,
                    valueRange = 1..15,
                    title = stringResource(MR.strings.pref_flash_duration),
                    valueString = stringResource(MR.strings.pref_flash_duration_summary, flashMillis),
                    enabled = flashPageState,
                    onValueChanged = { flashMillisPref.set(it * ReaderPreferences.MILLI_CONVERSION) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = flashInterval,
                    valueRange = 1..10,
                    title = stringResource(MR.strings.pref_flash_page_interval),
                    valueString = pluralStringResource(MR.plurals.pref_pages, flashInterval, flashInterval),
                    enabled = flashPageState,
                    onValueChanged = { flashIntervalPref.set(it) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = flashColorPref,
                    entries = mapOf(
                        ReaderPreferences.FlashColor.BLACK to stringResource(MR.strings.pref_flash_style_black),
                        ReaderPreferences.FlashColor.WHITE to stringResource(MR.strings.pref_flash_style_white),
                        ReaderPreferences.FlashColor.WHITE_BLACK
                            to stringResource(MR.strings.pref_flash_style_white_black),
                    ),
                    title = stringResource(MR.strings.pref_flash_with),
                    enabled = flashPageState,
                ),
            ),
        )
    }

    @Composable
    private fun colorPreference(
        readerPreferences: ReaderPreferences,
        background: Boolean,
    ): Preference.PreferenceItem.CustomPreference {
        val title = stringResource(
            if (background) TDMR.strings.pref_novel_background_color else TDMR.strings.pref_novel_font_color,
        )
        return Preference.PreferenceItem.CustomPreference(title = title) {
            val context = LocalContext.current
            val preference = if (background) {
                readerPreferences.novelBackgroundColor
            } else {
                readerPreferences.novelFontColor
            }
            val color by preference.collectAsState()
            val theme by readerPreferences.novelTheme.collectAsState()
            val resolved = ThemeUtils.getThemeColors(context, readerPreferences, theme)
            val displayedColor = if (color != 0) {
                color
            } else if (background) {
                resolved.first
            } else {
                resolved.second
            }
            var showDialog by remember { mutableStateOf(false) }
            TextPreferenceWidget(
                title = title,
                subtitle = "#%06X".format(displayedColor and 0xFFFFFF),
                onPreferenceClick = { showDialog = true },
            )
            if (showDialog) {
                ColorPickerDialog(
                    title = title,
                    initialColor = displayedColor,
                    onDismiss = { showDialog = false },
                    onConfirm = {
                        preference.set(it)
                        showDialog = false
                    },
                )
            }
        }
    }

    @Composable
    private fun getTextGroup(
        readerPreferences: ReaderPreferences,
        navigator: cafe.adriel.voyager.navigator.Navigator,
    ): Preference.PreferenceGroup {
        val fontSize = readerPreferences.novelFontSize.collectAsState().value
        val lineHeight = readerPreferences.novelLineHeight.collectAsState().value
        val autoSplit by readerPreferences.novelAutoSplitText.collectAsState()
        val splitWords by readerPreferences.novelAutoSplitWordCount.collectAsState()

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
                    valueRange = 1..50,
                    title = stringResource(TDMR.strings.pref_novel_line_height),
                    valueString = "${lineHeight}x",
                    onValueChanged = {
                        readerPreferences.novelLineHeight.set(it / 10f)
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.novelTextAlign,
                    entries = mapOf(
                        "left" to stringResource(MR.strings.zoom_start_left),
                        "center" to stringResource(MR.strings.zoom_start_center),
                        "right" to stringResource(MR.strings.zoom_start_right),
                        "justify" to stringResource(TDMR.strings.novel_text_align_justify),
                    ).toMap(),
                    title = stringResource(TDMR.strings.pref_novel_text_align),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelForceTextLowercase,
                    title = stringResource(TDMR.strings.novel_force_lowercase),
                    subtitle = stringResource(TDMR.strings.settings_reader_force_lowercase_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelUseOriginalFonts,
                    title = stringResource(TDMR.strings.pref_novel_use_original_fonts),
                    subtitle = stringResource(TDMR.strings.settings_reader_use_original_fonts_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelBionicReading,
                    title = stringResource(TDMR.strings.pref_novel_bionic_reading),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelAutoSplitText,
                    title = stringResource(TDMR.strings.novel_auto_split),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = splitWords.coerceIn(20, 2000),
                    valueRange = 20..2000,
                    title = stringResource(TDMR.strings.novel_split_word_count),
                    enabled = autoSplit,
                    preference = readerPreferences.novelAutoSplitWordCount,
                    onValueChanged = readerPreferences.novelAutoSplitWordCount::set,
                ),
            ),
        )
    }

    @Composable
    private fun getNavigationGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val readingLayout = readerPreferences.novelReadingLayout.collectAsState().value
        val volumeKeysScroll = readerPreferences.novelVolumeKeysScroll.collectAsState().value
        val volumeKeysScrollDistance = readerPreferences.novelVolumeKeysScrollDistance.collectAsState().value
        val navigationMode by readerPreferences.navigationModeNovel.collectAsState()
        val bottomZoneHeight by readerPreferences.novelBottomZoneHeight.collectAsState()
        val invertOptions = novelTapZoneInvertOptions(navigationMode)

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_reader_navigation),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = readerPreferences.navigationModeNovel,
                        title = stringResource(MR.strings.pref_viewer_nav),
                        entries = novelTapZoneOptions.associate { (value, label) -> value to stringResource(label) },
                    ),
                )
                if (invertOptions.isNotEmpty()) {
                    add(
                        Preference.PreferenceItem.ListPreference(
                            preference = readerPreferences.novelNavInverted,
                            title = stringResource(MR.strings.pref_read_with_tapping_inverted),
                            entries = invertOptions.associate { (value, label) -> value to stringResource(label) },
                        ),
                    )
                }
                if (navigationMode == ReaderPreferences.TAPZONE_BOTTOM_INDEX) {
                    add(
                        Preference.PreferenceItem.SliderPreference(
                            value = bottomZoneHeight,
                            valueRange = 5..50,
                            title = stringResource(TDMR.strings.novel_nav_zone_height),
                            valueString = "$bottomZoneHeight%",
                            preference = readerPreferences.novelBottomZoneHeight,
                            onValueChanged = readerPreferences.novelBottomZoneHeight::set,
                        ),
                    )
                }
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
    private fun getReadingGroup(preferences: ReaderPreferences) = Preference.PreferenceGroup(
        title = stringResource(MR.strings.pref_category_reading),
        preferenceItems = listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.showReadingMode,
                title = stringResource(MR.strings.pref_show_reading_mode),
                subtitle = stringResource(MR.strings.pref_show_reading_mode_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.showNavigationOverlay,
                title = stringResource(MR.strings.pref_show_navigation_mode),
                subtitle = stringResource(MR.strings.pref_show_navigation_mode_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.skipRead,
                title = stringResource(MR.strings.pref_skip_read_chapters),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.skipFiltered,
                title = stringResource(MR.strings.pref_skip_filtered_chapters),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.skipDupe,
                title = stringResource(MR.strings.pref_skip_dupe_chapters),
            ),
        ),
    )

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
                    valueRange = 0..100,
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
                    title = stringResource(TDMR.strings.pref_novel_infinite_scroll),
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
                    title = stringResource(TDMR.strings.pref_novel_hide_chapter_title),
                    subtitle = stringResource(TDMR.strings.settings_reader_hide_chapter_title_summary),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.novelChapterTitleDisplay,
                    title = stringResource(TDMR.strings.pref_novel_chapter_title_display),
                    entries = mapOf(
                        0 to stringResource(MR.strings.name),
                        1 to stringResource(TDMR.strings.novel_chapter_display_number),
                        2 to stringResource(TDMR.strings.novel_chapter_display_both),
                    ),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelBlockMedia,
                    title = stringResource(TDMR.strings.pref_novel_block_media),
                    subtitle = stringResource(TDMR.strings.settings_reader_block_media_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelTextSelectable,
                    title = stringResource(TDMR.strings.pref_novel_text_selectable),
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
