import { buildHlsDownloadConfig } from "./direct.ts";
import { DownloadSink } from "./download-sink.ts";
import { watchHlsActivity } from "./hls-activity.ts";
import { boxType, sameBytes, toBytes } from "./hls-fragment-bytes.ts";
import type { HlsConstructor, HlsEngine, HlsEventData, HlsFragment, HlsInitSegment } from "./hls-types.ts";

const FULL_SEGMENT_METHODS = new Set(["AES-128", "AES-256", "AES-256-CTR"]);
const MFHD_SEQUENCE_OFFSET = 20;

export type { HlsConstructor, HlsEngine, HlsEventData, HlsFragment, HlsInitSegment } from "./hls-types.ts";

export async function downloadHls(options: {
  readonly Hls: HlsConstructor;
  readonly document: Document;
  readonly sink: DownloadSink;
  readonly url: string;
  readonly customConfig: Record<string, unknown>;
  readonly debug: boolean;
  readonly signal?: AbortSignal;
  readonly log: (message: string) => void;
}): Promise<void> {
  const { Hls, document, sink, url, customConfig, debug, signal, log } = options;
  const view = document.defaultView;
  if (!view) throw new Error("Reader document has no active window");
  const hls = new Hls(buildHlsDownloadConfig(customConfig, debug));
  const media = document.createElement("video");
  media.hidden = true;
  media.muted = true;
  media.playsInline = true;
  document.body.append(media);
  const abortListener: { remove: () => void } = { remove: () => undefined };

  const stopWatchingActivity = watchHlsActivity(view, hls, sink);

  try {
    await new Promise<void>((resolve, reject) => {
      let fragments: HlsFragment[] | null = null;
      let mapInitSegment: HlsInitSegment | null = null;
      let mediaStarted = false;
      let active: FragmentCapture | null = null;
      let next = 0;
      let settled = false;
      let mode: "fmp4" | "plaintext" | null = null;
      let fmp4Init: Uint8Array | null = null;
      let pendingPayload: Uint8Array | null = null;
      let moofSequence = 0;
      let sinkQueue = Promise.resolve();

      const fail = (error: unknown) => {
        if (settled) return;
        settled = true;
        reject(error instanceof Error ? error : new Error(String(error)));
      };
      const abort = () => fail(new DOMException("The operation was aborted", "AbortError"));
      if (signal?.aborted) {
        abort();
        return;
      }
      if (signal) {
        signal.addEventListener("abort", abort, { once: true });
        abortListener.remove = () => signal.removeEventListener("abort", abort);
      }

      const enqueue = (task: () => Promise<void>) => {
        sinkQueue = sinkQueue.then(() => settled ? undefined : task()).catch(fail);
      };
      const ready = (container: string) => enqueue(() => sink.ready(container));
      const write = (bytes: Uint8Array) => enqueue(() => sink.put(bytes));
      const sameFragment = (left: HlsFragment | undefined, right: HlsFragment | undefined) =>
        left === right || Boolean(left && right && left.level === right.level && left.sn === right.sn);
      const activeFor = (data: HlsEventData) => active && sameFragment(data.frag, active.fragment) ? active : null;
      const advance = (capture: FragmentCapture | null) => {
        if (!capture || settled || capture !== active || !capture.buffered || !capture.written || capture.advancing) return;
        capture.advancing = true;
        void (async () => {
          await sinkQueue;
          if (settled || !fragments) return;
          next += 1;
          sink.progress(next, fragments.length);
          if (next === fragments.length) {
            await sink.commit();
            settled = true;
            resolve();
            return;
          }
          active = null;
          hls.resumeBuffering();
          media.currentTime = fragments[next]?.start ?? 0;
        })().catch(fail);
      };
      const writePlaintext = (payload: Uint8Array) => {
        if (next === 0 && mapInitSegment) {
          if (!mapInitSegment.data) {
            fail(new Error("HLS init segment is unavailable"));
            return;
          }
          write(toBytes(mapInitSegment.data).slice());
        }
        write(payload);
        if (active) {
          active.written = true;
          advance(active);
        }
      };
      const on = (event: string, handler: (data: HlsEventData) => void) => hls.on(event, (_name, data) => {
        if (settled) return;
        try { handler(data); } catch (error) { fail(error); }
      });

      const acceptPayload = (data: HlsEventData) => {
        const fragment = data.frag;
        if (!fragment || fragment.type !== "main" || !data.payload || fragment.sn === "initSegment") return;
        if ((fragment.initSegment || null) !== mapInitSegment) {
          fail(new Error("HLS init segment changes cannot be downloaded"));
          return;
        }
        if (active || !sameFragment(fragment, fragments?.[next])) return;
        active = { fragment, buffered: false, written: false, advancing: false, chunks: 0 };
        hls.pauseBuffering();
        if (mode === "fmp4") return;
        const payload = toBytes(data.payload).slice();
        if (mode) writePlaintext(payload);
        else pendingPayload = payload;
      };

      const events = Hls.Events;
      on(events.FRAG_PARSING_INIT_SEGMENT, (data) => {
        const combined = data.tracks?.video?.tsundokuInitSegment;
        if (mode === "fmp4") {
          if (!sameBytes(combined, fmp4Init)) fail(new Error("HLS track configuration changed mid-stream"));
          return;
        }
        if (mode) return;
        if (combined) {
          mode = "fmp4";
          fmp4Init = combined.slice();
          pendingPayload = null;
          ready("mp4");
          write(fmp4Init);
          return;
        }
        log("HLS remuxer output is not usable as a file, keeping TS");
        mode = "plaintext";
        ready("ts");
        const payload = pendingPayload;
        pendingPayload = null;
        if (payload) writePlaintext(payload);
      });
      on(events.BUFFER_APPENDING, (data) => {
        if (mode !== "fmp4") return;
        const capture = activeFor(data);
        const bytes = data.data;
        if (!capture || !bytes || bytes.byteLength < MFHD_SEQUENCE_OFFSET + 4 || boxType(bytes) !== "moof") return;
        const chunk = bytes.slice();
        new DataView(chunk.buffer).setUint32(MFHD_SEQUENCE_OFFSET, ++moofSequence);
        write(chunk);
        capture.chunks += 1;
      });
      on(events.FRAG_PARSED, (data) => {
        const capture = mode === "fmp4" ? activeFor(data) : null;
        if (!capture || !capture.chunks) return;
        capture.written = true;
        advance(capture);
      });
      on(events.ERROR, (data) => {
        const detail = typeof data.details === "string" ? data.details : data.type;
        if (!data.fatal && detail !== "fragDecryptError") return;
        const status = data.response?.code ? ` (${data.response.code})` : "";
        fail(new Error(`HLS load failed: ${detail || "unknown"}${status}`));
      });
      on(events.FRAG_DECRYPTED, acceptPayload);
      on(events.FRAG_BUFFERED, (data) => {
        const capture = activeFor(data);
        if (!capture) return;
        capture.buffered = true;
        advance(capture);
      });
      on(events.MEDIA_ATTACHED, () => {
        if (mediaStarted || !fragments) return;
        mediaStarted = true;
        hls.startLoad(fragments[0]?.start);
      });
      on(events.MANIFEST_PARSED, (data) => {
        if (data.audioTracks?.some((track) => track.url)) fail(new Error("HLS with separate audio tracks cannot be downloaded"));
      });
      on(events.LEVEL_LOADED, (data) => {
        if (fragments) return;
        hls.stopLoad();
        const level = typeof data.details === "object" ? data.details : undefined;
        if (level?.live) {
          fail(new Error("Live HLS cannot be downloaded as a complete video"));
          return;
        }
        fragments = (level?.fragments || []).filter((fragment) => !fragment.gap);
        const selectedFragments = fragments;
        if (selectedFragments.length === 0) {
          fail(new Error("HLS playlist has no segments"));
          return;
        }
        const unsupported = selectedFragments.find((fragment) => {
          const method = fragment.decryptdata?.method;
          return method && method !== "NONE" && !FULL_SEGMENT_METHODS.has(method);
        });
        if (unsupported) {
          fail(new Error(`HLS encryption method ${unsupported.decryptdata?.method} cannot be downloaded without transcoding`));
          return;
        }
        mapInitSegment = selectedFragments[0]?.initSegment || null;
        if (typeof data.level === "number" && Number.isInteger(data.level)) hls.loadLevel = data.level;
        if (mapInitSegment) {
          mode = "plaintext";
          ready("mp4");
        } else if (selectedFragments.some((fragment) => fragment.cc !== selectedFragments[0]?.cc)) {
          log("HLS playlist has discontinuities, keeping TS");
          mode = "plaintext";
          ready("ts");
        }
        hls.attachMedia(media);
      });

      try {
        hls.loadSource(String(url));
        hls.startLoad();
      } catch (error) {
        fail(new Error(`HLS loadSource failed: ${errorMessage(error)}`));
      }
    });
  } finally {
    abortListener.remove();
    stopWatchingActivity();
    try { hls.destroy(); } catch { /* hls.js teardown is best effort */ }
    media.remove();
  }
}

interface FragmentCapture {
  readonly fragment: HlsFragment;
  buffered: boolean;
  written: boolean;
  advancing: boolean;
  chunks: number;
}

function errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
