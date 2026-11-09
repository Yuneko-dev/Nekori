export type VideoType = 'm3u8' | 'mpd' | 'video-file' | 'iframe';
export type VideoMode = 'playback' | 'download';

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
  log(message: string): void;
  playHls(url: string, config?: Record<string, unknown>): Promise<void>;
  playDash(url: string, config?: DashConfig): Promise<void>;
  playDirect(url: string): Promise<void>;
  playIframe(url: string): Promise<void>;
  setDebug(enabled: boolean): void;
  toggleDebug(): void;
  addSubtitles(tracks: SubtitleTrack | readonly SubtitleTrack[]): Promise<void>;
  dispose(): void;
}
export interface HlsInitSegment {
  data?: ArrayBuffer | Uint8Array;
}
export interface HlsFragment {
  readonly level?: number;
  readonly sn?: number | string;
  readonly type?: string;
  readonly initSegment?: HlsInitSegment | null;
  readonly decryptdata?: { readonly method?: string } | null;
  readonly gap?: boolean;
  readonly cc?: number;
  readonly start?: number;
  readonly stats?: { readonly loaded?: number };
}
export interface HlsEventData {
  readonly frag?: HlsFragment;
  readonly payload?: ArrayBuffer | Uint8Array;
  // Vendor HLS patch output; keep paired with the upstream bundle's init-segment hook.
  readonly tracks?: { readonly video?: { readonly tsundokuInitSegment?: Uint8Array } };
  readonly data?: Uint8Array;
  readonly details?: string | { readonly live?: boolean; readonly fragments?: HlsFragment[] };
  readonly audioTracks?: readonly { readonly url?: string }[];
  readonly level?: number;
  readonly fatal?: boolean;
  readonly type?: string;
  readonly response?: { readonly code?: number };
  readonly error?: { readonly message?: string };
  readonly event?: { readonly message?: string };
  readonly message?: string;
}
export interface HlsEngine {
  readonly inFlightFragments?: { readonly main?: { readonly frag?: HlsFragment } };
  readonly events?: { readonly ERROR?: string };
  loadLevel?: number;
  on(event: string, listener: (name: string, data: HlsEventData) => void): void;
  loadSource(url: string): void;
  startLoad(startPosition?: number): void;
  stopLoad(): void;
  attachMedia(media: HTMLMediaElement): void;
  pauseBuffering(): void;
  resumeBuffering(): void;
  destroy(): void;
}
export interface HlsConstructor {
  new (config: Record<string, unknown>): HlsEngine;
  isSupported(): boolean;
  readonly Events: Record<string, string> & {
    readonly FRAG_PARSING_INIT_SEGMENT: string;
    readonly BUFFER_APPENDING: string;
    readonly FRAG_PARSED: string;
    readonly ERROR: string;
    readonly FRAG_DECRYPTED: string;
    readonly FRAG_BUFFERED: string;
    readonly MEDIA_ATTACHED: string;
    readonly MANIFEST_PARSED: string;
    readonly LEVEL_LOADED: string;
  };
}


export type VideoEngine = {
  readonly events?: { readonly ERROR?: string };
  setProtectionData?(data: Record<string, unknown>): void;
  attachSource?(url: string | null): void;
  on?(event: string, listener: (event: EngineError) => void): void;
};

export type EngineError = {
  readonly error?: { readonly message?: string };
  readonly event?: { readonly message?: string };
  readonly message?: string;
};

export type VideoJsElement = HTMLMediaElement & {
  src: string;
  source?: Record<string, unknown>;
  engine?: VideoEngine;
  currentTime: number;
  duration: number;
  play(): Promise<void>;
  error?: MediaError | null;
};

export type WindowWithHls = Window & { Hls?: HlsConstructor };

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
