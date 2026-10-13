export interface HlsInitSegment { data?: ArrayBuffer | Uint8Array; }
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
  new(config: Record<string, unknown>): HlsEngine;
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
