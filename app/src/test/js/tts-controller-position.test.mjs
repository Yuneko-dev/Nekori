import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import test from 'node:test';

const source = readFileSync(new URL('../../main/assets/novel-reader/reader-ui.js', import.meta.url), 'utf8');
const controls = source.slice(source.indexOf('    var positionKey'), source.indexOf('    function updateTtsState'))
    .replaceAll('__TTS_ENABLED__', 'true');

function setup(storage = new Map()) {
    const handlers = {};
    const controller = {
        style: {}, classList: { add() {}, remove() {} }, setPointerCapture() {},
        addEventListener(name, handler) { handlers[name] = handler; },
    };
    let toggles = 0;
    const window = { innerWidth: 400, innerHeight: 800,
        addEventListener(name, handler) { handlers[name] = handler; },
        Android: { toggleTts() { toggles++; } },
    };
    runInNewContext(controls, { controller, window, ttsButton: {}, modal: {},
        document: { querySelectorAll: () => [], elementsFromPoint: () => [], body: { children: [] } },
        localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    });
    const send = (name, x, y) => handlers[name]({ pointerId: 1, clientX: x, clientY: y,
        preventDefault() {}, stopPropagation() {} });
    return { controller, window, handlers, send, toggles: () => toggles };
}

test('TTS docks by release side, restores per-origin storage, and cancels without moving dock', () => {
    const storage = new Map();
    const ui = setup(storage);
    ui.send('pointerdown', 20, 400);
    ui.send('pointermove', 320, 500);
    ui.send('pointerup', 320, 500);
    assert.equal(ui.controller.style.left, 'calc(100% - 20px)');
    assert.equal(ui.controller.style.transform, 'translateX(-100%)');
    assert.equal(ui.controller.style.transition, '1s');
    const restored = setup(storage);
    assert.equal(restored.controller.style.left, 'calc(100% - 20px)');
    assert.equal(restored.controller.style.top, '500px');
    restored.send('pointerdown', 360, 500);
    restored.send('pointermove', 50, 300);
    restored.send('pointercancel', 50, 300);
    assert.equal(restored.controller.style.left, 'calc(100% - 20px)');
    assert.equal(restored.controller.style.top, '500px');
    restored.send('pointerdown', 360, 500);
    restored.send('pointerup', 360, 500);
    assert.equal(restored.toggles(), 1);
    restored.send('pointerdown', 360, 500);
    restored.send('pointermove', 100, 50);
    restored.send('pointerup', 100, 50);
    assert.equal(restored.controller.style.left, '20px');
    assert.equal(restored.controller.style.transform, 'translateX(0)');
    assert.equal(restored.controller.style.transition, '1s');
    assert.equal(restored.controller.style.top, '120px');
    restored.window.innerHeight = 180;
    restored.handlers.resize();
    assert.equal(restored.controller.style.top, '90px');
    assert.equal(setup().controller.style.left, '20px');
});

test('TTS tolerates corrupt or unavailable storage', () => {
    for (const value of ['{', '{"right":true,"top":"bad"}']) {
        assert.equal(setup(new Map([['nekori.tts.position', value]])).controller.style.left, '20px');
    }
    const ui = setup({ get() { throw Error('blocked'); }, set() { throw Error('blocked'); } });
    ui.send('pointerdown', 20, 400);
    ui.send('pointermove', 320, 500);
    ui.send('pointerup', 320, 500);
    assert.equal(ui.controller.style.left, 'calc(100% - 20px)');
});
