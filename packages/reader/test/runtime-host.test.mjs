import assert from 'node:assert/strict';
import test from 'node:test';
import { createRuntimeHost } from '../src/runtime/host.ts';

class TestTransport {
  onmessage = null;
  sent = [];

  postMessage(message) {
    this.sent.push(JSON.parse(message));
  }

  receive(envelope) {
    this.onmessage?.({ data: JSON.stringify(envelope) });
  }
}

const config = {
  documentId: 'doc-fetch',
  mode: 'reader',
  novel: { id: 'n1', name: 'Novel', path: '/novel' },
  chapter: { id: 'c1', name: 'Chapter', path: '/chapter', progress: 0 },
  nextChapter: null,
  strings: {},
  autoSaveInterval: 5000,
  runtime: {
    editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false,
    menuVisible: false, immersive: false, ttsState: 'stopped', loadingChapter: false,
    loadingNext: false, noMoreChapters: false,
  },
  chapters: [],
};

function makeHost(transport, { fetch = globalThis.fetch, proxyEndpoint = 'https://reader.test/proxy' } = {}) {
  const view = { fetch, Request: globalThis.Request, Headers: globalThis.Headers };
  const root = { ownerDocument: { defaultView: view } };
  const hostConfig = { ...config, proxyEndpoint };
  return createRuntimeHost({ root, config: hostConfig, transport });
}

test('proxied fetch forwards headers and preserves native Response streaming', async () => {
  const transport = new TestTransport();
  let observedUrl;
  let observedInit;
  const response = new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('chapter'));
      controller.close();
    },
  }), { status: 206, headers: { 'content-type': 'text/plain', 'x-source': 'ok' } });
  const host = makeHost(transport, {
    fetch: async (input, init) => {
      observedUrl = String(input);
      observedInit = init;
      return response;
    },
  });
  const controller = new AbortController();
  const body = new Blob(['request-body']);
  const responsePromise = host.fetch('https://source.test/chapter?x=1', {
    method: 'POST',
    headers: { Referer: 'https://source.test/', 'X-Plugin': 'reader', 'Content-Type': 'application/octet-stream' },
    body,
    signal: controller.signal,
  });
  assert.equal(observedUrl, `https://reader.test/proxy?url=${encodeURIComponent('https://source.test/chapter?x=1')}`);
  assert.equal(observedInit.method, 'POST');
  assert.equal(observedInit.body, body);
  assert.equal(observedInit.signal, controller.signal);
  assert.deepEqual(Array.from(observedInit.headers.entries()), [
    ['x-ln-forward-header-content-type', 'application/octet-stream'],
    ['x-ln-forward-header-referer', 'https://source.test/'],
    ['x-ln-forward-header-x-plugin', 'reader'],
  ]);
  assert.equal(await responsePromise, response);
  assert.equal(response.status, 206);
  assert.equal(response.headers.get('x-source'), 'ok');
  assert.equal(await response.text(), 'chapter');
  assert.equal(transport.sent.length, 0);
  host.dispose();
});

test('Request input keeps method and body while forwarding only namespaced headers', async () => {
  const transport = new TestTransport();
  let observedRequest;
  const host = makeHost(transport, {
    fetch: async (input) => {
      observedRequest = input;
      return new Response('ok');
    },
  });
  const input = new Request('https://source.test/video', {
    method: 'POST',
    headers: { Authorization: 'source-token', 'X-Trace': 'trace-1' },
    body: 'binary boundary payload',
  });
  await host.fetch(input);

  assert.ok(observedRequest instanceof Request);
  assert.equal(observedRequest.url, `https://reader.test/proxy?url=${encodeURIComponent(input.url)}`);
  assert.equal(observedRequest.method, 'POST');
  assert.deepEqual(Array.from(observedRequest.headers.entries()), [
    ['x-ln-forward-header-authorization', 'source-token'],
    ['x-ln-forward-header-content-type', 'text/plain;charset=UTF-8'],
    ['x-ln-forward-header-x-trace', 'trace-1'],
  ]);
  assert.equal(await observedRequest.text(), 'binary boundary payload');
  host.dispose();
});

