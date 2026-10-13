import { buildDashSource, buildHlsSource } from "./direct.ts";
import { PlayerDebugOverlay } from "./debug-overlay.ts";
import { VideoDownloader } from "./download.ts";
import { reportPlayerFailure } from "./errors.ts";
import { PlaybackProgress } from "./playback-progress.ts";
import { PlayerDomFactory, type PlayerDom } from "./player-dom.ts";
import { ResumePrompt } from "./resume-prompt.ts";
import type { StringStorage } from "./progress.ts";
import { SubtitleManager } from "./subtitles.ts";
import type { DashConfig, PlayerConfig, PlayerDelegate, ReaderMediaHost, SubtitleTrack } from "./types.ts";
import type { HlsConstructor } from "./hls-download.ts";

type VideoEngine = {
  readonly events?: { readonly ERROR?: string };
  setProtectionData?(data: Record<string, unknown>): void;
  attachSource?(url: string | null): void;
  on?(event: string, listener: (event: EngineError) => void): void;
};
type EngineError = { readonly error?: { readonly message?: string }; readonly event?: { readonly message?: string }; readonly message?: string };
type VideoJsElement = HTMLMediaElement & {
  src: string;
  source?: Record<string, unknown>;
  engine?: VideoEngine;
  currentTime: number;
  duration: number;
  play(): Promise<void>;
  error?: MediaError | null;
};
type WindowWithHls = Window & { Hls?: HlsConstructor };

export class PlayerController implements PlayerDelegate {
  private readonly host: ReaderMediaHost;
  private readonly config: PlayerConfig;
  private readonly document: Document;
  private readonly view: WindowWithHls;
  private readonly domFactory: PlayerDomFactory;
  private readonly subtitles: SubtitleManager;
  private readonly debugOverlay: PlayerDebugOverlay;
  private readonly resumePrompt: ResumePrompt;
  private readonly downloader: VideoDownloader | null;
  private readonly playbackAbort = new AbortController();
  private readonly progressStorage: StringStorage | null;
  private container: HTMLDivElement | null = null;
  private playerElement: HTMLElement | null = null;
  private videoElement: VideoJsElement | null = null;
  private iframeElement: HTMLIFrameElement | null = null;
  private resumeOverlay: HTMLDivElement | null = null;
  private nextUpPopup: HTMLDivElement | null = null;
  private hlsInstance: unknown = null;
  private dashInstance: VideoEngine | null = null;
  private progress: PlaybackProgress | null = null;
  private nextUpDismissed = false;
  private debugEnabled: boolean;
  private disposed = false;

  constructor(host: ReaderMediaHost, config: PlayerConfig) {
    this.host = host;
    this.config = config;
    this.document = host.root.ownerDocument;
    const view = this.document.defaultView;
    if (!view) throw new Error("Reader document has no active window");
    this.view = view as WindowWithHls;
    this.debugEnabled = Boolean(config.video.debug);
    this.debugOverlay = new PlayerDebugOverlay(this.document, () => this.toggleDebug());
    this.resumePrompt = new ResumePrompt((video) => this.tryPlay(video as VideoJsElement));
    this.domFactory = new PlayerDomFactory(host, config.video, {
      onNext: () => this.host.emit("navigation.next", {}),
      onDismissNext: () => {
        this.nextUpDismissed = true;
        if (this.nextUpPopup) this.nextUpPopup.hidden = true;
      },
    });
    this.subtitles = new SubtitleManager(host, this.document, (message) => this.log(message));
    this.progressStorage = this.getStorage();
    this.downloader = config.mode === "download" && config.downloadEndpoint
      ? new VideoDownloader(host, config.video, config.downloadEndpoint, (message) => this.log(message))
      : null;
    // An untyped manual player (settings preview) should not add an empty video surface to prose.
    if (config.mode === "playback" && config.video.type !== undefined) this.initializePlaybackDom();
    if (config.video.subtitles?.length) void this.subtitles.add(config.video.subtitles);
  }

