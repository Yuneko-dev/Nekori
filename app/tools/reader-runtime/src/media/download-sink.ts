export interface DownloadSinkHost {
  fetchNative(input: RequestInfo | URL, init?: RequestInit): Promise<Response>;
  emit(name: string, payload?: unknown): void;
}

export class DownloadSink {
  private readonly endpoint: string;
  private readonly host: DownloadSinkHost;
  private readonly signal: AbortSignal | undefined;

  constructor(host: DownloadSinkHost, endpoint: string, signal?: AbortSignal) {
    this.host = host;
    this.endpoint = endpoint.replace(/\/+$/, "");
    this.signal = signal;
  }

  start(task: () => Promise<void>, errorCode = "plugin"): Promise<void> {
    return task().catch(async (error: unknown) => {
      const message = errorMessage(error);
      this.host.emit("media.download.error", { code: errorCode, message });
      await this.delete().catch(() => undefined);
    });
  }

  async request(route: string, init: RequestInit, label?: string, useSignal = true): Promise<Response> {
    let response: Response;
    try {
      response = await this.host.fetchNative(`${this.endpoint}${route}`, {
        ...init,
        ...(useSignal && this.signal ? { signal: this.signal } : {}),
      });
    } catch (error) {
      if (error instanceof Error && error.name === "AbortError") throw error;
      throw new Error(`Download sink ${route} unreachable: ${errorMessage(error)}`);
    }
    if (label && !response.ok) throw new Error(`Download sink rejected ${label}: ${response.status}`);
    return response;
  }

  async delete(): Promise<void> {
    await this.request("/sink", { method: "DELETE" }, undefined, false);
  }

  async ready(container: string): Promise<void> {
    await this.request(`/sink?container=${encodeURIComponent(container)}`, { method: "POST" }, "ready");
    this.host.emit("media.download.ready", { container });
  }

  async put(bytes: Uint8Array): Promise<void> {
    if (bytes.byteLength === 0) return;
    // A copied, tightly sized buffer keeps the fetch body limited to this acknowledged chunk.
    await this.request("/sink", { method: "PUT", body: bytes.slice().buffer }, "chunk");
  }

  async commit(): Promise<void> {
    await this.request("/sink", { method: "POST" }, "commit");
    this.host.emit("media.download.complete", {});
  }

  progress(current: number, total: number): void {
    this.host.emit("media.download.progress", { current, total });
  }

  activity(): void {
    this.host.emit("media.download.activity", {});
  }
}

export async function writeResponseToSink(
  response: Response,
  sink: DownloadSink,
  totalBytes: number,
  signal?: AbortSignal,
): Promise<number> {
  let received = 0;
  let lastPercent = -1;
  const advance = (length: number) => {
    received += length;
    if (totalBytes <= 0) return;
    const percent = Math.min(100, Math.floor((received * 100) / totalBytes));
    if (percent !== lastPercent) {
      lastPercent = percent;
      sink.progress(percent, 100);
    }
  };

  if (response.body) {
    const reader = response.body.getReader();
    const cancel = () => { void reader.cancel(); };
    signal?.addEventListener("abort", cancel, { once: true });
    while (true) {
      try {
        if (signal?.aborted) throw new DOMException("The operation was aborted", "AbortError");
        const result = await reader.read();
        if (result.done) break;
        await sink.put(result.value);
        advance(result.value.byteLength);
      } catch (error) {
        await reader.cancel().catch(() => undefined);
        signal?.removeEventListener("abort", cancel);
        throw error;
      }
    }
    signal?.removeEventListener("abort", cancel);
    if (signal?.aborted) throw new DOMException("The operation was aborted", "AbortError");
  } else {
    const bytes = new Uint8Array(await response.arrayBuffer());
    await sink.put(bytes);
    advance(bytes.byteLength);
  }
  if (received === 0) throw new Error("Video stream was empty");
  await sink.commit();
  return received;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
