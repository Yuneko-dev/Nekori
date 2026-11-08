import type { DashConfig, VideoType } from "./types.ts";

export const DIRECT_PLAYERS: Readonly<Record<VideoType, "playHls" | "playDash" | "playDirect" | "playIframe">> = {
  m3u8: "playHls",
  mpd: "playDash",
  "video-file": "playDirect",
  iframe: "playIframe",
};

export interface HlsSource {
  readonly [key: string]: unknown;
  readonly preferPlayback: "mse";
  readonly type: "application/vnd.apple.mpegurl";
  readonly engine: { readonly hlsJs: Record<string, unknown> };
}

export function buildHlsSource(_url: string, config: Record<string, unknown>, debug = Boolean(config.debug)): HlsSource {
  return {
    preferPlayback: "mse",
    type: "application/vnd.apple.mpegurl",
    engine: {
      // Vendor HLS patch option; it stays paired with the upstream bundle's capture hook.
      hlsJs: { debug, ...config, tsundokuCaptureFragments: false },
    },
  };
}

export function buildHlsDownloadConfig(config: Record<string, unknown>, debug: boolean): Record<string, unknown> {
  return {
    debug,
    ...config,
    autoStartLoad: false,
    backBufferLength: 0,
    lowLatencyMode: false,
    progressive: false,
    startFragPrefetch: false,
    // Vendor HLS patch option; it stays paired with the upstream bundle's capture hook.
    tsundokuCaptureFragments: true,
  };
}

export function buildDashSource(settings?: Record<string, unknown>, protectionData?: Record<string, unknown>): DashConfig {
  return {
    ...(settings ? { settings } : {}),
    ...(protectionData ? { protectionData } : {}),
  };
}
