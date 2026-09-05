// Shared facade and pure math for novel reader layout modes.
(function () {
    "use strict";

    window.__TSUNDOKU_OBJECT_NAME__ = window.__TSUNDOKU_OBJECT_NAME__ || {};
    var root = window.__TSUNDOKU_OBJECT_NAME__;
    root.runtime = root.runtime || {};
    if (root.runtime.readerLayout) return;

    function clamp(value, low, high) {
        return Math.min(Math.max(value, low), high);
    }

    var math = {
        unitCount: function (leafCount, spread) {
            var leavesPerUnit = spread === "double" ? 2 : 1;
            return Math.max(1, Math.ceil(Math.max(0, leafCount) / leavesPerUnit));
        },
        unitFromOffset: function (offset, viewport, count) {
            if (!(viewport > 0) || count < 2) return 0;
            return clamp(Math.round(Math.max(0, offset) / viewport), 0, count - 1);
        },
        unitFromProgress: function (progress, count) {
            if (count < 2) return 0;
            return clamp(Math.round(clamp(progress, 0, 1) * (count - 1)), 0, count - 1);
        },
        progressForUnit: function (unit, count) {
            if (count < 2) return 1;
            return clamp(unit, 0, count - 1) / (count - 1);
        },
        leafRange: function (unit, leafCount, spread) {
            var leavesPerUnit = spread === "double" ? 2 : 1;
            var first = clamp(unit, 0, math.unitCount(leafCount, spread) - 1) * leavesPerUnit;
            return {
                firstLeaf: first + 1,
                lastLeaf: Math.min(first + leavesPerUnit, Math.max(1, leafCount)),
            };
        },
        logicalOffset: function (scrollLeft, direction) {
            return direction === "rtl" ? -scrollLeft : scrollLeft;
        },
        physicalOffset: function (offset, direction) {
            return direction === "rtl" ? -offset : offset;
        },
        chapterForLeaf: function (pageMap, leaf) {
            if (!pageMap.length) return null;
            var match = pageMap[0];
            for (var i = 0; i < pageMap.length; i++) {
                if (leaf < pageMap[i].startLeaf) break;
                match = pageMap[i];
                if (leaf < match.startLeaf + match.leafCount) break;
            }
            return match;
        },
    };

    var implementation = null;
    var pendingConfig = null;
    var layout = {
        enabled: false,
        _math: math,
        _install: function (value) {
            implementation = value;
            if (pendingConfig) implementation.configure(pendingConfig);
        },
        configure: function (config) {
            pendingConfig = config || {};
            layout.enabled = !!pendingConfig.enabled;
            if (implementation) implementation.configure(pendingConfig);
        },
    };

    ["moveBy", "seekUnit", "seekPercent", "revealElement", "reflow", "prepareSilentTurn", "finishSilentTurn"]
        .forEach(function (name) {
            layout[name] = function () {
                if (!implementation || typeof implementation[name] !== "function") return false;
                return implementation[name].apply(implementation, arguments);
            };
        });

    root.runtime.readerLayout = layout;
})();
