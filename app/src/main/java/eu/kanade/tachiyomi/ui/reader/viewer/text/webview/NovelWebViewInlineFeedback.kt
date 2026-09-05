package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class NovelWebViewInlineFeedback(
    private val scope: CoroutineScope,
    private val evaluateJs: (String) -> Unit,
) {

    fun showInlineError(message: String) {
        scope.launch(Dispatchers.Main) {
            val escapedMessage = message
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")

            val js = """
            (function() {
                var oldErrorDiv = document.getElementById('$ID_INLINE_ERROR');
                if (oldErrorDiv) oldErrorDiv.remove();

                var errorDiv = document.createElement('div');
                errorDiv.id = '$ID_INLINE_ERROR';
                errorDiv.style.textAlign = 'center';
                errorDiv.style.padding = '16px';
                errorDiv.style.color = '#FF5252';
                errorDiv.style.backgroundColor = 'rgba(255, 82, 82, 0.1)';
                errorDiv.style.cursor = 'pointer';
                errorDiv.textContent = '$escapedMessage (tap to dismiss)';

                var dismissTimer = null;

                errorDiv.onclick = function() {
                    if (dismissTimer !== null) {
                        clearTimeout(dismissTimer);
                    }
                    errorDiv.remove();
                };

                document.body.appendChild(errorDiv);

                var observer = new IntersectionObserver(function(entries) {
                    var entry = entries[0];

                    if (entry.isIntersecting && entry.intersectionRatio >= 0.9) {
                        observer.disconnect();

                        dismissTimer = setTimeout(function() {
                            errorDiv.remove();
                        }, $AUTO_DISMISS_MS);
                    }
                }, {
                    threshold: 0.9
                });

                observer.observe(errorDiv);
            })();
            """.trimIndent()

            evaluateJs(js)
        }
    }

    companion object {
        const val ID_INLINE_ERROR = "inline-error"
        private const val AUTO_DISMISS_MS = 8_000L
    }
}
