package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.content.Context
import android.util.Base64
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ErrorFormatter
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR

internal class NovelWebViewInlineFeedback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val evaluateJs: (String) -> Unit,
) {

    private var pendingRetry: (() -> Unit)? = null
    private var renderJob: Job? = null
    fun showInlineError(message: String) {
        // A later reader error must not discard the only way to retry a failed append.
        pendingRetry?.let {
            showInlineError(IllegalStateException(message), it)
            return
        }
        beginRender()
        renderJob = scope.launch(Dispatchers.Main) {
            val dismiss = context.stringResource(TDMR.strings.novel_error_tap_to_dismiss)
            evaluateJs(genericScript("$message ($dismiss)".jsEscape()))
        }
    }

    fun showInlineError(error: Throwable, onRetry: () -> Unit) {
        beginRender()
        pendingRetry = onRetry
        val formatted = ErrorFormatter.format(error)
        renderJob = scope.launch(Dispatchers.Main) {
            val category = formatted.category.localized(context).jsEscape()
            val summary = formatted.summary.jsEscape()
            val retry = context.stringResource(MR.strings.action_retry).jsEscape()
            val copy = context.stringResource(MR.strings.action_copy_to_clipboard).jsEscape()
            val trace = Base64.encodeToString(
                formatted.stackTrace.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            evaluateJs(richScript(category, summary, retry, copy, trace))
        }
    }

    fun retryPendingError() {
        val retry = pendingRetry ?: return
        pendingRetry = null
        retry()
    }

    fun clear() {
        renderJob?.cancel()
        renderJob = null
        pendingRetry = null
        evaluateJs("(function() { $REMOVE_OLD_ERROR })();")
    }

    private fun beginRender() {
        renderJob?.cancel()
        pendingRetry = null
    }

    private fun genericScript(message: String) = """
        (function() {
            $REMOVE_OLD_ERROR
            var errorDiv = document.createElement('div');
            errorDiv.id = '$ID_INLINE_ERROR';
            errorDiv.style.cssText = 'text-align:center;padding:16px;color:#FF5252;background:rgba(255,82,82,.1);cursor:pointer;';
            errorDiv.textContent = '$message';
            errorDiv.onclick = function() {
                if (errorDiv._dismissTimer) clearTimeout(errorDiv._dismissTimer);
                if (errorDiv._dismissObserver) errorDiv._dismissObserver.disconnect();
                errorDiv.remove();
            };
            document.body.appendChild(errorDiv);
            var observer = new IntersectionObserver(function(entries) {
                if (entries[0].isIntersecting && entries[0].intersectionRatio >= .9 && !errorDiv._dismissTimer) {
                    observer.disconnect();
                    errorDiv._dismissObserver = null;
                    errorDiv._dismissTimer = setTimeout(function() {
                        if (errorDiv.isConnected) errorDiv.remove();
                    }, $AUTO_DISMISS_MS);
                }
            }, { threshold: .9 });
            errorDiv._dismissObserver = observer;
            observer.observe(errorDiv);
        })();
    """.trimIndent()

    private fun richScript(category: String, summary: String, retry: String, copy: String, trace: String) = """
        (function() {
            $REMOVE_OLD_ERROR
            var errorDiv = document.createElement('div');
            errorDiv.id = '$ID_INLINE_ERROR';
            errorDiv.style.cssText = 'text-align:center;padding:16px;background:rgba(255,82,82,.1);';
            var categoryDiv = document.createElement('div');
            categoryDiv.textContent = '$category';
            categoryDiv.style.cssText = 'color:#FF5252;font-weight:bold;font-size:14px;margin-bottom:6px;';
            errorDiv.appendChild(categoryDiv);
            var summaryDiv = document.createElement('div');
            summaryDiv.textContent = '$summary';
            summaryDiv.style.cssText = 'margin-bottom:10px;overflow-wrap:anywhere;';
            errorDiv.appendChild(summaryDiv);
            var buttons = document.createElement('div');
            var retryButton = document.createElement('button');
            retryButton.style.cssText = '$BUTTON_STYLE';
            retryButton.textContent = '$retry';
            retryButton.onclick = function() {
                errorDiv.remove();
                if (window.Android && window.Android.retryInlineError) window.Android.retryInlineError();
            };
            buttons.appendChild(retryButton);
            var copyButton = document.createElement('button');
            copyButton.style.cssText = '$BUTTON_STYLE';
            copyButton.textContent = '$copy';
            copyButton.onclick = function() {
                if (window.Android && window.Android.copyToClipboard) window.Android.copyToClipboard('$trace');
            };
            buttons.appendChild(copyButton);
            errorDiv.appendChild(buttons);
            $APPEND_ERROR
        })();
    """.trimIndent()

    private fun String.jsEscape(): String = this
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    companion object {
        const val ID_INLINE_ERROR = "inline-error"
        private const val AUTO_DISMISS_MS = 8_000L
        private const val BUTTON_STYLE =
            "margin:0 6px;padding:10px 14px;min-height:44px;border-radius:6px;border:1px solid currentColor;background:transparent;color:inherit;"
        private const val APPEND_ERROR = """
            if (window.Tsundoku?.runtime?.readerLayout?.enabled) {
                errorDiv.style.cssText += 'position:fixed;left:0;right:0;bottom:var(--reader-margin-bottom,0px);z-index:1000;max-height:50vh;overflow:auto;background:var(--reader-background-color);';
            }
            document.body.appendChild(errorDiv);
        """
        private const val REMOVE_OLD_ERROR = """
            var oldErrorDiv = document.getElementById('inline-error');
            if (oldErrorDiv) {
                if (oldErrorDiv._dismissTimer) clearTimeout(oldErrorDiv._dismissTimer);
                if (oldErrorDiv._dismissObserver) oldErrorDiv._dismissObserver.disconnect();
                oldErrorDiv.remove();
            }
        """
    }
}
