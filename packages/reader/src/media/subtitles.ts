import type { ReaderMediaHost, SubtitleTrack } from "./types.ts";

export interface PreparedSubtitle {
  readonly label: string;
  readonly lang: string;
  readonly src: string;
  readonly default: boolean;
}

export function toWebVtt(text: string): string {
  const body = text.trimStart();
  return body.startsWith("WEBVTT")
    ? body
    : `WEBVTT\n\n${body.replace(/(\d\d:\d\d:\d\d),(\d\d\d)/g, "$1.$2")}`;
}

export class SubtitleManager {
  private readonly prepared: PreparedSubtitle[] = [];
  private readonly objectUrls = new Set<string>();
  private readonly host: ReaderMediaHost;
  private readonly document: Document;
  private readonly log: (message: string) => void;
  private readonly abortController = new AbortController();
  private disposed = false;

  constructor(
    host: ReaderMediaHost,
    document: Document,
    log: (message: string) => void,
  ) {
    this.host = host;
    this.document = document;
    this.log = log;
  }

  async add(tracks: SubtitleTrack | readonly SubtitleTrack[], media?: HTMLMediaElement | null): Promise<void> {
    if (this.disposed) return;
    for (const track of Array.isArray(tracks) ? tracks : [tracks]) {
      const label = track.label || "Subtitles";
      try {
        let text = track.content;
        if (!text) {
          if (!track.url) throw new Error("Subtitle URL is missing");
          const response = await this.host.fetch(track.url, {
            headers: { Referer: this.document.baseURI },
            signal: this.abortController.signal,
          });
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          text = await response.text();
        }
        if (this.disposed) return;
        const src = URL.createObjectURL(new Blob([toWebVtt(text)], { type: "text/vtt" }));
        const prepared = { label, lang: track.lang || "", src, default: Boolean(track.default) };
        this.prepared.push(prepared);
        this.objectUrls.add(src);
        if (media) this.append(media, prepared);
        this.log(`Subtitle added: ${label}`);
      } catch (error) {
        this.log(`Subtitle "${label}" failed: ${errorMessage(error)}`);
      }
    }
  }

  attach(media: HTMLMediaElement): void {
    for (const subtitle of this.prepared) this.append(media, subtitle);
  }

  dispose(): void {
    this.disposed = true;
    this.abortController.abort();
    for (const url of this.objectUrls) URL.revokeObjectURL(url);
    this.objectUrls.clear();
    this.prepared.length = 0;
  }

  private append(media: HTMLMediaElement, subtitle: PreparedSubtitle): void {
    const element = this.document.createElement("track");
    element.kind = "subtitles";
    element.label = subtitle.label;
    element.srclang = subtitle.lang;
    element.src = subtitle.src;
    media.append(element);
    if (subtitle.default && element.track) element.track.mode = "showing";
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
