// LNReader-compatible image viewer, floating TTS controller, and bionic reading.
//
// Replaces:
//   __BIONIC_ENABLED__       - true / false
//   __TTS_STATE_EVENT__      - runtime event name
//   __TTS_CONTROL_LABEL__ / __IMAGE_CLOSE_LABEL__   - JSON-quoted localized labels

(function () {
    window.Tsundoku = window.Tsundoku || {};
    var runtime = window.Tsundoku.runtime = window.Tsundoku.runtime || {};
    if (runtime.readerUi) {
        runtime.readerUi.setBionic(__BIONIC_ENABLED__);
        runtime.readerUi.refresh();
        return;
    }

    var volumeIcon =
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -960 960 960"><path d="M552-152v-75q86-23 139-93.5T744-480q0-89-53.5-158.5T552-734v-75q116 25 190 117t74 211q0 119-73.5 211.5T552-152ZM144-385v-192h144l192-192v576L288-385H144Zm408 55v-302q45 20 70.5 61t25.5 90q0 49-25.5 89.5T552-330Z"/></svg>';
    var pauseIcon =
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -960 960 960"><path d="M564-228v-504h168v504H564Zm-336 0v-504h168v504H228Z"/></svg>';
    var resumeIcon =
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -960 960 960"><path d="M240-240v-480h72v480h-72Zm144 0 384-240-384-240v480Z"/></svg>';

    var originalViewport = null;
    var modal = document.createElement('div');
    modal.id = 'Image-Modal';
    modal.setAttribute('role', 'dialog');
    modal.setAttribute('aria-modal', 'true');
    modal.setAttribute('aria-label', __IMAGE_CLOSE_LABEL__);
    var modalImage = document.createElement('img');
    modalImage.id = 'Image-Modal-img';
    modal.appendChild(modalImage);
    var readerUi = document.getElementById('reader-ui') || document.body;
    readerUi.appendChild(modal);

    function viewportMeta() {
        var meta = document.querySelector('meta[name="viewport"]');
        if (!meta) {
            meta = document.createElement('meta');
            meta.name = 'viewport';
            document.head.appendChild(meta);
        }
        return meta;
    }

    function showImage(image) {
        if (!image || !image.currentSrc && !image.src) return;
        originalViewport = viewportMeta().getAttribute('content');
        viewportMeta().setAttribute(
            'content',
            'width=device-width, initial-scale=1.0, maximum-scale=10'
        );
        modalImage.src = image.currentSrc || image.src;
        modalImage.alt = image.alt || '';
        modal.classList.add('show');
        if (window.Android && window.Android.setReaderUiModalOpen) {
            window.Android.setReaderUiModalOpen(true);
        }
    }

    function hideImage() {
        if (!modal.classList.contains('show')) return;
        if (window.Android && window.Android.suppressReaderGestures) {
            window.Android.suppressReaderGestures();
        }
        modal.classList.remove('show');
        modalImage.removeAttribute('src');
        var viewport = viewportMeta();
        if (originalViewport === null) {
            viewport.removeAttribute('content');
        } else {
            viewport.setAttribute('content', originalViewport);
        }
        originalViewport = null;
        if (window.Android && window.Android.setReaderUiModalOpen) {
            window.Android.setReaderUiModalOpen(false);
        }
    }

    modal.addEventListener('click', function (event) {
        if (event.target !== modalImage) {
            event.stopPropagation();
            hideImage();
        }
    });
    document.addEventListener('contextmenu', function (event) {
        var image = event.target instanceof HTMLImageElement ? event.target : null;
        if (!image) return;
        event.preventDefault();
        if (modal.classList.contains('show')) hideImage(); else if (image !== modalImage) showImage(image);
    });
    window.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') hideImage();
    });

    var controller = document.createElement('div');
    controller.id = 'TTS-Controller';
    var ttsButton = document.createElement('button');
    ttsButton.type = 'button';
    ttsButton.setAttribute('aria-label', __TTS_CONTROL_LABEL__);
    ttsButton.setAttribute('title', __TTS_CONTROL_LABEL__);
    ttsButton.innerHTML = volumeIcon;
    controller.appendChild(ttsButton);
    readerUi.appendChild(controller);

    var pointerStart = null;
    var hoverElement = null;
    var readableSelector = 'p,li,blockquote,h1,h2,h3,h4,h5,h6,pre';

    function readableElements() {
        var elements = Array.from(document.querySelectorAll(readableSelector)).filter(function (element) {
            return !element.closest('#Image-Modal,#TTS-Controller,#next-chapter-btn-container') &&
                !!element.innerText && element.innerText.trim().length > 0;
        });
        if (!elements.length) {
            elements = Array.from(document.body.children).filter(function (element) {
                return element !== modal && element !== controller &&
                    element.id !== 'next-chapter-btn-container' &&
                    !!element.innerText && element.innerText.trim().length > 0;
            });
        }
        return elements;
    }

    function clearHighlight() {
        if (hoverElement) hoverElement.classList.remove('highlight');
        hoverElement = null;
    }

    function updateHover(x, y) {
        var readable = readableElements();
        var elements = document.elementsFromPoint(x, y).reverse();
        var next = elements.find(function (element) {
            return element !== controller && element !== ttsButton && readable.indexOf(element) >= 0;
        }) || null;
        if (next === hoverElement) return;
        clearHighlight();
        hoverElement = next;
        if (hoverElement) hoverElement.classList.add('highlight');
    }

    controller.addEventListener('pointerdown', function (event) {
        event.preventDefault();
        event.stopPropagation();
        if (window.Android && window.Android.suppressReaderGestures) {
            window.Android.suppressReaderGestures();
        }
        pointerStart = { id: event.pointerId, x: event.clientX, y: event.clientY, moved: false };
        controller.classList.add('active');
        controller.style.transition = '';
        controller.setPointerCapture(event.pointerId);
    });
    controller.addEventListener('pointermove', function (event) {
        if (!pointerStart || pointerStart.id !== event.pointerId) return;
        event.preventDefault();
        event.stopPropagation();
        if (Math.hypot(event.clientX - pointerStart.x, event.clientY - pointerStart.y) >= 8) {
            pointerStart.moved = true;
        }
        if (!pointerStart.moved) return;
        controller.style.left = event.clientX + 'px';
        controller.style.top = event.clientY + 'px';
        updateHover(event.clientX, event.clientY);
    });
    controller.addEventListener('pointerup', function (event) {
        if (!pointerStart || pointerStart.id !== event.pointerId) return;
        var moved = pointerStart.moved;
        pointerStart = null;
        controller.style.transition = '1s';
        controller.classList.remove('active');
        controller.style.left = '20px';
        if (moved) {
            var top = event.clientY < 120 ? 120 : event.clientY;
            if (top + 120 > window.innerHeight) top = window.innerHeight - 120;
            controller.style.top = top + 'px';
            var index = readableElements().indexOf(hoverElement);
            if (index >= 0 && window.Android && window.Android.startTtsAtParagraph) {
                window.Android.startTtsAtParagraph(index);
            }
        } else if (window.Android && window.Android.toggleTts) {
            window.Android.toggleTts();
        }
        clearHighlight();
    });
    controller.addEventListener('pointercancel', function () {
        pointerStart = null;
        controller.classList.remove('active');
        controller.style.left = '20px';
        clearHighlight();
    });

    function updateTtsState() {
        var state = runtime.ttsState || 'stopped';
        ttsButton.innerHTML = state === 'playing' ? pauseIcon : state === 'paused' ? resumeIcon : volumeIcon;
    }

    function updateVisibility() {
        controller.classList.toggle('hidden', !!runtime.isEditMode);
    }

    window.addEventListener('__TTS_STATE_EVENT__', updateTtsState);

    var bionicObserver = null;
    var bionicEnabled = false;
    var bionicWord = /(\p{L}|\p{Nd})*\p{L}(\p{L}|\p{Nd})*/gu;
    // LNReader text-vide fixation-point 1.
    var fixationThresholds = [0, 4, 12, 17, 24, 29, 35, 42, 48];
    var excluded = 'script,style,code,pre,textarea,button,[contenteditable="true"],' +
        '#Image-Modal,#TTS-Controller,#next-chapter-btn-container,b[data-tsundoku-bionic]';

    function fixationLength(word) {
        var thresholdIndex = fixationThresholds.findIndex(function (limit) {
            return word.length <= limit;
        });
        return Math.max(
            thresholdIndex === -1 ? word.length - fixationThresholds.length : word.length - thresholdIndex,
            0
        );
    }

    function applyBionic(root) {
        if (!bionicEnabled || runtime.isEditMode || !root) return;
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        var nodes = [];
        while (walker.nextNode()) {
            var node = walker.currentNode;
            if (node.nodeValue.trim() && node.parentElement && !node.parentElement.closest(excluded)) {
                nodes.push(node);
            }
        }
        nodes.forEach(function (node) {
            var text = node.nodeValue;
            bionicWord.lastIndex = 0;
            if (!bionicWord.test(text)) return;
            bionicWord.lastIndex = 0;
            var fragment = document.createDocumentFragment();
            var last = 0;
            var match;
            while ((match = bionicWord.exec(text)) !== null) {
                if (match.index > last) fragment.appendChild(document.createTextNode(text.slice(last, match.index)));
                var word = match[0];
                var strongLength = fixationLength(word);
                if (strongLength > 0) {
                    var strong = document.createElement('b');
                    strong.setAttribute('data-tsundoku-bionic', '');
                    strong.textContent = word.slice(0, strongLength);
                    fragment.appendChild(strong);
                }
                fragment.appendChild(document.createTextNode(word.slice(strongLength)));
                last = match.index + word.length;
            }
            if (last < text.length) fragment.appendChild(document.createTextNode(text.slice(last)));
            node.replaceWith(fragment);
        });
    }

    function removeBionic() {
        document.querySelectorAll('b[data-tsundoku-bionic]').forEach(function (element) {
            element.replaceWith(document.createTextNode(element.textContent || ''));
        });
        document.body.normalize();
    }

    function setBionic(enabled) {
        bionicEnabled = !!enabled && !runtime.isEditMode;
        if (bionicObserver) {
            bionicObserver.disconnect();
            bionicObserver = null;
        }
        if (!bionicEnabled) {
            removeBionic();
            return;
        }
        applyBionic(document.body);
        bionicObserver = new MutationObserver(function (mutations) {
            bionicObserver.disconnect();
            mutations.forEach(function (mutation) {
                mutation.addedNodes.forEach(function (node) {
                    if (node.nodeType === Node.TEXT_NODE) {
                        applyBionic(node.parentNode);
                    } else if (node.nodeType === Node.ELEMENT_NODE && !node.matches('b[data-tsundoku-bionic]')) {
                        applyBionic(node);
                    }
                });
            });
            bionicObserver.observe(document.body, { childList: true, subtree: true });
        });
        bionicObserver.observe(document.body, { childList: true, subtree: true });
    }

    runtime.readerUi = {
        refresh: function () {
            updateTtsState();
            updateVisibility();
        },
        setBionic: setBionic,
        closeImage: hideImage,
    };
    setBionic(__BIONIC_ENABLED__);
    runtime.readerUi.refresh();
})();
