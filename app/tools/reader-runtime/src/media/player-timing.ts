export const NEXT_UP_WINDOW_SECONDS = 120;

export function updateNextUp(video: HTMLMediaElement, popup: HTMLElement | null, dismissed: boolean): void {
  if (!popup || dismissed || !(video.duration > NEXT_UP_WINDOW_SECONDS)) return;
  const remaining = video.duration - video.currentTime;
  if (remaining > NEXT_UP_WINDOW_SECONDS || remaining <= 0) {
    popup.hidden = true;
    return;
  }
  const count = popup.querySelector<HTMLElement>(".nekori-nextup__count");
  if (count) count.textContent = `${Math.ceil(remaining)}s`;
  popup.hidden = false;
}
