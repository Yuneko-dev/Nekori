// Page-side scroll listener for the novel WebView reader.
// Installed once per load via NovelWebViewStyler.injectScrollTracking(), which substitutes the
// __TSUNDOKU_OBJECT_NAME__ / __CHAPTER_DIVIDER_CLASS__ / __CHAPTER_ID_ATTR__ /
// __INFINITE_SCROLL_ENABLED__ / __LOAD_THRESHOLD__ / __DONE_THRESHOLD__ / __PROGRESS_EVENT__ tokens.
//
// Reports to the Android JS interface:
//   onChapterScrollUpdate(chapterId, progress)  visible chapter changed
//   onScrollUpdate(progress)                     live slider position
//   onScrollProgress(progress)                   persist point (scroll settled / 100%)
//   loadNextChapter()
//
// Publishes for snippets/plugins:
//   runtime.progress / runtime.chapterProgress / runtime.currentChapterId  (updated every frame)
//   window event __PROGRESS_EVENT__  { progress, chapterProgress, chapterId, isLast }
//     dispatched JS-side (no Kotlin bridge hop), throttled with the slider bridge.

(function () {
    window.__TSUNDOKU_OBJECT_NAME__ = window.__TSUNDOKU_OBJECT_NAME__ || {};
    window.__TSUNDOKU_OBJECT_NAME__.runtime = window.__TSUNDOKU_OBJECT_NAME__.runtime || {};
    var runtime = window.__TSUNDOKU_OBJECT_NAME__.runtime;

    if (runtime.infiniteScrollInstalled) {
        return;
    }
    runtime.infiniteScrollInstalled = true;

    var infiniteScrollEnabled = __INFINITE_SCROLL_ENABLED__;
    var loadThreshold = __LOAD_THRESHOLD__;
    var lastSliderProgress = -1;
    var lastScrollUpdateTime = 0;
    // Late reflow (images/fonts) triggers scroll anchoring that fires scrollend off a
    // still-settling docHeight; persistCurrent waits this out before saving.
    var lastBodyResizeAt = 0;
    var SETTLE_MS = 400;

    runtime.loadingNext = runtime.loadingNext || false;
    runtime.shortChapterLoadPending = runtime.shortChapterLoadPending || false;
    runtime.setLoadingNext = function (v) {
        runtime.loadingNext = !!v;
        if (!runtime.loadingNext && runtime.shortChapterLoadPending) {
            runtime.shortChapterLoadPending = false;
            loadNextChapterIfIdle();
        }
    };
    runtime.noMoreChapters = runtime.noMoreChapters || false;
    runtime.setNoMoreChapters = function (v) { runtime.noMoreChapters = !!v; };
    runtime.lastChapterIdxSeen = (typeof runtime.lastChapterIdxSeen === 'number') ? runtime.lastChapterIdxSeen : -1;
    // Forces the next scroll frame to re-emit onChapterScrollUpdate. Called by the Android side when
    // it lifts the scroll-restore guard, so a chapter switch dropped while the guard was up (this
    // callback is edge-triggered and won't otherwise re-fire for the same idx) is re-reported.
    runtime.resetChapterTracking = function () { runtime.lastChapterIdxSeen = -1; };

    window.chapterBoundaries = window.chapterBoundaries || [];
    runtime.knownDividerCount = runtime.knownDividerCount || 0;

    function computeState() {
        var scrollTop = window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0;
        // window.innerHeight is the visual viewport, documentElement.clientHeight (layout viewport)
        // diverges under useWideViewPort/loadWithOverviewMode so the bottom never reaches 100%.
        var docHeight = Math.max(
            document.documentElement.scrollHeight,
            document.body ? document.body.scrollHeight : 0
        );
        var viewport = window.innerHeight || document.documentElement.clientHeight;
        var scrollable = docHeight - viewport;
        var progress = scrollable > 0 ? scrollTop / scrollable : 1;
        if (scrollable > 0 && scrollTop >= scrollable - 2) progress = 1.0;
        if (progress >= __DONE_THRESHOLD__) progress = 1.0;
        if (progress < 0) progress = 0;

        var chapterProgress = progress;
        var idx = 0;
        var chapterId = null;
        var isLast = true;
        if (infiniteScrollEnabled && window.chapterBoundaries.length > 1) {
            for (var i = 0; i < window.chapterBoundaries.length; i++) {
                if (scrollTop >= window.chapterBoundaries[i].startOffset) idx = i; else break;
            }
            var boundary = window.chapterBoundaries[idx];
            chapterId = boundary.chapterId;
            var chapterScrollY = Math.max(scrollTop - boundary.startOffset, 0);
            isLast = idx === window.chapterBoundaries.length - 1;
            if (boundary.height <= viewport) {
                chapterProgress = 1.0;
            } else {
                // Keep the denominator stable when an append turns the current last chapter into a
                // middle chapter; otherwise an unchanged 95% position jumps backwards.
                var effectiveHeight = boundary.height - viewport;
                chapterProgress = Math.min(chapterScrollY / effectiveHeight, 1.0);
                if (chapterProgress >= __DONE_THRESHOLD__) chapterProgress = 1.0;
            }
        }
        return { progress: progress, chapterProgress: chapterProgress, idx: idx, chapterId: chapterId, isLast: isLast };
    }

    var PROGRESS_EVENT = '__PROGRESS_EVENT__';
    // Dispatched page-side (no Kotlin bridge). Constructing + dispatching a CustomEvent with no
    // listeners is cheap, so gating on subscriber count isn't worth the bookkeeping; the 50ms/0.01
    // slider throttle already bounds how often this fires.
    function dispatchProgress(s) {
        try {
            window.dispatchEvent(new CustomEvent(PROGRESS_EVENT, {
                detail: {
                    progress: s.progress,
                    chapterProgress: s.chapterProgress,
                    chapterId: s.chapterId,
                    isLast: s.isLast,
                },
            }));
        } catch (e) {}
    }

    function publishProgress(s) {
        runtime.progress = s.progress;
        runtime.chapterProgress = s.chapterProgress;
        runtime.currentChapterId = s.chapterId;
    }

    var framePending = false;

    function loadNextChapterIfIdle() {
        if (runtime.loadingNext || !infiniteScrollEnabled || runtime.noMoreChapters) return;
        runtime.loadingNext = true;
        try {
            Android.loadNextChapter();
        } catch (e) {
            runtime.loadingNext = false;
        }
    }

    function onFrame() {
        framePending = false;
        var s = computeState();
        publishProgress(s);

        if (infiniteScrollEnabled && s.idx !== runtime.lastChapterIdxSeen && s.chapterId != null) {
            runtime.lastChapterIdxSeen = s.idx;
            Android.onChapterScrollUpdate(String(s.chapterId), s.chapterProgress);
        }

        // Throttle slider bridge (50ms + 0.01 delta); 100% persist exempt so completion isn't dropped.
        if (Math.abs(s.chapterProgress - lastSliderProgress) > 0.01) {
            var now = Date.now();
            if (now - lastScrollUpdateTime > 50) {
                lastScrollUpdateTime = now;
                lastSliderProgress = s.chapterProgress;
                Android.onScrollUpdate(s.chapterProgress);
                dispatchProgress(s);
            }
        }
        // Only the last chapter flashes to 100% and self-persists; a middle chapter momentarily
        // hitting 1.0 as it crosses a divider is marked read Android-side on the chapter switch,
        // so flashing the slider to 100% here would just flicker it for one frame.
        if (s.isLast && s.chapterProgress >= 1.0 && lastSliderProgress !== 1.0) {
            lastSliderProgress = 1.0;
            Android.onScrollUpdate(1.0);
            Android.onScrollProgress(1.0);
            dispatchProgress(s);
        }

        var shouldLoadNext = window.chapterBoundaries.length > 1
            ? s.idx === window.chapterBoundaries.length - 1 && s.chapterProgress >= loadThreshold
            : s.progress >= loadThreshold;
        if (shouldLoadNext) loadNextChapterIfIdle();
    }

    function onScroll() {
        if (framePending) return;
        framePending = true;
        requestAnimationFrame(onFrame);
    }
    window.addEventListener('scroll', onScroll, { passive: true });

    // computeState() is re-read here so a chapter switch mid-scroll can't persist a stale value.
    function persistCurrent(retriesLeft) {
        if (retriesLeft === undefined) retriesLeft = 3;
        if (retriesLeft > 0 && Date.now() - lastBodyResizeAt < SETTLE_MS) {
            setTimeout(function () { persistCurrent(retriesLeft - 1); }, SETTLE_MS);
            return;
        }
        Android.onScrollProgress(computeState().chapterProgress);
    }
    if ('onscrollend' in window) {
        window.addEventListener('scrollend', function () { persistCurrent(); }, { passive: true });
    } else {
        var settleTimer = null;
        window.addEventListener('scroll', function () {
            clearTimeout(settleTimer);
            settleTimer = setTimeout(persistCurrent, 250);
        }, { passive: true });
    }

    // Marks in-flight reflow so persistCurrent can wait it out before saving.
    if (typeof ResizeObserver === 'function' && document.body) {
        var bodyResizeObserver = new ResizeObserver(function () {
            lastBodyResizeAt = Date.now();
        });
        bodyResizeObserver.observe(document.body);
    }

    window.addChapterBoundary = function (chapterId, startOffset, height) {
        window.chapterBoundaries.push({
            chapterId: chapterId,
            startOffset: startOffset,
            height: height
        });
    };

    var shortChapterObserver = null;
    var observedShortChapter = null;
    if (infiniteScrollEnabled && typeof IntersectionObserver === 'function') {
        shortChapterObserver = new IntersectionObserver(function (entries) {
            if (entries.some(function (entry) {
                return entry.target === observedShortChapter &&
                    entry.isIntersecting &&
                    entry.intersectionRatio >= loadThreshold;
            })) {
                if (runtime.loadingNext && !runtime.noMoreChapters) {
                    runtime.shortChapterLoadPending = true;
                } else {
                    loadNextChapterIfIdle();
                }
            }
        }, { threshold: loadThreshold });
    }

    function observeLastShortChapter(dividers) {
        if (!shortChapterObserver) return;
        var lastDivider = dividers[dividers.length - 1];
        var chapter = lastDivider ? lastDivider.nextElementSibling : null;
        var viewport = window.innerHeight || document.documentElement.clientHeight;
        var shortChapter = chapter && chapter.getBoundingClientRect().height <= viewport ? chapter : null;
        if (shortChapter === observedShortChapter) return;
        shortChapterObserver.disconnect();
        observedShortChapter = shortChapter;
        if (observedShortChapter) shortChapterObserver.observe(observedShortChapter);
    }

    // getBoundingClientRect() + scrollY (not offsetTop) so offsets are correct inside a positioned
    // container.
    window.updateChapterBoundaries = function () {
        var dividers = document.querySelectorAll('.__CHAPTER_DIVIDER_CLASS__');
        var scrollY = window.scrollY || window.pageYOffset || 0;
        var boundaries = [];
        dividers.forEach(function (divider, index) {
            var chapterId = divider.getAttribute('__CHAPTER_ID_ATTR__');
            var rect = divider.getBoundingClientRect();
            var startOffset = rect.top + scrollY;
            var nextDivider = dividers[index + 1];
            var endOffset = nextDivider
                ? nextDivider.getBoundingClientRect().top + scrollY
                : document.body.scrollHeight;
            boundaries.push({
                chapterId: chapterId,
                startOffset: startOffset,
                height: endOffset - startOffset
            });
        });
        window.chapterBoundaries = boundaries;
        runtime.knownDividerCount = dividers.length;
        observeLastShortChapter(dividers);
    };

    // Rebuild boundaries on DOM change (append/prepend/trim) or reflow (image/font load), coalesced
    // to one rebuild per frame.
    if (infiniteScrollEnabled && document.body) {
        var rebuildPending = false;
        function scheduleBoundaryRebuild() {
            if (rebuildPending) return;
            rebuildPending = true;
            requestAnimationFrame(function () {
                rebuildPending = false;
                if (typeof window.updateChapterBoundaries === 'function') window.updateChapterBoundaries();
            });
        }
        if (typeof ResizeObserver === 'function') {
            runtime.boundaryResizeObserver = new ResizeObserver(scheduleBoundaryRebuild);
            runtime.boundaryResizeObserver.observe(document.body);
        }
        if (typeof MutationObserver === 'function') {
            // Coalesce to one rebuild per frame rather than scanning querySelectorAll on every
            // mutation (a chapter insert fires many); updateChapterBoundaries re-reads dividers anyway.
            runtime.boundaryMutationObserver = new MutationObserver(scheduleBoundaryRebuild);
            runtime.boundaryMutationObserver.observe(document.body, { childList: true, subtree: true });
        }
    }

    requestAnimationFrame(function () {
        if (typeof window.updateChapterBoundaries === 'function') window.updateChapterBoundaries();
        var s0 = computeState();
        publishProgress(s0);
        dispatchProgress(s0);
    });
})();
