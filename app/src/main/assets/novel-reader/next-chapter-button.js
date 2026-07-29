// LNReader-compatible chapter ending for the non-infinite reader.
//
// Replaces:
//   __BTN_CONTAINER_ID__     - DOM id of the wrapping div
//   __SAFE_BOTTOM_VAR__      - safe-area bottom CSS custom property name
//   __HAS_NEXT_CHAPTER__     - true / false
//   __FINISHED_TEXT__        - JSON-quoted localized label
//   __NEXT_CHAPTER_TEXT__    - JSON-quoted localized button label
//   __NO_NEXT_CHAPTER_TEXT__ - JSON-quoted localized end-of-novel label

(function () {
    var existing = document.getElementById('__BTN_CONTAINER_ID__');
    if (existing) existing.remove();

    var container = document.createElement('div');
    container.id = '__BTN_CONTAINER_ID__';
    container.style.paddingBottom = 'calc(40px + var(__SAFE_BOTTOM_VAR__, 0px))';

    var finished = document.createElement('div');
    finished.className = 'info-text';
    finished.textContent = __FINISHED_TEXT__;
    container.appendChild(finished);

    if (__HAS_NEXT_CHAPTER__) {
        var button = document.createElement('button');
        button.className = 'next-button';
        button.type = 'button';
        button.textContent = __NEXT_CHAPTER_TEXT__;
        button.addEventListener('click', function (event) {
            event.stopPropagation();
            if (window.Android && window.Android.suppressReaderGestures) {
                window.Android.suppressReaderGestures();
            }
            window.Android.loadNextChapter();
        });
        container.appendChild(button);
    } else {
        var message = document.createElement('div');
        message.className = 'info-text';
        message.textContent = __NO_NEXT_CHAPTER_TEXT__;
        container.appendChild(message);
    }

    document.body.appendChild(container);
})();
