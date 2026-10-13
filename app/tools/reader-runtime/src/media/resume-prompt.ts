import { formatClock } from "./progress.ts";

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
    const time = overlay.querySelector<HTMLElement>(".nekori-overlay__time");
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
      overlay.querySelector('[data-action="continue"]')?.removeEventListener("click", continueAnswer);
      overlay.querySelector('[data-action="restart"]')?.removeEventListener("click", restartAnswer);
    };
    const continueAnswer = () => answer(true);
    const restartAnswer = () => answer(false);
    overlay.querySelector('[data-action="continue"]')?.addEventListener("click", continueAnswer, { once: true });
    overlay.querySelector('[data-action="restart"]')?.addEventListener("click", restartAnswer, { once: true });
  }
}

function errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
