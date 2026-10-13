import { downloadHls, type HlsConstructor } from "./hls-download.ts";
import { DownloadSink, writeResponseToSink } from "./download-sink.ts";
import type { ReaderMediaHost, VideoDirective } from "./types.ts";

const MIME_CONTAINERS: Readonly<Record<string, string>> = {
  "video/mp4": "mp4",
  "video/x-matroska": "mkv",
  "video/webm": "webm",
  "video/quicktime": "mov",
  "video/x-msvideo": "avi",
  "video/mp2t": "ts",
};
const VIDEO_EXTENSIONS = new Set(["mp4", "m4v", "mkv", "webm", "mov", "avi", "ts"]);

export class VideoDownloader {
  private readonly host: ReaderMediaHost;
  private readonly document: Document;
  private readonly directive: VideoDirective;
  private readonly endpoint: string;
  private readonly log: (message: string) => void;
  private readonly abortController = new AbortController();
  private download: Promise<void> | null = null;

  constructor(
    host: ReaderMediaHost,
    directive: VideoDirective,
    endpoint: string,
    log: (message: string) => void,
  ) {
    this.host = host;
    this.document = host.root.ownerDocument;
    this.directive = directive;
    this.endpoint = endpoint;
    this.log = log;
  }

  start(type: "m3u8" | "video-file", url: string, hlsConfig: Record<string, unknown> = {}): Promise<void> {
    if (this.download) return this.download;
    const sink = new DownloadSink(this.host, this.endpoint, this.abortController.signal);
    const task = type === "m3u8"
      ? this.downloadHls(sink, url, hlsConfig)
      : this.downloadDirect(sink, url);
    this.download = sink.start(() => task);
    return this.download;
  }

  cancel(): void {
    this.abortController.abort();
  }

  private async downloadDirect(sink: DownloadSink, url: string): Promise<void> {
    const response = await this.sourceFetch(url);
    if (!response.ok) throw new Error(`Video fetch failed: ${response.status}`);
    // The paired native proxy header preserves upstream Content-Length without renaming its protocol yet.
    const contentLength = Number(response.headers.get("content-length") || response.headers.get("x-tsundoku-upstream-length"));
    const total = Number.isSafeInteger(contentLength) && contentLength > 0 ? contentLength : 0;
    await sink.ready(videoContainer(url, response.headers.get("content-type"), this.document.baseURI));
    await writeResponseToSink(response, sink, total, this.abortController.signal);
  }

  private async downloadHls(sink: DownloadSink, url: string, config: Record<string, unknown>): Promise<void> {
    const view = this.document.defaultView as (Window & { Hls?: HlsConstructor }) | null;
    const Hls = view?.Hls;
    if (!Hls) throw new Error("hls.js is unavailable");
    await downloadHls({
      Hls,
      document: this.document,
      sink,
      url,
      customConfig: config,
      debug: Boolean(this.directive.debug),
      signal: this.abortController.signal,
      log: this.log,
    });
  }

  private sourceFetch(url: string): Promise<Response> {
    const local = /^(blob:|data:|filesystem:)/i.test(url);
    const fetch = local ? this.host.fetchNative.bind(this.host) : this.host.fetch.bind(this.host);
    return fetch(url, { ...(local ? {} : { headers: { Referer: this.document.baseURI } }), signal: this.abortController.signal });
  }
}

export function videoContainer(url: string, contentType: string | null, baseURI: string): string {
  const pathname = new URL(String(url), baseURI).pathname;
  const extension = pathname.match(/\.([a-z0-9]{1,5})$/i)?.[1]?.toLowerCase() || "";
  if (VIDEO_EXTENSIONS.has(extension)) return extension;
  const mime = String(contentType || "").split(";", 1)[0]?.toLowerCase() || "";
  return MIME_CONTAINERS[mime] || "mp4";
}
