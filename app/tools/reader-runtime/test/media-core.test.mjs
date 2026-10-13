import assert from 'node:assert/strict';
import test from 'node:test';
import { DIRECT_PLAYERS, buildDashSource, buildHlsSource } from '../src/media/direct.ts';
import { VIDEO_FRACTION_KEY, combineResumeProgress, readFractions, writeFraction } from '../src/media/progress.ts';
import { toWebVtt } from '../src/media/subtitles.ts';
import { DownloadSink, writeResponseToSink } from '../src/media/download-sink.ts';
import { awaitPlayerElements } from '../src/media/player-elements.ts';
import { reportPlayerFailure } from '../src/media/errors.ts';
import { PlaybackProgress } from '../src/media/playback-progress.ts';

test('direct dispatch covers only the four chapter directive types', () => {
  assert.deepEqual(DIRECT_PLAYERS, {
    m3u8: 'playHls',
    mpd: 'playDash',
    'video-file': 'playDirect',
    iframe: 'playIframe',
  });
});

test('playback forces fragment capture off even when plugin config asks for it', () => {
  // Upstream HLS vendor option, paired with the bundled fragment-capture patch.
  const source = buildHlsSource('https://media.test/a.m3u8', { debug: true, tsundokuCaptureFragments: true });
  assert.equal(source.type, 'application/vnd.apple.mpegurl');
  assert.equal(source.engine.hlsJs.tsundokuCaptureFragments, false);
  assert.equal(source.engine.hlsJs.startFragPrefetch, undefined);
});

test('dash settings and protection data pass through without a default robustness', () => {
  const settings = { streaming: { retryAttempts: { MPD: 3 } } };
  const protectionData = { 'com.widevine.alpha': { serverURL: 'https://license.test' } };
  assert.deepEqual(buildDashSource(settings, protectionData), {
    settings,
    protectionData,
  });
  assert.equal(JSON.stringify(buildDashSource(settings, protectionData)).includes('robustness'), false);
});

test('stored fractional resume progress restores precision without exceeding one percent', () => {
  const fractions = { '/opaque/chapter': 0.7345, bad: 1.4, negative: -0.2 };
  assert.equal(combineResumeProgress(42, '/opaque/chapter', fractions), 42.7345);
  assert.equal(combineResumeProgress(42, 'bad', fractions), 42);
  assert.equal(combineResumeProgress(42, 'negative', fractions), 42);
  assert.equal(combineResumeProgress(100, '/opaque/chapter', fractions), 100);
});

test('fraction storage preserves other chapter entries and drops invalid maps', () => {
  const values = new Map([[VIDEO_FRACTION_KEY, '{"kept":0.25}']]);
  const storage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  };
  assert.deepEqual(readFractions(storage), { kept: 0.25 });
  writeFraction(storage, 'current', 0.123456);
  assert.equal(values.get(VIDEO_FRACTION_KEY), '{"kept":0.25,"current":0.1235}');
  writeFraction(storage, 'current', 0);
  assert.equal(values.get(VIDEO_FRACTION_KEY), '{"kept":0.25}');
  values.set(VIDEO_FRACTION_KEY, '[]');
  assert.deepEqual(readFractions(storage), {});
});

test('playback emits opaque chapter progress and next navigation with fractional resume storage', () => {
  const events = [];
  const values = new Map();
  const video = new EventTarget();
  Object.assign(video, { currentTime: 5.123, duration: 10, error: null });
  const progress = new PlaybackProgress({
    host: {
      chapter: { id: 'chapter-id', path: '/opaque//path#part', progress: 0 },
      nextChapter: { name: 'Next' },
      emit: (name, payload) => events.push([name, payload]),
    },
    directive: { path: '/opaque//path#part', progress: 0 },
    storage: {
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => values.set(key, value),
    },
    onResume: () => undefined,
    onDismissed: () => false,
    log: () => undefined,
  });
  progress.attach(video, null, (message) => { throw new Error(message); });
  video.dispatchEvent(new Event('timeupdate'));
  assert.equal(values.get(VIDEO_FRACTION_KEY), '{"/opaque//path#part":0.23}');
  video.dispatchEvent(new Event('ended'));
  assert.deepEqual(events, [
    ['reader.saveVideoProgress', { chapterId: 'chapter-id', path: '/opaque//path#part', progress: 51 }],
    ['reader.saveVideoProgress', { chapterId: 'chapter-id', path: '/opaque//path#part', progress: 100 }],
    ['navigation.next', {}],
  ]);
  assert.equal(values.get(VIDEO_FRACTION_KEY), '{}');
  progress.dispose();
});

