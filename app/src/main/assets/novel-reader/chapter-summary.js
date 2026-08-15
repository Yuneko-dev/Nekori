// Chapter summary card, rendered inside an open shadow root.
//
// The shadow boundary is doing real work, not decoration: the selection-to-paragraph mapping in
// NovelWebViewViewer counts `chapterEl.querySelectorAll('p, li, …')`, and the bionic MutationObserver
// walks document.body. Neither crosses into a shadow root, so the summary cannot shift a paragraph
// index or get bionic-bolded. Native drives it; the card only reports which button was pressed.
(function () {
    if (window.__tsundokuSummary) return;

    var HOST_TAG = "tsundoku-chapter-summary";
    var CSS_HREF = "https://tsundoku.reader/assets/chapter-summary.css";

    function chapterRoot(chapterId) {
        return (
            document.querySelector('tsundoku-chapter[data-chapter-id="' + chapterId + '"]') ||
            document.getElementById("LNReader-chapter")
        );
    }

    function existingHost(chapterId) {
        var root = chapterRoot(chapterId);
        return root ? root.querySelector(":scope > " + HOST_TAG) : null;
    }

    function createHost(chapterId) {
        var root = chapterRoot(chapterId);
        if (!root) return null;
        var host = document.createElement(HOST_TAG);
        host.setAttribute("data-chapter-id", String(chapterId));
        var shadow = host.attachShadow({ mode: "open" });
        var link = document.createElement("link");
        link.rel = "stylesheet";
        link.href = CSS_HREF;
        var card = document.createElement("div");
        card.className = "card";
        card.appendChild(el("div", "head", [el("span", "title", [])]));
        card.appendChild(el("div", "body", []));
        card.appendChild(el("div", "actions", []));
        shadow.appendChild(link);
        shadow.appendChild(card);
        root.insertBefore(host, root.firstChild);
        return host;
    }

    function el(tag, className, children) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        for (var i = 0; i < children.length; i++) node.appendChild(children[i]);
        return node;
    }

    // Model output only ever becomes text nodes: no HTML, no markdown, nothing parsed. A blank line
    // starts a new paragraph, which is exactly the contract the summary prompt asks for.
    function renderParagraphs(container, text) {
        container.textContent = "";
        var blocks = String(text == null ? "" : text).split(/\n\s*\n/);
        for (var i = 0; i < blocks.length; i++) {
            var block = blocks[i].trim();
            if (!block) continue;
            var p = document.createElement("p");
            p.textContent = block;
            container.appendChild(p);
        }
        if (!container.firstChild) container.textContent = String(text || "").trim();
    }

    function button(label, action, chapterId) {
        var node = document.createElement("button");
        node.type = "button";
        node.textContent = label;
        node.addEventListener("click", function () {
            if (window.Android && window.Android.onChapterSummaryAction) {
                window.Android.onChapterSummaryAction(String(chapterId), action);
            }
        });
        return node;
    }

    window.__tsundokuSummary = {
        // state: "loading" | "ready" | "failed". `labels` carries the localized strings so the
        // asset never has to know about the app's i18n.
        render: function (chapterId, state, text, labels) {
            var host = existingHost(chapterId) || createHost(chapterId);
            if (!host) return;
            var shadow = host.shadowRoot;
            var body = shadow.querySelector(".body");
            var actions = shadow.querySelector(".actions");
            shadow.querySelector(".title").textContent = labels.title || "";

            body.className = state === "ready" ? "body" : "body status";
            if (state === "ready") {
                renderParagraphs(body, text);
            } else {
                body.textContent = state === "loading" ? labels.loading || "" : text || "";
            }

            actions.textContent = "";
            if (state === "loading") {
                actions.appendChild(button(labels.cancel, "cancel", chapterId));
            } else {
                actions.appendChild(button(labels.regenerate, "regenerate", chapterId));
            }
            actions.appendChild(button(labels.close, "close", chapterId));
        },

        // A second tap on the menu item must not restart a finished summary; it just goes to it.
        focus: function (chapterId) {
            var host = existingHost(chapterId);
            if (!host) return false;
            host.scrollIntoView({ behavior: "smooth", block: "start" });
            return true;
        },

        remove: function (chapterId) {
            var host = existingHost(chapterId);
            if (host && host.parentNode) host.parentNode.removeChild(host);
        },
    };
})();
