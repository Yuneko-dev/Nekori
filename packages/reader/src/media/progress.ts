import type { ReaderMediaHost, VideoDirective } from './types.ts';
export const VIDEO_FRACTION_KEY = '__tsundoku_video_fraction';

export type FractionMap = Record<string, number>;
export interface StringStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export function readFractions(storage: StringStorage): FractionMap {
  try {
    const parsed: unknown = JSON.parse(storage.getItem(VIDEO_FRACTION_KEY) || '{}');
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as FractionMap)
      : {};
  } catch {
    return {};
  }
}

export function writeFraction(storage: StringStorage, path: string, fraction: number): void {
  if (!path) return;
  try {
    const fractions = readFractions(storage);
    if (fraction > 0) fractions[path] = Math.round(fraction * 1e4) / 1e4;
    else delete fractions[path];
    storage.setItem(VIDEO_FRACTION_KEY, JSON.stringify(fractions));
  } catch {
    // Native whole-percent progress remains usable if web storage is unavailable.
  }
}

export function combineResumeProgress(
  progress: number,
  path: string,
  fractions: FractionMap,
): number {
  if (!(progress > 0 && progress < 100)) return progress;
  const fraction = fractions[path];
  return progress + (fraction !== undefined && fraction > 0 && fraction < 1 ? fraction : 0);
}

export function formatClock(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  return [Math.floor(total / 3600), Math.floor(total / 60) % 60, total % 60]
    .map((part) => String(part).padStart(2, '0'))
    .join(':');
}

export const NEXT_UP_WINDOW_SECONDS = 120;

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

  attach(
    video: HTMLMediaElement,
    popup: HTMLElement | null,
    fail: (message: string) => void,
  ): void {
    const path = this.directive.path;
    const disable = Boolean(this.directive.disableProgress);
    const save = (progress: number, fraction = 0, ended = false) => {
      if (this.storage && path) writeFraction(this.storage, path, fraction);
      this.host.emit('reader.saveVideoProgress', {
        chapterId: this.host.chapter.id,
        path,
        progress,
        ended,
      });
    };
    if (disable) save(0);
    this.listen(video, 'loadedmetadata', () => {
      this.log('Video loadedmetadata');
      if (!Number.isFinite(video.duration)) {
        save(0);
        return;
      }
      if (this.hasSeekedInitial || disable || !(video.duration > 0)) return;
      const progress = this.directive.progress;
      if (progress > 0 && progress < 100) {
        const restored = combineResumeProgress(
          progress,
          path,
          this.storage ? readFractions(this.storage) : {},
        );
        this.onResume(video, (restored / 100) * video.duration);
      }
      this.hasSeekedInitial = true;
    });
    this.listen(video, 'timeupdate', () => updateNextUp(video, popup, this.onDismissed()));
    this.listen(video, 'timeupdate', () => {
      if (disable || !Number.isFinite(video.duration) || !(video.duration > 0)) return;
      const currentTime = video.currentTime;
      if (Math.abs(currentTime - this.lastSaveTime) < 3) return;
      this.lastSaveTime = currentTime;
      const exact = (currentTime / video.duration) * 100;
      const progress = Math.floor(exact);
      save(progress, exact - progress);
    });
    this.listen(video, 'ended', () => {
      this.log('Video ended');
      const completed = !disable && Number.isFinite(video.duration) && video.duration > 0;
      save(completed ? 100 : 0, 0, completed);
      if (this.host.nextChapter ?? this.directive.nextChapter)
        this.host.emit('navigation.next', {});
    });
    this.listen(
      video,
      'error',
      () => {
        const mediaError = video.error as (MediaError & { message?: string }) | null;
        fail(`Video playback failed: ${mediaError?.message || 'unknown error'}`);
      },
      { once: true },
    );
  }

  dispose(): void {
    for (const remove of this.removeListeners.splice(0)) remove();
  }

  private listen(
    target: EventTarget,
    type: string,
    listener: EventListener,
    options?: AddEventListenerOptions,
  ): void {
    target.addEventListener(type, listener, options);
    this.removeListeners.push(() => target.removeEventListener(type, listener, options));
  }
}

export function updateNextUp(
  video: HTMLMediaElement,
  popup: HTMLElement | null,
  dismissed: boolean,
): void {
  if (!popup || dismissed || !(video.duration > NEXT_UP_WINDOW_SECONDS)) return;
  const remaining = video.duration - video.currentTime;
  if (remaining > NEXT_UP_WINDOW_SECONDS || remaining <= 0) {
    popup.hidden = true;
    return;
  }
  const count = popup.querySelector<HTMLElement>('.nekori-nextup__count');
  if (count) count.textContent = `${Math.ceil(remaining)}s`;
  popup.hidden = false;
}

