import type { ReaderMediaHost, VideoDirective } from "./types.ts";
import playerStyles from "../styles/player.css?inline";

export interface PlayerSkinControls {
  readonly resume: HTMLDivElement | null;
  readonly next: HTMLDivElement | null;
}

export interface PlayerSkinCallbacks {
  readonly onNext: () => void;
  readonly onDismissNext: () => void;
}

export function decoratePlayerSkin(
  skin: HTMLElement,
  media: HTMLMediaElement,
  document: Document,
  host: ReaderMediaHost,
  config: VideoDirective,
  callbacks: PlayerSkinCallbacks,
): PlayerSkinControls {
  const shadow = skin.shadowRoot;
  const container = shadow?.querySelector("media-container");
  const resume = elementFromMarkup<HTMLDivElement>(document, `
    <div class="nekori-overlay" hidden>
      <div class="nekori-overlay__card media-popup-surface">
        <div class="nekori-overlay__title"></div><div class="nekori-overlay__question"></div>
        <div class="nekori-overlay__time"></div><div class="nekori-overlay__actions">
          <button type="button" class="media-button nekori-action nekori-action--primary" data-action="continue"></button>
          <button type="button" class="media-button nekori-action" data-action="restart"></button>
        </div>
      </div>
    </div>`);
  const nextChapter = host.nextChapter ?? config.nextChapter;
  const next = !nextChapter ? null : elementFromMarkup<HTMLDivElement>(document, `
    <div class="nekori-nextup media-popup-surface" hidden>
      <div class="nekori-nextup__head"><span class="nekori-nextup__label"></span><span class="nekori-nextup__count"></span></div>
      <div class="nekori-nextup__name"></div><div class="nekori-nextup__actions">
        <button type="button" class="media-button nekori-action nekori-action--primary" data-action="next"></button>
        <button type="button" class="media-button nekori-action nekori-nextup__dismiss" data-action="dismiss">&times;</button>
      </div>
    </div>`);
  if (!shadow || !container) return { resume: null, next: null };

  const strings = host.strings ?? {};
  installControlStyles(shadow, document);
  setText(resume, ".nekori-overlay__title", strings.videoResumeTitle);
  setText(resume, ".nekori-overlay__question", strings.videoResumeQuestion);
  setText(resume, '[data-action="continue"]', strings.videoResumeContinue);
  setText(resume, '[data-action="restart"]', strings.videoResumeRestart);
  if (next && nextChapter) {
    setText(next, '[data-action="next"]', strings.videoNextPlay);
    setText(next, ".nekori-nextup__label", strings.videoNextUp);
    setText(next, ".nekori-nextup__name", nextChapter.name);
    const dismiss = required(next.querySelector<HTMLButtonElement>('[data-action="dismiss"]'));
    if (strings.close) dismiss.setAttribute("aria-label", strings.close);
    required(next.querySelector<HTMLButtonElement>('[data-action="next"]')).addEventListener("click", callbacks.onNext);
    dismiss.addEventListener("click", callbacks.onDismissNext);
  }
  const seek = document.createElement("button");
  seek.type = "button";
  seek.className = "media-button nekori-action nekori-skip-intro";
  if (strings.videoSkipIntro) {
    seek.setAttribute("aria-label", strings.videoSkipIntro);
    seek.title = strings.videoSkipIntro;
  }
  seek.innerHTML = '<svg viewBox="0 0 24 24" class="media-button-icon" aria-hidden="true" fill="currentColor"><path d="M18.87 13c-.5 0-.91.37-.98.86-.48 3.37-3.77 5.84-7.42 4.96-2.25-.54-3.91-2.27-4.39-4.53C5.27 10.42 8.22 7 11.95 7v2.79c0 .45.54.67.85.35l3.79-3.79c.2-.2.2-.51 0-.71L12.8 1.85c-.31-.31-.85-.09-.85.35V5c-4.94 0-8.84 4.48-7.84 9.6.6 3.11 2.9 5.5 5.99 6.19 4.83 1.08 9.15-2.2 9.77-6.67.09-.59-.4-1.12-1-1.12z"></path><text x="12" y="16.6" text-anchor="middle" font-size="8" font-weight="600" fill="currentColor" stroke="none">85</text></svg>';
  seek.addEventListener("click", () => {
    const target = media.currentTime + 85;
    media.currentTime = Number.isFinite(media.duration) ? Math.min(target, media.duration) : target;
  });
  (shadow.querySelector(".video-controls-secondary") || shadow.querySelector(".video-controls-primary"))?.prepend(seek);
  container.append(resume);
  if (next) container.append(next);
  return { resume, next };
}

export function stripUnsupportedControls(skin: HTMLElement): void {
  const shadow = skin.shadowRoot;
  if (!shadow) throw new Error(`${skin.localName} shadow root is unavailable`);
  shadow.querySelectorAll("media-cast-button, media-airplay-button, media-pip-button").forEach((control) => {
    shadow.querySelectorAll("media-tooltip[trigger]").forEach((tooltip) => {
      if (tooltip.getAttribute("trigger") === control.id) tooltip.remove();
    });
  });
  shadow.querySelectorAll(
    "media-cast-button, media-airplay-button, media-pip-button, #cast-tooltip, #airplay-tooltip, #pip-tooltip, " +
    'media-hotkey[action="togglePictureInPicture"], media-icon[name="pip-enter"], media-icon[name="pip-exit"]',
  ).forEach((element) => element.remove());
  shadow.querySelectorAll("media-status-indicator[actions]").forEach((element) => {
    const actions = (element.getAttribute("actions") || "").split(/[\s,]+/).filter((action) => action && action !== "togglePictureInPicture");
    element.setAttribute("actions", actions.join(","));
  });
  const mediaContainer = shadow.querySelector("media-container");
  mediaContainer?.setAttribute("style", `${mediaContainer.getAttribute("style") || ""};--media-border-radius:0;--media-video-border-radius:0`);
}

function installControlStyles(shadow: ShadowRoot, document: Document): void {
  const style = document.createElement("style");
  style.textContent = playerStyles;
  shadow.prepend(style);
}

function elementFromMarkup<T extends Element>(document: Document, markup: string): T {
  const template = document.createElement("template");
  template.innerHTML = markup;
  const element = template.content.firstElementChild;
  if (!element) throw new Error("Player control template is empty");
  return element as T;
}

function setText(root: ParentNode, selector: string, value: string | undefined): void {
  const element = root.querySelector<HTMLElement>(selector);
  if (element) element.textContent = value || "";
}

function required<T>(value: T | null): T {
  if (!value) throw new Error("Player control markup is incomplete");
  return value;
}
