// Keeps fragment links scoped to their own chapter when several chapters share one document.
(function () {
    var rootElement = document.getElementById('LNReader-chapter');
    if (!rootElement || rootElement.dataset.scopedAnchorsInstalled) return;

    function findAnchor(chapterElement, name) {
        var elements = chapterElement.querySelectorAll('[id], a[name]');
        for (var i = 0; i < elements.length; i++) {
            if (elements[i].id === name || elements[i].getAttribute('name') === name) {
                return elements[i];
            }
        }
        return null;
    }

    rootElement.addEventListener('click', function (event) {
        if (
            event.defaultPrevented ||
            event.button !== 0 ||
            event.metaKey ||
            event.ctrlKey ||
            event.shiftKey ||
            event.altKey
        ) {
            return;
        }

        var element = event.target instanceof Element ? event.target : event.target.parentElement;
        var link = element && element.closest('a[href^="#"]');
        if (!link || !rootElement.contains(link)) return;

        var layout = window.Tsundoku && window.Tsundoku.runtime && window.Tsundoku.runtime.readerLayout;
        var paged = layout && layout.enabled;
        var chapterElement = link.closest('tsundoku-chapter') || (paged ? rootElement : null);
        // Preserve native anchors for unwrapped, vertically scrolling documents.
        if (!chapterElement) return;

        var fragment = link.getAttribute('href').slice(1);
        if (!fragment && !paged) return;

        try {
            fragment = decodeURIComponent(fragment);
        } catch (_) {
            // Use the original fragment when percent-encoding is malformed.
        }

        // Do not let duplicate ids in another loaded chapter win browser fragment resolution.
        event.preventDefault();

        var target = fragment ? findAnchor(chapterElement, fragment) : chapterElement;
        if (!target) return;

        // scrollIntoView can scroll the column viewport vertically, even with overflow-y: hidden.
        if (paged) layout.revealElement(target);
        else target.scrollIntoView({ behavior: 'auto', block: 'start' });
    }, true);

    rootElement.dataset.scopedAnchorsInstalled = true;
})();
