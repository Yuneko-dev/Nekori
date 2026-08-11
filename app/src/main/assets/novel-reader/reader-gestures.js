// Tells native who owns the touch that just started.
//
// WebView.hitTestResult reports UNKNOWN_TYPE for <video>, <iframe> and <button> exactly as it does
// for inert prose, so the native gesture detector cannot tell a play/pause tap from a page tap. The
// DOM can. Native starts every ACTION_DOWN blocked, so a touch this listener never sees - a
// cross-origin iframe, a plugin in a nested document - stays blocked instead of toggling chrome.
//
// Capturing phase on purpose: a control that calls stopPropagation() must still be recognised.
(function () {
    if (window.__tsundokuGestures) return;
    window.__tsundokuGestures = true;

    // Everything that owns its own gestures: media, embedded documents, links, form controls, ARIA
    // widgets, and the reader's own floating containers (their buttons already match `button`, but
    // their padding and backdrop do not).
    var OWNED = [
        'a[href]', 'button', 'input', 'select', 'textarea', 'label', 'summary', 'details',
        'video', 'audio', 'iframe', 'object', 'embed',
        '[onclick]', '[contenteditable=""]', '[contenteditable="true"]',
        '[role="button"]', '[role="link"]', '[role="checkbox"]', '[role="radio"]',
        '[role="switch"]', '[role="slider"]', '[role="spinbutton"]', '[role="tab"]',
        '[role="menuitem"]', '[role="option"]', '[role="combobox"]', '[role="textbox"]',
        '[role="dialog"]', '[aria-modal="true"]',
        '#Image-Modal', '#TTS-Controller', '#next-chapter-btn-container',
        '#lnreader-player-container', '#lnreader-debug-overlay', '#lnreader-debug-toggle',
    ].join(',');

    document.addEventListener(
        'pointerdown',
        function (event) {
            if (!window.Android || !window.Android.claimReaderGesture) return;
            var target = event.target instanceof Element ? event.target : null;
            var owner;
            if (!target || !event.isPrimary || target.closest(OWNED)) {
                // A secondary pointer blocks the whole gesture: a pinch is never a tap or a swipe.
                owner = 'blocked';
            } else if (target.closest('img')) {
                // Only an image outside any interactive ancestor; tap shows chrome, long press is
                // the page's own image modal (contextmenu, in reader-ui.js).
                owner = 'image';
            } else {
                owner = 'surface';
            }
            window.Android.claimReaderGesture(owner);
        },
        true,
    );

    if (window.Android && window.Android.onReaderGesturesReady) {
        window.Android.onReaderGesturesReady();
    }
})();
