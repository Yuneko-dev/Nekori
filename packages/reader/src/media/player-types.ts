import type { HlsConstructor } from './hls-download.ts';

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
