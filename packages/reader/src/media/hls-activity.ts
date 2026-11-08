import type { HlsEngine, HlsFragment } from "./hls-types.ts";
import type { DownloadSink } from "./download-sink.ts";

export function watchHlsActivity(
  view: Window,
  hls: HlsEngine,
  sink: DownloadSink,
): () => void {
  let loadingFragment: HlsFragment | undefined;
  let loadedBytes = 0;
  const timer = view.setInterval(() => {
    try {
      const fragment = hls.inFlightFragments?.main?.frag;
      if (fragment !== loadingFragment) {
        loadingFragment = fragment;
        loadedBytes = 0;
        if (fragment) sink.activity();
      }
      const currentBytes = Number(fragment?.stats?.loaded) || 0;
      if (currentBytes > loadedBytes) {
        loadedBytes = currentBytes;
        sink.activity();
      }
    } catch {
      // Activity is advisory; data and terminal events still determine download state.
    }
  }, 1000);
  return () => view.clearInterval(timer);
}