test('fetchNative bypasses the reader proxy', async () => {
  const transport = new TestTransport();
  let observedUrl;
  const host = makeHost(transport, {
    fetch: async (input) => {
      observedUrl = String(input);
      return new Response('local');
    },
  });
  const response = await host.fetchNative('blob:https://reader.test/resource');
  assert.equal(observedUrl, 'blob:https://reader.test/resource');
  assert.equal(await response.text(), 'local');
  assert.equal(transport.sent.length, 0);
  host.dispose();
});

test('aborting a proxied fetch forwards the same signal and rejects with AbortError', async () => {
  const transport = new TestTransport();
  let receivedSignal;
  const host = makeHost(transport, {
    fetch: (_input, init) => {
      receivedSignal = init.signal;
      return new Promise((_resolve, reject) => init.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true }));
    },
  });
  const controller = new AbortController();
  const pending = host.fetch('https://source.test/slow', { signal: controller.signal });
  controller.abort();

  await assert.rejects(pending, { name: 'AbortError' });
  assert.equal(receivedSignal, controller.signal);
  assert.equal(transport.sent.length, 0);
  host.dispose();
});

test('disabled proxy reports actionable reader error and rejects', async () => {
  const transport = new TestTransport();
  const host = makeHost(transport, { proxyEndpoint: '' });
  await host.start();
  await assert.rejects(host.fetch('https://source.test/chapter'), /local proxy is disabled/i);
  assert.deepEqual(transport.sent.map(({ kind, method }) => [kind, method]), [
    ['boot', undefined],
    ['ready', undefined],
    ['event', 'reader.error'],
  ]);
  assert.equal(transport.sent[2].payload.message, 'This plugin cannot use window.nekori.fetch because the local proxy is disabled. Go to Settings > Advanced > Novel WebView, enable Allow local proxy API, then reopen the reader.');
  host.dispose();
});

test('document configure and patch update the public context and emit local state changes', async () => {
  const transport = new TestTransport();
  const host = makeHost(transport);
  const changes = [];
  host.events.subscribe('document.stateChanged', (payload) => changes.push(payload));
  transport.receive({
    protocolVersion: 1,
    documentId: config.documentId,
    kind: 'command',
    requestId: 'config-1',
    method: 'document.configure',
    payload: {
      ...config,
      context: { sourceBaseUrl: 'https://source.test/novel' },
      chapter: { id: 'chapter-2', name: 'Next', path: '//raw/#path', progress: 0 },
      nextChapter: { id: 'chapter-3', name: 'Following', path: '/three', progress: 0 },
      chapters: [
        { id: 'chapter-2', name: 'Next', path: '//raw/#path', progress: 0 },
        { id: 'chapter-3', name: 'Following', path: '/three', progress: 0 },
      ],
      runtime: { ...config.runtime, editing: true, infinite: true, menuVisible: true, ttsState: 'paused' },
    },
  });
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(host.context.chapter.id, 'chapter-2');
  assert.equal(host.context.chapter.path, '//raw/#path');
  assert.equal(host.context.nextChapter.id, 'chapter-3');
  assert.equal(host.context.runtime.editing, true);
  assert.equal(host.context.sourceBaseUrl, 'https://source.test/novel');

  transport.receive({
    protocolVersion: 1,
    documentId: config.documentId,
    kind: 'command',
    requestId: 'patch-1',
    method: 'document.patchState',
    payload: { patch: { menuVisible: false, loadingNext: true }, event: 'menuvisibilitychange' },
  });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(host.context.runtime.menuVisible, false);
  assert.equal(host.context.runtime.loadingNext, true);
  assert.equal(changes.length, 2);
  assert.equal(changes[1].event, 'menuvisibilitychange');
  host.dispose();
});
