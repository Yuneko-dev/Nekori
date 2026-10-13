import { combineResumeProgress, readFractions, writeFraction, type StringStorage } from "./progress.ts";
import { updateNextUp } from "./player-timing.ts";
import type { ReaderMediaHost, VideoDirective } from "./types.ts";

export class PlaybackProgress {
  private readonly host: ReaderMediaHost;
  private readonly directive: VideoDirective;
  private readonly storage: StringStorage | null;
  private readonly onResume: (video: HTMLMediaElement, seconds: number) => void;
  private readonly onDismissed: () => boolean;
  private readonly log: (message: string) => void;
  private readonly removeListeners: Array<() => void> = [];
  private hasSeekedInitial = false;
  private lastSaveTime = 0;

  constructor(options: {
    host: ReaderMediaHost;
    directive: VideoDirective;
    storage: StringStorage | null;
    onResume: (video: HTMLMediaElement, seconds: number) => void;
    onDismissed: () => boolean;
    log: (message: string) => void;
  }) {
    this.host = options.host;
    this.directive = options.directive;
    this.storage = options.storage;
    this.onResume = options.onResume;
    this.onDismissed = options.onDismissed;
    this.log = options.log;
  }

  attach(video: HTMLMediaElement, popup: HTMLElement | null, fail: (message: string) => void): void {
    const path = this.directive.path;
    const disable = Boolean(this.directive.disableProgress);
    const save = (progress: number, fraction = 0) => {
      if (disable) return;
      if (this.storage && path) writeFraction(this.storage, path, fraction);
      this.host.emit("reader.saveVideoProgress", { chapterId: this.host.chapter.id, path, progress });
    };
    this.listen(video, "loadedmetadata", () => {
      this.log("Video loadedmetadata");
      if (this.hasSeekedInitial || disable || !(video.duration > 0)) return;
      const progress = this.directive.progress;
      if (progress > 0 && progress < 100) {
        const restored = combineResumeProgress(progress, path, this.storage ? readFractions(this.storage) : {});
        this.onResume(video, (restored / 100) * video.duration);
      }
      this.hasSeekedInitial = true;
    });
    this.listen(video, "timeupdate", () => updateNextUp(video, popup, this.onDismissed()));
    this.listen(video, "timeupdate", () => {
      if (disable || !(video.duration > 0)) return;
      const currentTime = video.currentTime;
      if (Math.abs(currentTime - this.lastSaveTime) < 3) return;
      this.lastSaveTime = currentTime;
      const exact = (currentTime / video.duration) * 100;
      const progress = Math.floor(exact);
      save(progress, exact - progress);
    });
    this.listen(video, "ended", () => {
      this.log("Video ended");
      save(100);
      if (this.host.nextChapter ?? this.directive.nextChapter) this.host.emit("navigation.next", {});
    });
    this.listen(video, "error", () => {
      const mediaError = video.error as (MediaError & { message?: string }) | null;
      fail(`Video playback failed: ${mediaError?.message || "unknown error"}`);
    }, { once: true });
  }

  dispose(): void {
    for (const remove of this.removeListeners.splice(0)) remove();
  }

  private listen(target: EventTarget, type: string, listener: EventListener, options?: AddEventListenerOptions): void {
    target.addEventListener(type, listener, options);
    this.removeListeners.push(() => target.removeEventListener(type, listener, options));
  }
}
