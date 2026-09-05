// Horizontal CSS-column driver. Native code owns gestures/chrome and configures this facade.
(function () {
    "use strict";

    // Loaded as a raw asset after reader-layout.js has resolved the Kotlin substitution token.
    var root = window.Tsundoku;
    var runtime = root && root.runtime;
    var layout = runtime && runtime.readerLayout;
    var container = document.getElementById("LNReader-chapter");
    if (!layout || !container || layout._pageDriverInstalled) return;
    layout._pageDriverInstalled = true;
    var bridge = window.Android || {};

    function callAndroid(name, args) {
        if (typeof bridge[name] === "function") bridge[name].apply(bridge, args || []);
    }

    var math = layout._math;
    var config = {
        enabled: false, spread: "single", direction: "ltr", infinite: false,
        threshold: 0.8, chapterId: "",
    };
    var pageMap = [];
    var totalLeaves = 1;
    var framePending = false;
    var settleTimer = null;
    var reflowPending = false;
    var silent = false;
    var restore = null;
    var lastUnit = null;
    var lastChapterId = null;
    var lastChapterProgress = null;
    var lastPositionKey = null;
    var silentSourceUnit = null;
    var BLANK_CLASS = "tsundoku-layout-blank-leaf";
    var observedLayoutNodes = typeof WeakSet === "function" ? new WeakSet() : null;
    var resizeObserver = typeof ResizeObserver === "function" ? new ResizeObserver(scheduleReflow) : null;

    function spreadSize() { return config.spread === "double" ? 2 : 1; }
    function viewport() { return Math.max(container.clientWidth, 1); }
    function logicalOffset() { return math.logicalOffset(container.scrollLeft || 0, config.direction); }
    function currentUnit() {
        return math.unitFromOffset(logicalOffset(), viewport(), math.unitCount(totalLeaves, config.spread));
    }
    function maxLogicalOffset() {
        return Math.max(0, (math.unitCount(totalLeaves, config.spread) - 1) * viewport());
    }
    function scrollToUnit(unit, behavior) {
        var count = math.unitCount(totalLeaves, config.spread);
        var target = Math.min(Math.max(unit, 0), count - 1) * viewport();
        container.scrollTo({
            left: math.physicalOffset(target, config.direction),
            behavior: behavior === "smooth" ? "smooth" : "auto",
        });
    }
    function clampToPageBounds() {
        var offset = logicalOffset();
        var clamped = Math.min(Math.max(offset, 0), maxLogicalOffset());
        if (Math.abs(offset - clamped) <= 0.5) return;
        container.scrollTo({ left: math.physicalOffset(clamped, config.direction), behavior: "auto" });
    }
    function setCompatState(name, value) {
        if (!window.pageReader) return;
        var state = window.pageReader[name];
        if (state && (typeof state === "object" || typeof state === "function")) state.val = value;
        else window.pageReader[name] = value;
    }
    function requestChapter(direction, atBoundary) {
        if (direction < 0) return;
        if (!config.infinite) {
            callAndroid("loadNextChapter");
            return;
        }
        if (runtime.loadingNext || (runtime.noMoreChapters && !atBoundary)) return;
        runtime.loadingNext = true;
        try {
            callAndroid("loadNextChapter");
        } catch (e) {
            runtime.loadingNext = false;
        }
    }
    function leafForRect(rect, hostRect, offset, leafWidth) {
        var position = config.direction === "rtl"
            ? hostRect.right - rect.right + offset
            : rect.left - hostRect.left + offset;
        return Math.max(0, Math.floor((position + 1) / leafWidth));
    }
    function measureMap() {
        var hostRect = container.getBoundingClientRect();
        var offset = logicalOffset();
        var leafWidth = viewport() / spreadSize();
        var chapters = container.querySelectorAll("tsundoku-chapter");
        var result = [];
        chapters.forEach(function (chapter) {
            observeLayoutNode(chapter);
            chapter.querySelectorAll("tsundoku-chapter-summary").forEach(observeLayoutNode);
            var rects = Array.from(chapter.getClientRects ? chapter.getClientRects() : []);
            if (!rects.length) rects = [chapter.getBoundingClientRect()];
            var leaves = rects.map(function (rect) { return leafForRect(rect, hostRect, offset, leafWidth); });
            var start = Math.min.apply(Math, leaves);
            var end = Math.max.apply(Math, leaves);
            result.push({
                chapterId: String(chapter.getAttribute("data-chapter-id") || ""),
                startLeaf: start,
                leafCount: Math.max(1, end - start + 1),
                element: chapter,
            });
        });
        return result;
    }
    function observeLayoutNode(node) {
        if (!resizeObserver || !node || (observedLayoutNodes && observedLayoutNodes.has(node))) return;
        resizeObserver.observe(node);
        if (observedLayoutNodes) observedLayoutNodes.add(node);
    }
    function rebuildBlankLeaves() {
        container.querySelectorAll("." + BLANK_CLASS).forEach(function (blank) { blank.remove(); });
        if (config.spread !== "double") return;
        measureMap().forEach(function (entry) {
            if (entry.leafCount % 2 === 0) return;
            var blank = document.createElement("div");
            blank.className = BLANK_CLASS;
            blank.setAttribute("aria-hidden", "true");
            entry.element.insertAdjacentElement("afterend", blank);
        });
    }
    function emitPosition(persist) {
        if (!config.enabled || silent || !pageMap.length) return;
        var unit = currentUnit();
        var globalLeaf = unit * spreadSize();
        var chapter = math.chapterForLeaf(pageMap, globalLeaf);
        if (!chapter) return;
        var localLeaf = Math.min(Math.max(globalLeaf - chapter.startLeaf, 0), chapter.leafCount - 1);
        var localUnit = Math.floor(localLeaf / spreadSize());
        var unitCount = math.unitCount(chapter.leafCount, config.spread);
        var progress = math.progressForUnit(localUnit, unitCount);
        var range = math.leafRange(localUnit, chapter.leafCount, config.spread);
        var sameChapter = chapter.chapterId === lastChapterId;
        var positionKey = [chapter.chapterId, localUnit, unitCount, chapter.leafCount].join(":");
        runtime.progress = progress;
        runtime.chapterProgress = progress;
        runtime.currentChapterId = chapter.chapterId;
        if (positionKey !== lastPositionKey) {
            lastPositionKey = positionKey;
            setCompatState("page", localUnit + 1);
            setCompatState("totalPages", unitCount);
            if (!sameChapter) {
                runtime.lastChapterIdSeen = chapter.chapterId;
                callAndroid("onChapterScrollUpdate", [chapter.chapterId, progress]);
            }
            callAndroid("onPagePositionChanged", [
                chapter.chapterId, localUnit, unitCount,
                range.firstLeaf, range.lastLeaf, chapter.leafCount
            ]);
            callAndroid("onScrollUpdate", [progress]);
        }
        if (persist) callAndroid("onScrollProgress", [progress]);

        if (config.infinite && chapter === pageMap[pageMap.length - 1] && progress >= config.threshold) requestChapter(1);
        lastUnit = unit;
        lastChapterId = chapter.chapterId;
        lastChapterProgress = progress;
    }
    function onScroll() {
        clampToPageBounds();
        if (!framePending) {
            framePending = true;
            requestAnimationFrame(function () { framePending = false; emitPosition(false); });
        }
        clearTimeout(settleTimer);
        settleTimer = setTimeout(function () {
            var unit = currentUnit();
            if (Math.abs(logicalOffset() - unit * viewport()) > 1) scrollToUnit(unit);
            emitPosition(true);
        }, 180);
    }
    function doReflow() {
        reflowPending = false;
        if (!config.enabled) return;
        var pendingRestore = restore;
        var snapshot = pendingRestore || (pageMap.length ? {
            chapterId: (math.chapterForLeaf(pageMap, currentUnit() * spreadSize()) || {}).chapterId,
            progress: lastChapterProgress,
        } : null);
        rebuildBlankLeaves();
        pageMap = measureMap();
        var contentLeaves = Math.max(1, Math.round(container.scrollWidth / (viewport() / spreadSize())));
        if (!pageMap.length && config.spread === "double" && contentLeaves % 2 !== 0) {
            var blank = document.createElement("div");
            blank.className = BLANK_CLASS;
            blank.setAttribute("aria-hidden", "true");
            container.appendChild(blank);
        }
        totalLeaves = Math.max(1, Math.round(container.scrollWidth / (viewport() / spreadSize())));
        if (!pageMap.length) {
            var readerChapter = window.reader && window.reader.chapter;
            pageMap = [{
                chapterId: String(config.chapterId || (readerChapter && readerChapter.id) || ""),
                startLeaf: 0,
                leafCount: contentLeaves,
                element: container,
            }];
        }
        if (snapshot && snapshot.chapterId != null) {
            var chapter = pageMap.find(function (entry) { return entry.chapterId === snapshot.chapterId; });
            if (chapter) {
                var localUnit = math.unitFromProgress(snapshot.progress || 0, math.unitCount(chapter.leafCount, config.spread));
                scrollToUnit(Math.floor(chapter.startLeaf / spreadSize()) + localUnit, "instant");
            }
        }
        restore = null;
        if (pendingRestore) {
            lastUnit = null;
            lastChapterProgress = null;
        }
        lastPositionKey = null;
        emitPosition(false);
    }
    function scheduleReflow() {
        if (reflowPending) return;
        reflowPending = true;
        requestAnimationFrame(doReflow);
    }

    var driver = {
        configure: function (value) {
            config.enabled = !!value.enabled;
            config.spread = value.spread === "double" ? "double" : "single";
            config.direction = value.direction === "rtl" ? "rtl" : "ltr";
            config.infinite = !!value.infinite;
            config.chapterId = value.chapterId == null ? "" : String(value.chapterId);
            var threshold = Number(value.threshold);
            config.threshold = Number.isFinite(threshold) ? Math.min(Math.max(threshold, 0), 1) : 0.8;
            document.body.classList.toggle("page-reader", config.enabled);
            container.dataset.readerSpread = config.spread;
            if (config.enabled) container.dir = config.direction;
            else container.removeAttribute("dir");
            if (config.enabled) scheduleReflow();
            else container.querySelectorAll("." + BLANK_CLASS).forEach(function (blank) { blank.remove(); });
        },
        moveBy: function (delta, behavior) {
            var source = silentSourceUnit == null ? currentUnit() : silentSourceUnit;
            var destination = source + Math.sign(Number(delta || 0));
            var count = math.unitCount(totalLeaves, config.spread);
            if (destination < 0) return false;
            if (destination >= count) { requestChapter(1, true); return false; }
            scrollToUnit(destination, behavior);
            return true;
        },
        seekUnit: function (localUnit, behavior) {
            var chapter = math.chapterForLeaf(pageMap, currentUnit() * spreadSize());
            if (!chapter) return false;
            var unitCount = math.unitCount(chapter.leafCount, config.spread);
            var target = Math.min(Math.max(Number(localUnit) || 0, 0), unitCount - 1);
            scrollToUnit(Math.floor(chapter.startLeaf / spreadSize()) + target, behavior || "instant");
            return true;
        },
        seekPercent: function (percent) {
            var chapter = math.chapterForLeaf(pageMap, currentUnit() * spreadSize());
            if (!chapter) return false;
            var local = math.unitFromProgress(Number(percent) / 100, math.unitCount(chapter.leafCount, config.spread));
            scrollToUnit(Math.floor(chapter.startLeaf / spreadSize()) + local, "instant");
            return true;
        },
        revealElement: function (element) {
            if (!element || !element.getBoundingClientRect) return false;
            var leaf = leafForRect(element.getBoundingClientRect(), container.getBoundingClientRect(), logicalOffset(), viewport() / spreadSize());
            scrollToUnit(Math.floor(leaf / spreadSize()), "instant");
            return true;
        },
        reflow: function () { scheduleReflow(); return true; },
        prepareSilentTurn: function (preserveViewport) {
            var chapter = math.chapterForLeaf(pageMap, currentUnit() * spreadSize());
            silentSourceUnit = currentUnit();
            restore = preserveViewport === false || !chapter
                ? null
                : { chapterId: chapter.chapterId, progress: lastChapterProgress || 0 };
            silent = true;
            return true;
        },
        finishSilentTurn: function (reflow, persist) {
            if (persist === false && silentSourceUnit != null) {
                scrollToUnit(silentSourceUnit, "instant");
            }
            silentSourceUnit = null;
            silent = false;
            if (reflow === false) emitPosition(persist !== false);
            else scheduleReflow();
            return true;
        },
    };

    container.addEventListener("scroll", onScroll, { passive: true });
    observeLayoutNode(container);
    if (typeof MutationObserver === "function") {
        new MutationObserver(function (mutations) {
            var external = mutations.some(function (mutation) {
                return Array.from(mutation.addedNodes).concat(Array.from(mutation.removedNodes)).some(function (node) {
                    return !node.classList || !node.classList.contains(BLANK_CLASS);
                });
            });
            if (external) scheduleReflow();
        }).observe(container, { childList: true, subtree: true });
    }
    document.addEventListener("load", function (event) {
        if (event.target && event.target.tagName === "IMG") scheduleReflow();
    }, true);
    if (document.fonts) {
        document.fonts.ready.then(scheduleReflow);
        if (document.fonts.addEventListener) document.fonts.addEventListener("loadingdone", scheduleReflow);
    }
    layout._install(driver);
})();
