import assert from 'node:assert/strict';
import test from 'node:test';
import { createRuntimeSession } from '../src/runtime/session.ts';

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
  protocolVersion: 1,
  documentId: 'doc-1',
  context: { sessionId: 'session-1' },
};

test('boot waits for initialization before ready is sent', async () => {
  const transport = new TestTransport();
  const session = createRuntimeSession(config, transport);
  let finishInitialization;
  const initialization = new Promise((resolve) => { finishInitialization = resolve; });

  const started = session.start([() => initialization]);
  assert.deepEqual(transport.sent.map(({ kind }) => kind), ['boot']);
  finishInitialization();
  await started;

  assert.deepEqual(transport.sent.map(({ kind }) => kind), ['boot', 'ready']);
});

test('queues startup events until after ready and flushes them in FIFO order', async () => {
  const transport = new TestTransport();
  const session = createRuntimeSession(config, transport);
  session.emit('gesture.ready', {});
  const started = session.start([() => {
    session.emit('layout.positionChanged', {
      chapterId: 'chapter-1', unitIndex: 0, unitCount: 4, firstPage: 1, lastPage: 1, totalPages: 4,
    });
    session.emit('scroll.progressPreview', { progress: 0.25 });
  }]);

  assert.deepEqual(transport.sent.map(({ kind }) => kind), ['boot']);
  await started;
  assert.deepEqual(transport.sent.map(({ kind, method }) => [kind, method]), [
    ['boot', undefined],
    ['ready', undefined],
    ['event', 'gesture.ready'],
    ['event', 'layout.positionChanged'],
    ['event', 'scroll.progressPreview'],
  ]);
});

test('sends reader errors immediately after boot when initialization fails', async () => {
  const transport = new TestTransport();
  const session = createRuntimeSession(config, transport);
  const started = session.start([() => {
    session.emit('gesture.ready', {});
    throw new Error('controller setup failed');
  }]);

  await assert.rejects(started, /controller setup failed/);
  session.emit('reader.error', { message: 'Reader setup failed.' });
  assert.deepEqual(transport.sent.map(({ kind, method }) => [kind, method]), [
    ['boot', undefined],
    ['event', 'reader.error'],
  ]);
  session.dispose();
});

test('disposal drops events queued before native ready', async () => {
  const transport = new TestTransport();
  const session = createRuntimeSession(config, transport);
  session.boot();
  session.emit('gesture.ready', {});
  session.dispose();

  assert.deepEqual(transport.sent.map(({ kind }) => kind), ['boot']);
  assert.throws(() => session.emit('gesture.ready', {}), /disposed/i);
});

test('dispatches only registered native commands and returns a result', async () => {
  const transport = new TestTransport();
  const session = createRuntimeSession(config, transport);
  session.registerCommand('layout.move', (payload) => payload);
  await session.start();

  transport.receive({
    protocolVersion: 1,
    documentId: config.documentId,
    kind: 'command',
    requestId: 'r-1',
    method: 'layout.move',
    payload: { delta: 12 },
  });
  transport.receive({
    protocolVersion: 1,
    documentId: config.documentId,
    kind: 'command',
    requestId: 'r-2',
    method: 'reader.eval',
  });
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(transport.sent.slice(-2).map(({ kind, requestId }) => [kind, requestId]).sort(), [
    ['result', 'r-1'],
    ['result', 'r-2'],
  ].sort());
  const supported = transport.sent.find(({ requestId }) => requestId === 'r-1');
  const unsupported = transport.sent.find(({ requestId }) => requestId === 'r-2');
  assert.deepEqual(supported.payload, { delta: 12 });
  assert.match(unsupported.error.message, /unsupported|unknown/i);
});

test('cancelling a pending request rejects it and sends a cancel envelope', async () => {
  const transport = new TestTransport();
  const session = createRuntimeSession(config, transport);
  const controller = new AbortController();
  const request = session.request('media.requestProtectedPlayback', { url: 'https://example.test' }, controller.signal);
  const requestId = transport.sent.at(-1).requestId;
  controller.abort();

  await assert.rejects(request, { name: 'AbortError' });
  assert.equal(transport.sent.at(-1).kind, 'cancel');
  assert.equal(transport.sent.at(-1).requestId, requestId);
});

test('ignores late replies after cancellation and rejects stale inbound documents', async () => {
  const transport = new TestTransport();
  const session = createRuntimeSession(config, transport);
  const controller = new AbortController();
  const request = session.request('media.requestProtectedPlayback', {}, controller.signal);
  const requestId = transport.sent.at(-1).requestId;
  controller.abort();
  await assert.rejects(request, { name: 'AbortError' });

  transport.receive({
    protocolVersion: 1,
    documentId: config.documentId,
    kind: 'result',
    requestId,
    payload: { ok: true },
  });
  transport.receive({
    protocolVersion: 1,
    documentId: 'old-doc',
    kind: 'event',
    payload: { name: 'reader.error' },
  });

  assert.equal(session.pendingRequestCount, 0);
  assert.equal(session.lastProtocolError?.message.includes('document'), true);
});

test('native cancellation aborts only the matching registered command', async () => {
  const transport = new TestTransport();
  const session = createRuntimeSession(config, transport);
  let receivedSignal;
  session.registerCommand('layout.move', (_payload, signal) => {
    receivedSignal = signal;
    return new Promise(() => {});
  });
  await session.start();
  transport.receive({
    protocolVersion: 1,
    documentId: config.documentId,
    kind: 'command',
    requestId: 'native-1',
    method: 'layout.move',
    payload: { delta: 1 },
  });
  await new Promise((resolve) => setImmediate(resolve));
  transport.receive({
    protocolVersion: 1,
    documentId: config.documentId,
    kind: 'cancel',
    requestId: 'native-1',
  });

  assert.equal(receivedSignal.aborted, true);
  assert.equal(session.pendingRequestCount, 0);
  session.dispose();
});