export const PLAYER_TAGS = {
  live: { player: 'live-video-player', skin: 'live-video-skin' },
  vod: { player: 'video-player', skin: 'video-skin' },
} as const;
export const ELEMENT_DEFINE_TIMEOUT_MS = 10_000;

export async function awaitPlayerElements(
  registry: CustomElementRegistry,
  tags: readonly string[],
  timeoutMs = ELEMENT_DEFINE_TIMEOUT_MS,
): Promise<void> {
  const pending = tags.filter((tag) => tag !== 'video' && !registry.get(tag));
  if (pending.length === 0) return;
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([
      Promise.all(pending.map((tag) => registry.whenDefined(tag))),
      new Promise<never>((_, reject) => {
        timeout = setTimeout(
          () => reject(new Error(`custom element ${pending.join(', ')} is unavailable`)),
          timeoutMs,
        );
      }),
    ]);
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
}

export class PlayerDebugOverlay {
  private readonly document: Document;
  private readonly onToggle: () => void;
  private overlay: HTMLDivElement | null = null;
  private toggle: HTMLButtonElement | null = null;

  constructor(document: Document, onToggle: () => void) {
    this.document = document;
    this.onToggle = onToggle;
  }

  attach(enabled: boolean): void {
    if (this.overlay) return;
    this.overlay = this.document.createElement('div');
    this.overlay.id = 'nekori-debug-overlay';
    this.document.body.append(this.overlay);
    this.set(enabled);
  }

  set(enabled: boolean): void {
    if (!this.overlay) return;
    if (enabled && !this.toggle) {
      this.toggle = this.document.createElement('button');
      this.toggle.id = 'nekori-debug-toggle';
      this.toggle.type = 'button';
      this.toggle.addEventListener('click', this.onToggle);
      this.document.body.append(this.toggle);
    }
    this.overlay?.classList.toggle('active', enabled);
    this.toggle?.setAttribute('aria-expanded', String(enabled));
    this.toggle?.setAttribute('aria-label', enabled ? 'Hide player log' : 'Show player log');
  }

  log(message: string): void {
    if (!this.overlay) return;
    const line = this.document.createElement('div');
    line.className = 'nekori-debug-msg';
    line.textContent = `[${new Date().toLocaleTimeString()}] ${message}`;
    this.overlay.append(line);
    this.overlay.scrollTop = this.overlay.scrollHeight;
  }

  dispose(): void {
    this.overlay?.remove();
    this.toggle?.remove();
    this.overlay = null;
    this.toggle = null;
  }
}

export class ResumePrompt {
  private awaiting = false;
  private answer: (() => void) | null = null;
  private readonly onPlay: (video: HTMLMediaElement) => void;

  constructor(onPlay: (video: HTMLMediaElement) => void) {
    this.onPlay = onPlay;
  }

  tryPlay(video: HTMLMediaElement, log: (message: string) => void): void {
    if (this.awaiting) return;
    void video.play().catch((error: unknown) => log(`Auto-play prevented: ${errorMessage(error)}`));
  }

  cancel(): void {
    this.answer?.();
    this.answer = null;
    this.awaiting = false;
  }

  offer(video: HTMLMediaElement, overlay: HTMLElement | null, seconds: number): void {
    if (!overlay) {
      video.currentTime = seconds;
      return;
    }
    const time = overlay.querySelector<HTMLElement>('.nekori-overlay__time');
    if (time) time.textContent = formatClock(seconds);
    overlay.hidden = false;
    this.awaiting = true;
    const answer = (resume: boolean) => {
      this.answer = null;
      overlay.hidden = true;
      this.awaiting = false;
      if (resume) video.currentTime = seconds;
      this.onPlay(video);
    };
    this.answer = () => {
      overlay.hidden = true;
      overlay.querySelector('[data-action="continue"]')?.removeEventListener('click', continueAnswer);
      overlay.querySelector('[data-action="restart"]')?.removeEventListener('click', restartAnswer);
    };
    const continueAnswer = () => answer(true);
    const restartAnswer = () => answer(false);
    overlay.querySelector('[data-action="continue"]')?.addEventListener('click', continueAnswer, { once: true });
    overlay.querySelector('[data-action="restart"]')?.addEventListener('click', restartAnswer, { once: true });
  }
}

function errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
