// Fixed decorative layer, shared by scrolling and column layouts. No chapter DOM mutations.
(function () {
    'use strict';
    const layer = document.createElement('div');
    layer.id = 'reader-background';
    layer.setAttribute('aria-hidden', 'true');
    document.body.prepend(layer);
    let generation = 0;
    let loadedUrl = '';
    function apply(config) {
        const token = ++generation;
        layer.style.backgroundImage = 'none';
        if (!config.image) {
            loadedUrl = '';
            return;
        }
        const opacity = Math.min(100, Math.max(0, config.opacity ?? 100)) / 100;
        const blur = Math.min(20, Math.max(0, config.blur ?? 0));
        layer.style.backgroundSize = config.size === 'stretch' ? '100% 100%'
            : ['cover', 'contain', 'auto'].includes(config.size) ? config.size : 'cover';
        layer.style.backgroundPosition = ['center', 'top', 'bottom', 'left', 'right', 'top left'].includes(config.position)
            ? config.position : 'center';
        layer.style.backgroundRepeat = config.repeat ? 'repeat' : 'no-repeat';
        layer.style.opacity = opacity;
        layer.style.filter = blur ? `blur(${blur}px)` : 'none';
        layer.style.inset = `${-blur * 3}px`;

        function display() {
            if (token !== generation) return;
            layer.style.backgroundImage = `url(${JSON.stringify(config.image)})`;
        }
        if (loadedUrl === config.image) {
            display();
            return;
        }
        const image = new Image();
        image.onload = function () {
            if (token !== generation) return;
            loadedUrl = config.image;
            display();
        };
        image.onerror = function () {
            if (token !== generation) return;
            loadedUrl = '';
            window.reader?.error(config.errorMessage);
        };
        image.src = config.image;
    }

    window.readerBackground = { apply };
    const config = document.getElementById('reader-background-config');
    if (config) apply(JSON.parse(config.textContent));
})();
