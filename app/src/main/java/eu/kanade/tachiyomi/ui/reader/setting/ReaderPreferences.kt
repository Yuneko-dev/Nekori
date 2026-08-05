package eu.kanade.tachiyomi.ui.reader.setting

import android.os.Build
import androidx.compose.ui.graphics.BlendMode
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.reader.DefaultStatusBarOrder
import eu.kanade.presentation.reader.appbars.DefaultBottomBarItems
import eu.kanade.presentation.reader.appbars.serialize
import eu.kanade.presentation.reader.serializeStatusBarOrder
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.i18n.MR

class ReaderPreferences(
    preferenceStore: PreferenceStore,
) {

    // region General

    val flashOnPageChange: Preference<Boolean> = preferenceStore.getBoolean("pref_reader_flash", false)

    val flashDurationMillis: Preference<Int> = preferenceStore.getInt("pref_reader_flash_duration", MILLI_CONVERSION)

    val flashPageInterval: Preference<Int> = preferenceStore.getInt("pref_reader_flash_interval", 1)

    val flashColor: Preference<FlashColor> = preferenceStore.getEnum("pref_reader_flash_mode", FlashColor.BLACK)

    val showPageNumber: Preference<Boolean> = preferenceStore.getBoolean("pref_show_page_number_key", true)

    val fullscreen: Preference<Boolean> = preferenceStore.getBoolean("fullscreen", true)

    val drawUnderCutout: Preference<Boolean> = preferenceStore.getBoolean("cutout_short", true)

    val keepScreenOn: Preference<Boolean> = preferenceStore.getBoolean("pref_keep_screen_on_key", false)

    val defaultOrientationType: Preference<Int> = preferenceStore.getInt(
        "pref_default_orientation_type_key",
        ReaderOrientation.FREE.flagValue,
    )

    val readerTheme: Preference<Int> = preferenceStore.getInt("pref_reader_theme_key", 1)

    val folderPerManga: Preference<Boolean> = preferenceStore.getBoolean("create_folder_per_manga", false)

    val skipRead: Preference<Boolean> = preferenceStore.getBoolean("skip_read", false)

    val skipFiltered: Preference<Boolean> = preferenceStore.getBoolean("skip_filtered", true)

    val skipDupe: Preference<Boolean> = preferenceStore.getBoolean("skip_dupe", false)

    val autoTranslate: Preference<Boolean> = preferenceStore.getBoolean("pref_auto_translate", false)

    // endregion

    // region Color filter

    val customBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_custom_brightness_key", false)

    val customBrightnessValue: Preference<Int> = preferenceStore.getInt("custom_brightness_value", 0)

    val colorFilter: Preference<Boolean> = preferenceStore.getBoolean("pref_color_filter_key", false)

    val colorFilterValue: Preference<Int> = preferenceStore.getInt("color_filter_value", 0)

    val colorFilterMode: Preference<Int> = preferenceStore.getInt("color_filter_mode", 0)

    val grayscale: Preference<Boolean> = preferenceStore.getBoolean("pref_grayscale", false)

    val invertedColors: Preference<Boolean> = preferenceStore.getBoolean("pref_inverted_colors", false)

    // endregion

    // region Controls

    // Navigation mode for novel viewers (tap zones)
    val navigationModeNovel: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_novel", 5)

    val novelNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted_novel",
        TappingInvertMode.NONE,
    )

    val showNavigationOverlayNewUser: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_new_user",
        true,
    )

    // endregion

    enum class FlashColor {
        BLACK,
        WHITE,
        WHITE_BLACK,
    }

    enum class TappingInvertMode(
        val titleRes: StringResource,
        val shouldInvertHorizontal: Boolean = false,
        val shouldInvertVertical: Boolean = false,
    ) {
        NONE(MR.strings.tapping_inverted_none),
        HORIZONTAL(MR.strings.tapping_inverted_horizontal, shouldInvertHorizontal = true),
        VERTICAL(MR.strings.tapping_inverted_vertical, shouldInvertVertical = true),
        BOTH(MR.strings.tapping_inverted_both, shouldInvertHorizontal = true, shouldInvertVertical = true),
    }

    enum class NovelWebViewNetworkMode {
        CHROMIUM,
        NETWORK_HELPER,
    }

    // region Novel
    val novelFontSize: Preference<Int> = preferenceStore.getInt("pref_novel_font_size", 16)
    val novelFontFamily: Preference<String> = preferenceStore.getString("pref_novel_font_family", "sans-serif")
    val novelTheme: Preference<String> = preferenceStore.getString("pref_novel_theme", "app")
    val novelLineHeight: Preference<Float> = preferenceStore.getFloat("pref_novel_line_height", 1.6f)
    val novelTextAlign: Preference<String> = preferenceStore.getString("pref_novel_text_align", "left")

    // Stored as half-steps (speed x2) so the slider can move in 0.5 increments with an Int pref.
    // 2..20 maps to speed 1.0..10.0. New key: the old "pref_novel_auto_scroll_speed" mixed a 1..10
    // level and a 5..120 sec/screen scale, so it isn't reused. Divide by 2f to get the speed level.
    val novelAutoScrollSpeed: Preference<Int> = preferenceStore.getInt("pref_novel_auto_scroll_speed_half", 6)

    // Resolve the stored half-step Int to the speed level the viewers scroll at (1.0..10.0).
    fun novelAutoScrollLevel(): Float = novelAutoScrollSpeed.get().coerceIn(2, 20) / 2f
    val novelVolumeKeysScroll: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_volume_keys_scroll", false)
    val novelVolumeKeysScrollDistance: Preference<Int> = preferenceStore.getInt(
        "pref_novel_volume_keys_scroll_distance",
        VOLUME_KEY_SCROLL_DISTANCE_DEFAULT,
    )
    val novelTapToScroll: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_tap_to_scroll", false)
    val novelTextSelectable: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_text_selectable", true)

    // Block media elements (images, videos) in WebView and TextView readers
    val novelBlockMedia: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_block_media", false)

    // Font color (stored as ARGB int, 0 means use theme default)
    // Note: 0xFFFFFFFF (white) = -1 as signed int, so 0 is used as the "unset" marker
    val novelFontColor: Preference<Int> = preferenceStore.getInt("pref_novel_font_color", 0)

    // Background color (stored as ARGB int, 0 means use theme default)
    val novelBackgroundColor: Preference<Int> = preferenceStore.getInt("pref_novel_background_color", 0)

    // Paragraph indentation in em units (0 = no indent, default 2em)
    val novelParagraphIndent: Preference<Float> = preferenceStore.getFloat("pref_novel_paragraph_indent", 0f)

    // Margin preferences (in dp)
    val novelMarginLeft: Preference<Int> = preferenceStore.getInt("pref_novel_margin_left", 16)
    val novelMarginRight: Preference<Int> = preferenceStore.getInt("pref_novel_margin_right", 16)
    val novelMarginTop: Preference<Int> = preferenceStore.getInt("pref_novel_margin_top", 50)
    val novelMarginBottom: Preference<Int> = preferenceStore.getInt("pref_novel_margin_bottom", 16)

    // EPUB specific toggles
    val enableEpubStyles: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_enable_epub_css", true)
    val enableEpubJs: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_enable_epub_js", false)
    val novelSourceCssPriority: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_source_css_priority",
        false,
    )
    val novelPluginUseCustomCss: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_plugin_use_custom_css",
        true,
    )
    val novelPluginUseCustomJs: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_plugin_use_custom_js",
        true,
    )

    // Extra console/dialog/file-chooser behavior; fullscreen video uses the always-present client.
    val novelWebViewDevTools: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_webview_devtools", false)

    val novelWebViewRemoteDebugging: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_webview_remote_debugging",
        false,
    )

    val novelWebViewNetworkMode: Preference<NovelWebViewNetworkMode> = preferenceStore.getEnum(
        "pref_novel_webview_network_mode",
        NovelWebViewNetworkMode.NETWORK_HELPER,
    )

    val novelWebViewLocalProxyEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_webview_local_proxy_enabled",
        false,
    )

    val novelConsoleErrorToast: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_console_error_toast",
        false,
    )

    // Custom CSS/JS stored as JSON array of {title, code} objects
    val novelCustomCss: Preference<String> = preferenceStore.getString("pref_novel_custom_css", "")
    val novelCustomJs: Preference<String> = preferenceStore.getString("pref_novel_custom_js", "")
    val novelCustomCssSnippets: Preference<String> = preferenceStore.getString("pref_novel_css_snippets", "[]")
    val novelCustomJsSnippets: Preference<String> = preferenceStore.getString("pref_novel_js_snippets", "[]")

    // Global CSS/JS presets stored as JSON array of {name, css, js} objects
    val novelGlobalPresets: Preference<String> = preferenceStore.getString("pref_novel_global_presets", "[]")

    // Regex find/replace rules stored as JSON array of {title, pattern, replacement, enabled, isRegex}
    // Applied to chapter HTML content before rendering in both WebView and TextView modes
    val novelRegexReplacements: Preference<String> = preferenceStore.getString("pref_novel_regex_replacements", "[]")

    // Infinite scroll - automatically load next/previous chapters
    val novelInfiniteScroll: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_infinite_scroll", false)

    // Keep chapters loaded in memory (0 = only current, 1 = current + prev, 2 = current + next, 3 = both)
    val novelKeepChaptersLoaded: Preference<Int> = preferenceStore.getInt("pref_novel_keep_chapters_loaded", 0)

    // Custom brightness for novel reader
    val novelCustomBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_custom_brightness", false)

    // Brightness value for novel reader (-75 to 100, 0 = system)
    val novelCustomBrightnessValue: Preference<Int> = preferenceStore.getInt("pref_novel_custom_brightness_value", 0)

    // Show progress slider in novel reader (allows scrolling to position in current chapter)
    val novelShowProgressSlider: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_show_progress_slider",
        true,
    )

    // Show the platform vertical scrollbar in novel readers.
    val novelVerticalScrollbar: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_vertical_scrollbar",
        false,
    )

    // Vertical scrollbar side in novel readers: "left" or "right".
    val novelVerticalScrollbarPosition: Preference<String> = preferenceStore.getString(
        "pref_novel_vertical_scrollbar_position",
        "right",
    )

    // Vertical progress slider height mode: "half" or "full".
    val novelVerticalProgressSliderSize: Preference<String> = preferenceStore.getString(
        "pref_novel_vertical_progress_slider_size",
        "half",
    )

    // Hide chapter title in novel content
    val novelHideChapterTitle: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_hide_chapter_title", false)

    // Force lowercase for all chapter content
    val novelForceTextLowercase: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_force_lowercase", false)

    // Auto-split text after X words until punctuation mark (0 = disabled)
    val novelAutoSplitText: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_auto_split_text", false)
    val novelAutoSplitWordCount: Preference<Int> = preferenceStore.getInt("pref_novel_auto_split_word_count", 50)

    // Use source's original fonts (don't force a specific font family)
    val novelUseOriginalFonts: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_use_original_fonts", false)

    val novelBionicReading: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_bionic_reading", false)

    // Keep screen on while reading
    val novelKeepScreenOn: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_keep_screen_on", false)

    // Paragraph spacing (additional space between paragraphs in em units)
    val novelParagraphSpacing: Preference<Float> = preferenceStore.getFloat("pref_novel_paragraph_spacing", 0.5f)

    // Swipe navigation - swipe left/right to change chapters
    val novelSwipeNavigation: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_swipe_navigation", false)

    // Chapter title display format: 0 = name only, 1 = number only, 2 = both (name + number)
    val novelChapterTitleDisplay: Preference<Int> = preferenceStore.getInt("pref_novel_chapter_title_display", 2)

    // Auto-load next chapter at percentage (legacy 0 may exist; treated as default)
    val novelAutoLoadNextChapterAt: Preference<Int> = preferenceStore.getInt("pref_novel_auto_load_next_at", 95)

    // Mark chapter as read when progress reaches this percentage
    val novelMarkAsReadThreshold: Preference<Int> = preferenceStore.getInt("pref_novel_mark_read_threshold", 95)

    // If enabled, chapters that fully fit in the viewport are marked read immediately.
    val novelMarkShortChapterAsRead: Preference<Boolean> =
        preferenceStore.getBoolean("pref_novel_mark_short_chapter_read", true)

    // Show raw HTML (display HTML tags without parsing) - useful for debugging
    val novelShowRawHtml: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_show_raw_html", false)

    // TTS (Text-to-Speech) preferences
    val novelTtsEnabled: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_tts_enabled", true)
    val novelTtsSpeed: Preference<Float> = preferenceStore.getFloat("pref_novel_tts_speed", 1.0f)
    val novelTtsPitch: Preference<Float> = preferenceStore.getFloat("pref_novel_tts_pitch", 1.0f)
    val novelTtsUseTikTok: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_tts_use_tiktok", false)
    val novelTtsVoice: Preference<String> = preferenceStore.getString("pref_novel_tts_voice", "")
    val novelTtsTikTokVoice: Preference<String> = preferenceStore.getString("pref_novel_tts_tiktok_voice", "")
    val novelTtsAutoNextChapter: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_tts_auto_next", true)
    val novelTtsHighlightStyle: Preference<String> = preferenceStore.getString(
        "pref_novel_tts_highlight_style",
        "background",
    ) // background, underline, outline
    val novelTtsHighlightColor: Preference<Int> = preferenceStore.getInt(
        "pref_novel_tts_highlight_color",
        0xFFFFD54F.toInt(),
    )
    val novelTtsHighlightTextColor: Preference<Int> = preferenceStore.getInt(
        "pref_novel_tts_highlight_text_color",
        0xFF1A1A1A.toInt(),
    )
    val novelTtsEnableHighlight: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_tts_enable_highlight",
        true,
    )
    val novelTtsKeepHighlightInView: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_tts_keep_highlight_in_view",
        true,
    )
    val novelTtsBackgroundPlayback: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_tts_background_playback",
        false,
    )
    val novelTtsControlsVisible: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_tts_controls_visible",
        false,
    )
    val novelTtsAutoStartOnPanelOpen: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_tts_auto_start_on_panel_open",
        false,
    )

    val novelBottomBarItems: Preference<String> = preferenceStore.getString(
        "novel_bottom_bar_items",
        DefaultBottomBarItems.serialize(),
    )

    // Status bar overlay showing time, battery, chapter, and progress during reading
    val novelStatusBarEnabled: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_status_bar_enabled", false)
    val novelStatusBarShowTime: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_status_bar_show_time",
        true,
    )
    val novelStatusBarShowBattery: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_status_bar_show_battery",
        true,
    )
    val novelStatusBarShowChapterNumber: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_status_bar_show_chapter_number",
        true,
    )
    val novelStatusBarShowChapterTitle: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_status_bar_show_chapter_title",
        true,
    )
    val novelStatusBarShowProgress: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_status_bar_show_progress",
        true,
    )

    val novelStatusBarPosition: Preference<String> = preferenceStore.getString(
        "pref_novel_status_bar_position",
        "bottom",
    )

    val novelStatusBarSize: Preference<String> = preferenceStore.getString(
        "pref_novel_status_bar_size",
        "small",
    )

    val novelStatusBarShowCharging: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_status_bar_show_charging",
        true,
    )

    val novelStatusBarOrder: Preference<String> = preferenceStore.getString(
        "pref_novel_status_bar_order",
        DefaultStatusBarOrder.serializeStatusBarOrder(),
    )
    // endregion

    companion object {
        const val MILLI_CONVERSION = 100

        const val TAPZONE_DISABLED_INDEX = 5
        const val TAPZONE_CENTER_INDEX = 6

        const val VOLUME_KEY_SCROLL_DISTANCE_MIN = 5
        const val VOLUME_KEY_SCROLL_DISTANCE_MAX = 95
        const val VOLUME_KEY_SCROLL_DISTANCE_STEP = 5
        const val VOLUME_KEY_SCROLL_DISTANCE_DEFAULT = 75
        const val VOLUME_KEY_SCROLL_DISTANCE_SLIDER_STEPS =
            (VOLUME_KEY_SCROLL_DISTANCE_MAX - VOLUME_KEY_SCROLL_DISTANCE_MIN) /
                VOLUME_KEY_SCROLL_DISTANCE_STEP - 1
        val VolumeKeyScrollDistanceRange = VOLUME_KEY_SCROLL_DISTANCE_MIN..VOLUME_KEY_SCROLL_DISTANCE_MAX

        val TapZones = listOf(
            MR.strings.label_default,
            MR.strings.l_nav,
            MR.strings.kindlish_nav,
            MR.strings.edge_nav,
            MR.strings.right_and_left_nav,
            MR.strings.disabled_nav,
        )

        val ColorFilterMode = buildList {
            addAll(
                listOf(
                    MR.strings.label_default to BlendMode.SrcOver,
                    MR.strings.filter_mode_multiply to BlendMode.Modulate,
                    MR.strings.filter_mode_screen to BlendMode.Screen,
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAll(
                    listOf(
                        MR.strings.filter_mode_overlay to BlendMode.Overlay,
                        MR.strings.filter_mode_lighten to BlendMode.Lighten,
                        MR.strings.filter_mode_darken to BlendMode.Darken,
                    ),
                )
            }
        }
    }
}