test('subtitle conversion adds WebVTT header and normalizes SubRip decimal commas', () => {
  assert.equal(toWebVtt('\uFEFF1\n00:00:01,250 --> 00:00:02,500\nhello'),
    'WEBVTT\n\n1\n00:00:01.250 --> 00:00:02.500\nhello');
  assert.equal(toWebVtt('  WEBVTT\n\n00:00:01.000 --> 00:00:02.000'),
    'WEBVTT\n\n00:00:01.000 --> 00:00:02.000');
});

test('download progress advances only after each streamed chunk is acknowledged', async () => {
  const sequence = [];
  const sink = new DownloadSink({
    fetchNative: async (input, init) => {
      const route = new URL(String(input));
      if (init?.method === 'PUT') sequence.push('ack-chunk');
      else if (init?.method === 'POST' && !route.search) sequence.push('commit');
      else if (route.searchParams.has('container')) sequence.push('ready');
      else sequence.push(route);
      return new Response(null, { status: 200 });
    },
    emit: (name, payload) => {
      if (name === 'media.download.progress') sequence.push(`progress-${payload.current}`);
    },
  }, 'https://127.0.0.1:4321/download');
  await sink.ready('mp4');
  const response = new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new Uint8Array([1, 2]));
      controller.enqueue(new Uint8Array([3, 4]));
      controller.close();
    },
  }), { headers: { 'content-length': '4' } });
  await writeResponseToSink(response, sink, 4);
  assert.deepEqual(sequence, [
    'ready', 'ack-chunk', 'progress-50', 'ack-chunk', 'progress-100', 'commit',
  ]);
});

test('late CDN custom elements wait for registration and missing ones time out', async () => {
  let release;
  const registry = {
    get: (tag) => tag === 'video' ? class Video {} : undefined,
    whenDefined: () => new Promise((resolve) => { release = resolve; }),
  };
  const pending = awaitPlayerElements(registry, ['video', 'media-i18n'], 100);
  release();
  await pending;
  await assert.rejects(awaitPlayerElements({
    get: () => undefined,
    whenDefined: () => new Promise(() => {}),
  }, ['dash-video'], 1), /dash-video is unavailable/);
});

test('player failures select one mode channel and preserve the iframe download code', async () => {
  const playback = [];
  await reportPlayerFailure({
    mode: 'playback',
    emit: (name, payload) => playback.push([name, payload]),
    fetchNative: async () => new Response(null, { status: 200 }),
  }, 'Video failed');
  assert.deepEqual(playback, [['reader.error', { message: 'Video failed' }]]);

  const download = [];
  await reportPlayerFailure({
    mode: 'download',
    downloadEndpoint: 'https://127.0.0.1:4321/download',
    emit: (name, payload) => download.push([name, payload]),
    fetchNative: async () => new Response(null, { status: 200 }),
  }, 'Iframe video downloads are not supported', 'iframe');
  assert.deepEqual(download, [
    ['media.download.error', { code: 'iframe', message: 'Iframe video downloads are not supported' }],
  ]);
});

test('cancellation while waiting on the next chunk never commits a partial download', async () => {
  const calls = [];
  const controller = new AbortController();
  let cancelled = false;
  const sink = new DownloadSink({
    fetchNative: async (_input, init) => {
      calls.push(init?.method);
      return new Response(null, { status: 200 });
    },
    emit: () => undefined,
  }, 'https://127.0.0.1:4321/download', controller.signal);
  const response = new Response(new ReadableStream({
    start(stream) { stream.enqueue(new Uint8Array([1, 2])); },
    cancel() { cancelled = true; },
  }));
  const writing = writeResponseToSink(response, sink, 4, controller.signal);
  await new Promise((resolve) => setTimeout(resolve, 0));
  controller.abort();
  await assert.rejects(writing, { name: 'AbortError' });
  assert.equal(cancelled, true);
  assert.equal(calls.includes('POST'), false);
});

for (const [label, disableProgress, duration] of [['disabled', true, 10], ['live', false, Infinity]]) {
  test(`${label} video keeps native and fractional progress at zero`, () => {
    const events = [];
    const values = new Map([[VIDEO_FRACTION_KEY, '{"/video":0.5}']]);
    const video = Object.assign(new EventTarget(), { currentTime: 5, duration });
    const progress = new PlaybackProgress({
      host: { chapter: { id: '1' }, emit: (name, payload) => events.push([name, payload]) },
      directive: { path: '/video', progress: 50, disableProgress },
      storage: { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value) },
      onResume: () => assert.fail('Must not resume disabled/live video'),
      onDismissed: () => false, log: () => {},
    });
    progress.attach(video, null, assert.fail);
    for (const name of ['loadedmetadata', 'timeupdate', 'ended']) video.dispatchEvent(new Event(name));
    assert.ok(events.length > 0);
    assert.ok(events.every(([name, payload]) => name === 'reader.saveVideoProgress' && payload.progress === 0));
    assert.equal(values.get(VIDEO_FRACTION_KEY), '{}');
    progress.dispose();
  });
}
