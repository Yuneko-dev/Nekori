import type { ReaderMediaHost, VideoDirective } from "./types.ts";
import { decoratePlayerSkin, stripUnsupportedControls } from "./player-controls.ts";
import { awaitPlayerElements, PLAYER_TAGS } from "./player-elements.ts";
export { awaitPlayerElements, ELEMENT_DEFINE_TIMEOUT_MS, PLAYER_TAGS } from "./player-elements.ts";
export { NEXT_UP_WINDOW_SECONDS, updateNextUp } from "./player-timing.ts";

export interface PlayerDom {
  readonly resumeOverlay: HTMLDivElement | null;
  readonly nextUpPopup: HTMLDivElement | null;
  readonly root: HTMLElement;
  readonly media: HTMLMediaElement;
  readonly skin: HTMLElement;
}

export interface PlayerDomCallbacks {
  readonly onNext: () => void;
  readonly onDismissNext: () => void;
}

export class PlayerDomFactory {
  private readonly document: Document;
  private readonly host: ReaderMediaHost;
  private readonly config: VideoDirective;
  private readonly callbacks: PlayerDomCallbacks;

  constructor(host: ReaderMediaHost, config: VideoDirective, callbacks: PlayerDomCallbacks) {
    this.document = host.root.ownerDocument;
    this.host = host;
    this.config = config;
    this.callbacks = callbacks;
  }

  createContainer(): HTMLDivElement {
    const container = this.document.createElement("div");
    container.id = "nekori-player-container";
    return container;
  }

  async build(mediaTag: string, live: boolean): Promise<PlayerDom> {
    const view = this.document.defaultView;
    if (!view) throw new Error("Reader document has no active window");
    const tags = live ? PLAYER_TAGS.live : PLAYER_TAGS.vod;
    await awaitPlayerElements(view.customElements, [tags.player, tags.skin, "media-i18n", mediaTag]);

    const media = this.document.createElement(mediaTag) as HTMLMediaElement;
    media.setAttribute("playsinline", "");
    media.setAttribute("preload", "auto");
    const thumbnailsVtt = this.config.thumbnailsVtt;
    if (mediaTag !== "video" || thumbnailsVtt) media.setAttribute("crossorigin", "anonymous");
    if (thumbnailsVtt) {
      const track = this.document.createElement("track");
      track.kind = "metadata";
      track.label = "thumbnails";
      track.src = thumbnailsVtt;
      track.default = true;
      media.append(track);
    }

    const skin = this.document.createElement(tags.skin);
    stripUnsupportedControls(skin);
    const controls = decoratePlayerSkin(skin, media, this.document, this.host, this.config, this.callbacks);
    skin.append(media);
    if (this.config.poster) {
      const image = this.document.createElement("img");
      image.slot = "poster";
      image.src = this.config.poster;
      image.alt = "";
      skin.append(image);
    }
    const player = this.document.createElement(tags.player);
    player.append(skin);
    const root = this.document.createElement("media-i18n");
    root.append(player);
    return { resumeOverlay: controls.resume, nextUpPopup: controls.next, root, media, skin };
  }
}

export { stripUnsupportedControls } from "./player-controls.ts";

