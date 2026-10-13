import { DownloadSink } from "./download-sink.ts";
import type { VideoMode } from "./types.ts";

export interface PlayerFailureHost {
  emit(name: string, payload?: unknown): void;
  fetchNative(input: RequestInfo | URL, init?: RequestInit): Promise<Response>;
}

export async function reportPlayerFailure(
  options: {
    readonly mode: VideoMode;
    readonly downloadEndpoint?: string;
    readonly emit: PlayerFailureHost["emit"];
    readonly fetchNative: PlayerFailureHost["fetchNative"];
  },
  message: string,
  code = "plugin",
): Promise<void> {
  if (options.mode === "playback") {
    options.emit("reader.error", { message });
    return;
  }
  if (!options.downloadEndpoint) {
    options.emit("media.download.error", { code, message });
    return;
  }
  const sink = new DownloadSink(options, options.downloadEndpoint);
  await sink.start(async () => { throw new Error(message); }, code);
}