  async playDirect(url: string): Promise<void> {
    if (this.disposed) return;
    if (this.config.mode === "download") return this.downloadRequired("video-file", url);
    const video = await this.mountVideo("video");
    if (!video) return;
    video.src = url;
    this.tryPlay(video);
  }

  async playHls(url: string, customConfig: Record<string, unknown> = {}): Promise<void> {
    if (this.disposed) return;
    if (this.config.mode === "download") return this.downloadRequired("m3u8", url, customConfig);
    return this.playAdaptive("HLS", "hlsjs-video", url, (video) => {
      const Hls = this.view.Hls;
      if (!Hls || !Hls.isSupported()) throw new Error("hls.js is unavailable");
      video.source = buildHlsSource(url, { debug: this.debugEnabled, ...customConfig });
      this.hlsInstance = video.engine || null;
    });
  }

  async playDash(url: string, dashConfig: DashConfig = {}): Promise<void> {
    if (this.disposed) return;
    if (this.config.mode === "download") return this.fail("DASH and DRM video downloads are not supported");
    return this.playAdaptive("DASH", "dash-video", url, async (video) => {
      const engine = video.engine;
      if (!engine) throw new Error("dash.js engine is unavailable");
      const { settings, protectionData } = buildDashSource(dashConfig.settings, dashConfig.protectionData);
      if (protectionData && Object.keys(protectionData).length > 0) {
        const granted = await this.host.request("media.requestProtectedPlayback", {});
        if (this.disposed) return;
        if (granted !== true) {
          throw new Error("DRM video needs the device media identifier, which is not shared while incognito is on");
        }
        if (!engine.setProtectionData) throw new Error("dash.js cannot configure protected playback");
        engine.setProtectionData(protectionData);
      }
      if (settings) video.source = { engine: { dashJs: settings } };
      this.dashInstance = engine;
      engine.on?.(engine.events?.ERROR || "error", (event) => {
        const detail = event.error?.message || event.event?.message || event.message || "unknown error";
        this.fail(`DASH playback failed: ${detail}`);
      });
    });
  }

  async playIframe(url: string): Promise<void> {
    if (this.disposed) return;
    if (this.config.mode === "download") {
      const message = "Iframe video downloads are not supported";
      return this.fail(message, "iframe");
    }
    let iframeUrl: URL;
    try {
      iframeUrl = new URL(String(url), this.document.baseURI);
    } catch {
      return this.fail(`Invalid iframe URL: ${url}`);
    }
    if (iframeUrl.protocol !== "http:" && iframeUrl.protocol !== "https:") {
      return this.fail(`Unsupported iframe protocol: ${iframeUrl.protocol}`);
    }
    this.destroyCurrentMedia();
    const iframe = this.document.createElement("iframe");
    this.initializePlaybackDom();
    iframe.src = iframeUrl.href;
    iframe.sandbox.add("allow-scripts", "allow-same-origin", "allow-presentation");
    iframe.allowFullscreen = true;
    iframe.onload = () => this.log("Iframe loaded");
    iframe.onerror = () => { void this.fail("Iframe failed to load", "iframe"); };
    this.container?.append(iframe);
    this.iframeElement = iframe;
  }

  setDebug(enabled: boolean): void {
    this.debugEnabled = enabled;
    this.debugOverlay.set(enabled);
  }

  toggleDebug(): void { this.setDebug(!this.debugEnabled); }

  addSubtitles(tracks: SubtitleTrack | readonly SubtitleTrack[]): Promise<void> {
    return this.subtitles.add(tracks, this.videoElement);
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.playbackAbort.abort();
    this.downloader?.cancel();
    this.progress?.dispose();
    this.subtitles.dispose();
    this.destroyCurrentMedia();
    this.container?.remove();
    this.container = null;
    this.debugOverlay.dispose();
  }

