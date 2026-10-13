export type VideoType = "m3u8" | "mpd" | "video-file" | "iframe";
export type VideoMode = "playback" | "download";

export interface SubtitleTrack {
  readonly label?: string;
  readonly lang?: string;
  readonly url?: string;
  readonly content?: string;
  readonly default?: boolean;
}

export interface DashConfig {
  readonly settings?: Record<string, unknown>;
  readonly protectionData?: Record<string, unknown>;
}

export interface VideoDirective {
  readonly type?: VideoType;
  readonly url?: string;
  readonly path: string;
  readonly progress: number;
  readonly direct?: boolean;
  readonly nextChapter?: { readonly name: string } | null;
  readonly subtitles?: readonly SubtitleTrack[];
  readonly hlsConfig?: Record<string, unknown>;
  readonly dashConfig?: DashConfig;
  readonly poster?: string;
  readonly thumbnailsVtt?: string;
  readonly disableProgress?: boolean;
  readonly debug?: boolean;
}

export interface PlayerConfig {
  readonly mode: VideoMode;
  readonly video: VideoDirective;
  readonly proxyEndpoint?: string;
  readonly downloadEndpoint?: string;
}

export interface ReaderMediaHost {
  readonly root: HTMLElement;
  readonly chapter: Readonly<{ id: string; progress: number; path: string }>;
  readonly nextChapter?: Readonly<{ name: string }> | null;
  readonly strings?: Readonly<Record<string, string>>;
  fetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response>;
  fetchNative(input: RequestInfo | URL, init?: RequestInit): Promise<Response>;
  emit(name: string, payload?: unknown): void;
  request(name: string, payload?: unknown, signal?: AbortSignal): Promise<unknown>;
}

export interface PlayerDelegate {
  playHls(url: string, config?: Record<string, unknown>): Promise<void>;
  playDash(url: string, config?: DashConfig): Promise<void>;
  playDirect(url: string): Promise<void>;
  playIframe(url: string): Promise<void>;
  setDebug(enabled: boolean): void;
  toggleDebug(): void;
  addSubtitles(tracks: SubtitleTrack | readonly SubtitleTrack[]): Promise<void>;
  dispose(): void;
}
