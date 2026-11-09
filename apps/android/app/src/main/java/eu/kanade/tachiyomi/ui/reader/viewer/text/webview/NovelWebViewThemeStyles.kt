package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

/** Pure Theme styles module. Inputs are values only; no Reader access. */
internal data class ThemeStylesInput(
    val fontSizeCss: String,
    val fontFamily: String,
    val lineHeight: Float,
    val marginTopCss: String,
    val marginRightCss: String,
    val marginBottomCss: String,
    val marginLeftCss: String,
    val minFragmentLines: Int,
    val textColorHex: String,
    val backgroundColorHex: String,
    val textAlign: String,
    val textSelectable: Boolean,
    val paragraphIndentCss: String,
    val paragraphSpacingCss: String,
    val fontFaceDeclaration: String = "",
    val pluginCustomCss: String = "",
    val customCss: String = "",
    val snippetCss: String = "",
)

internal object NovelWebViewThemeStyles {

    fun buildCss(input: ThemeStylesInput): String {
        val textSelect = if (input.textSelectable) "text" else "none"
        return """
            ${input.fontFaceDeclaration}
            :root {
                --reader-font-size: ${input.fontSizeCss};
                --reader-font-family: ${input.fontFamily};
                --reader-line-height: ${input.lineHeight};
                --reader-margin-top: ${input.marginTopCss};
                --reader-margin-right: ${input.marginRightCss};
                --reader-margin-bottom: ${input.marginBottomCss};
                --reader-min-fragment-lines: ${input.minFragmentLines};
                --reader-margin-left: ${input.marginLeftCss};
                --reader-text-color: ${input.textColorHex};
                --reader-background-color: ${input.backgroundColorHex};
                --reader-text-align: ${input.textAlign};
                --reader-user-select: $textSelect;
                --reader-paragraph-indent: ${input.paragraphIndentCss};
                --reader-paragraph-spacing: ${input.paragraphSpacingCss};
            }
            ${input.pluginCustomCss}
            ${input.customCss}
            ${input.snippetCss}
        """.trimIndent().replace("\n", " ")
    }

    fun bodyClasses(
        hideChapterTitle: Boolean,
        infiniteScroll: Boolean,
        hideChapterSeparator: Boolean,
        sourceCssPriority: Boolean,
        useOriginalFonts: Boolean,
    ): String = buildList {
        if (hideChapterTitle) add("nekori-reader-hide-title")
        if (infiniteScroll && hideChapterSeparator) {
            add("nekori-reader-hide-chapter-separator")
        }
        if (!sourceCssPriority) add("nekori-reader-force-style")
        if (useOriginalFonts) add("nekori-reader-original-font")
    }.joinToString(" ")
}