  private initializePlaybackDom(): void {
    if (this.container) return;
    this.container = this.domFactory.createContainer();
    this.host.root.prepend(this.container);
    this.debugOverlay.attach(this.debugEnabled);
  }

  private fail(message: string, code = "plugin"): Promise<void> {
    if (this.disposed) return Promise.resolve();
    this.log(message);
    return reportPlayerFailure({
      mode: this.config.mode,
      ...(this.config.downloadEndpoint ? { downloadEndpoint: this.config.downloadEndpoint } : {}),
      emit: (name, payload) => this.host.emit(name, payload),
      fetchNative: (input, init) => this.host.fetchNative(input, init),
    }, message, code);
  }

  private async downloadRequired(type: "m3u8" | "video-file", url: string, config?: Record<string, unknown>): Promise<void> {
    if (!this.downloader) return this.fail("Video download sink is unavailable");
    await this.downloader.start(type, url, config);
  }

  log(message: string): void {
    if (this.disposed) return;
    console.log("[NekoriPlayer]", message);
    if (!this.debugEnabled) return;
    this.debugOverlay.attach(true);
    this.debugOverlay.log(message);
  }

  private destroyCurrentMedia(): void {
    this.resumePrompt.cancel();
    this.dashInstance?.attachSource?.(null);
    this.hlsInstance = null;
    this.dashInstance = null;
    this.progress?.dispose();
    this.progress = null;
    this.playerElement?.remove();
    this.playerElement = null;
    this.videoElement = null;
    this.iframeElement?.remove();
    this.iframeElement = null;
    this.resumeOverlay = null;
    this.nextUpPopup = null;
    this.nextUpDismissed = false;
  }

  private async mountVideo(mediaTag: string): Promise<VideoJsElement | null> {
    this.initializePlaybackDom();
    this.destroyCurrentMedia();
    try {
      const dom = await this.domFactory.build(mediaTag, Boolean(this.config.video.disableProgress));
      if (this.disposed) return null;
      this.playerElement = dom.root;
      this.videoElement = dom.media as VideoJsElement;
      this.resumeOverlay = dom.resumeOverlay;
      this.nextUpPopup = dom.nextUpPopup;
      this.container?.prepend(dom.root);
      this.progress = new PlaybackProgress({
        host: this.host,
        directive: this.config.video,
        storage: this.progressStorage,
        onResume: (video, seconds) => this.resumePrompt.offer(video, this.resumeOverlay, seconds),
        onDismissed: () => this.nextUpDismissed,
        log: (message) => this.log(message),
      });
      this.progress.attach(dom.media as HTMLMediaElement, dom.nextUpPopup, (message) => { void this.fail(message); });
      this.subtitles.attach(dom.media as HTMLMediaElement);
      return dom.media as VideoJsElement;
    } catch (error) {
      if (this.disposed) return null;
      await this.fail(`Video player initialization failed: ${errorMessage(error)}`);
      return null;
    }
  }

  private async playAdaptive(
    kind: "HLS" | "DASH",
    mediaTag: string,
    url: string,
    configure: (video: VideoJsElement) => void | Promise<void>,
  ): Promise<void> {
    const video = await this.mountVideo(mediaTag);
    if (!video) return;
    try {
      await configure(video);
      if (this.disposed) return;
      video.src = String(url);
      video.addEventListener("loadedmetadata", () => this.tryPlay(video), { once: true, signal: this.playbackAbort.signal });
    } catch (error) {
      if (this.disposed) return;
      await this.fail(`${kind} playback failed: ${errorMessage(error)}`);
    }
  }

  private tryPlay(video: VideoJsElement): void {
    this.resumePrompt.tryPlay(video, (message) => this.log(message));
  }

  private getStorage(): StringStorage | null {
    try { return this.view.localStorage; } catch { return null; }
  }
}

export function createPlayer(host: ReaderMediaHost, config: PlayerConfig): PlayerDelegate {
  return new PlayerController(host, config);
}

function errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
