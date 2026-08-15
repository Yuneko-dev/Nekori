/* eslint-disable */

// core-player.js

(function () {
  const FULL_SEGMENT_HLS_METHODS = new Set(["AES-128", "AES-256", "AES-256-CTR"]);
  const MIME_CONTAINERS = {
    "video/mp4": "mp4",
    "video/x-matroska": "mkv",
    "video/webm": "webm",
    "video/quicktime": "mov",
    "video/x-msvideo": "avi",
    "video/mp2t": "ts",
  };
  const VIDEO_EXTENSIONS = ["mp4", "m4v", "mkv", "webm", "mov", "avi", "ts"];
  // Keys must stay in sync with VIDEO_TYPES in NovelWebViewChapterDirectives.kt; a type Kotlin lets
  // through but this map does not know becomes an "Unknown video type" failure at playback.
  const DIRECT_PLAYERS = {
    m3u8: "playHls",
    mpd: "playDash",
    "video-file": "playDirect",
    iframe: "playIframe",
  };
  // Upstream Video.js tag names, deliberately not Tsundoku-specific ones: the bundled videojs.min.js
  // and a CDN build of the same version register exactly these, so either can back this file.
  const PLAYER_TAGS = {
    live: { player: "live-video-player", skin: "live-video-skin" },
    vod: { player: "video-player", skin: "video-skin" },
  };

  const metaContent = (name) => {
    const el = document.querySelector(`meta[name="${name}"]`);
    return el ? el.content.trim() : "";
  };

  // Native bridges (window.reader, window.Android, TsundokuVideoDownload) can vanish mid-playback or
  // mid-teardown, so every call through one is guarded the same way.
  const hostCall = (host, name, ...args) => {
    try {
      if (host && typeof host[name] === "function") return host[name](...args);
    } catch (_) {
      // The WebView may be torn down while a callback is in flight.
    }
    return undefined;
  };
  const readerCall = (name, ...args) => hostCall(window.reader, name, ...args);
  const readerProp = (name) => {
    try {
      return window.reader ? window.reader[name] : null;
    } catch (_) {
      return null;
    }
  };

  class LNReaderPlayer {
    constructor() {
      this.container = null;
      this.videoElement = null;
      this.playerElement = null;
      this.iframeElement = null;
      this.hlsInstance = null;
      this.dashInstance = null;
      this.debugOverlay = null;

      this.hasSeekedInitial = false;
      this.lastSaveTime = 0;
      this.isDebugMode = false;

      this.disableProgress = false;

      this.downloadEndpoint = "";
      this.downloadPromise = null;
      // Capture the real browser fetch before plugin custom.js can replace it.
      this.sinkFetch = window.fetch.bind(window);
    }

    init() {
      if (this.container) return; // Prevent double initialization

      this.isDebugMode = metaContent("lnreader-debug-mode") === "true";
      this.disableProgress = Boolean(
        document.querySelector("meta#lnreader-video-disable-progress")
      );
      this.downloadEndpoint = metaContent("lnreader-video-download").replace(
        /\/+$/,
        ""
      );

      this.container = document.createElement("div");
      this.container.id = "lnreader-player-container";
      this.setupDebugOverlay();

      const chapterEl = document.getElementById("LNReader-chapter");
      if (!chapterEl) {
        this.fail("Chapter container #LNReader-chapter is missing");
        return;
      }
      chapterEl.prepend(this.container);

      this.log("LNReaderPlayer initialized");

      if (metaContent("lnreader-video-mode") !== "direct") {
        this.log("Lazy mode or no mode detected, waiting for plugin...");
        return;
      }
      this.log("Direct mode detected");
      const url = metaContent("lnreader-video-url");
      const type = metaContent("lnreader-video-type");
      if (!url || !type) {
        this.fail("Direct video URL or type is missing");
        return;
      }
      this.log(`Auto-playing direct: type=${type}, url=${url}`);
      const method = DIRECT_PLAYERS[type];
      if (method) {
        this[method](url);
      } else {
        this.fail(`Unknown video type: ${type}`);
      }
    }

    setupDebugOverlay() {
      this.debugOverlay = document.createElement("div");
      this.debugOverlay.id = "lnreader-debug-overlay";
      document.body.appendChild(this.debugOverlay);
      if (!this.isDebugMode) return;

      this.debugOverlay.classList.add("active");
      const toggle = document.createElement("button");
      toggle.id = "lnreader-debug-toggle";
      toggle.type = "button";
      toggle.setAttribute("aria-label", "Hide player log");
      toggle.setAttribute("aria-expanded", "true");
      toggle.innerHTML =
        '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 7 4 5-4 5m6 0h8"/></svg>';
      toggle.addEventListener("click", () => {
        const visible = this.debugOverlay.classList.toggle("active");
        toggle.setAttribute("aria-expanded", String(visible));
        toggle.setAttribute(
          "aria-label",
          visible ? "Hide player log" : "Show player log"
        );
      });
      document.body.appendChild(toggle);
    }

    // Single exit for every failure the user has to know about. Callers never pick a channel: in
    // download mode the error must reach the download bridge or the download hangs waiting for bytes
    // that never arrive; during playback it goes to the reader's inline error banner.
    fail(message) {
      this.log(message);
      if (this.isDownloadMode()) {
        this.startDownload(() => {
          throw new Error(message);
        });
      } else {
        readerCall("error", message);
      }
    }

    log(msg) {
      console.log("[LNReaderPlayer]", msg);
      if (this.isDebugMode && this.debugOverlay) {
        const msgEl = document.createElement("div");
        msgEl.className = "lnreader-debug-msg";
        msgEl.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
        this.debugOverlay.appendChild(msgEl);
        this.debugOverlay.scrollTop = this.debugOverlay.scrollHeight;
      }
    }

    destroyCurrentMedia() {
      this.hlsInstance = null;
      this.dashInstance = null;
      if (this.playerElement) this.playerElement.remove();
      this.playerElement = null;
      this.videoElement = null;
      if (this.iframeElement) {
        this.iframeElement.remove();
        this.iframeElement = null;
      }
      this.hasSeekedInitial = false;
      this.lastSaveTime = 0;
    }

    isDownloadMode() {
      return Boolean(this.downloadEndpoint);
    }

    bridgeCall(name, ...args) {
      return hostCall(window.TsundokuVideoDownload, name, ...args);
    }

    startDownload(task) {
      if (this.downloadPromise) return this.downloadPromise;
      this.downloadPromise = Promise.resolve()
        .then(task)
        .catch(async (error) => {
          const message = (error && error.message) || String(error);
          this.log(`Download failed: ${message}`);
          this.bridgeCall("onError", "plugin", message);
          await this.deleteDownload();
        });
      return this.downloadPromise;
    }

    // `label` names the sink operation for the error message; omit it to accept any status.
    async sinkRequest(route, init, label) {
      let response;
      try {
        response = await this.sinkFetch(`${this.downloadEndpoint}${route}`, init);
      } catch (error) {
        if (error && error.name === "AbortError") throw error;
        throw new Error(
          `Download sink ${route} unreachable: ${(error && error.message) || error}`
        );
      }
      if (label && !response.ok) {
        throw new Error(`Download sink rejected ${label}: ${response.status}`);
      }
      return response;
    }

    deleteDownload() {
      return this.sinkRequest("/sink", { method: "DELETE" }).catch(() => {});
    }

    readyDownload(container) {
      return this.sinkRequest(
        `/sink?container=${encodeURIComponent(container)}`,
        { method: "POST" },
        "ready"
      );
    }

    putDownloadChunk(bytes) {
      if (!bytes || bytes.byteLength === 0) return Promise.resolve();
      return this.sinkRequest("/sink", { method: "PUT", body: bytes }, "chunk");
    }

    commitDownload() {
      return this.sinkRequest("/sink", { method: "POST" }, "commit");
    }

    // Only downloadDirect still fetches by hand; HLS goes through hls.js's own loader. The proxy is
    // kept here because a plain cross-origin video file usually carries no CORS headers of its own.
    sourceFetch(url) {
      const target = String(url);
      // A lazy-mode plugin can hand back a blob:/data: url it built in-page after decrypting. Those
      // are already local and the proxy rejects them outright - it only accepts http(s) - so they
      // have to be read directly.
      const isLocal = /^(blob:|data:|filesystem:)/i.test(target);
      const mediaFetch =
        !isLocal && window.reader && typeof window.reader.fetch === "function"
          ? window.reader.fetch.bind(window.reader)
          : window.fetch.bind(window);
      return mediaFetch(target, {
        headers: isLocal ? undefined : { Referer: document.baseURI },
      });
    }

    videoContainer(url, contentType) {
      const match = new URL(String(url), document.baseURI).pathname.match(
        /\.([a-z0-9]{1,5})$/i
      );
      const extension = match ? match[1].toLowerCase() : "";
      if (VIDEO_EXTENSIONS.includes(extension)) return extension;
      const mime = String(contentType || "").split(";", 1)[0].toLowerCase();
      return MIME_CONTAINERS[mime] || "mp4";
    }

    async downloadDirect(url) {
      // ponytail: direct video could use OkHttp with resume; phase 1 keeps one WebView pipeline.
      let response;
      try {
        response = await this.sourceFetch(url);
      } catch (error) {
        throw new Error((error && error.message) || "Video fetch failed");
      }
      if (!response.ok) {
        throw new Error(`Video fetch failed: ${response.status}`);
      }
      // The loopback proxy cannot forward Content-Length as-is, so it re-exposes the upstream value
      // under its own header. Without one of the two there is no total, and a direct download can
      // only ever report 0%.
      const totalBytes = Number(
        response.headers.get("content-length") ||
          response.headers.get("x-tsundoku-upstream-length")
      );
      const knownTotal =
        Number.isSafeInteger(totalBytes) && totalBytes > 0 ? totalBytes : 0;
      await this.readyDownload(
        this.videoContainer(url, response.headers.get("content-type"))
      );

      // Reported as a percentage rather than a byte count: the bridge marshals ints, and a large
      // video overflows one. Only whole percent changes cross the bridge, so a chunked read does not
      // fire thousands of calls.
      let received = 0;
      let lastPercent = -1;
      const advance = (byteLength) => {
        received += byteLength;
        if (knownTotal === 0) return;
        const percent = Math.min(100, Math.floor((received * 100) / knownTotal));
        if (percent !== lastPercent) {
          lastPercent = percent;
          this.bridgeCall("onProgress", percent, 100);
        }
      };

      if (response.body && typeof response.body.getReader === "function") {
        const reader = response.body.getReader();
        while (true) {
          const result = await reader.read();
          if (result.done) break;
          await this.putDownloadChunk(result.value);
          advance(result.value.byteLength);
        }
      } else {
        const bytes = new Uint8Array(await response.arrayBuffer());
        await this.putDownloadChunk(bytes);
        advance(bytes.byteLength);
      }
      if (received === 0) {
        throw new Error("Video stream was empty");
      }
      await this.commitDownload();
    }

    // The bundled hls.js emits FRAG_DECRYPTED for each complete plaintext segment before its normal
    // demux/remux step. Android remuxes the resulting TS after the sink commits.
    async downloadHls(url, customHlsConfig) {
      if (!window.Hls) {
        throw new Error("hls.js is unavailable");
      }
      const hls = new Hls(
        Object.assign({ debug: this.isDebugMode }, customHlsConfig, {
          autoStartLoad: false,
          backBufferLength: 0,
          lowLatencyMode: false,
          progressive: false,
          startFragPrefetch: false,
          tsundokuCaptureFragments: true,
        })
      );
      const media = document.createElement("video");
      media.hidden = true;
      media.muted = true;
      media.playsInline = true;
      document.body.appendChild(media);
      let loadingFragment = null;
      let loadedBytes = 0;
      const activityTimer = window.setInterval(() => {
        try {
          const inFlight = hls.inFlightFragments;
          const fragment = inFlight && inFlight.main && inFlight.main.frag;
          if (fragment !== loadingFragment) {
            loadingFragment = fragment;
            loadedBytes = 0;
            if (fragment) this.bridgeCall("onActivity");
          }
          const currentBytes = Number(fragment && fragment.stats && fragment.stats.loaded) || 0;
          if (currentBytes > loadedBytes) {
            loadedBytes = currentBytes;
            this.bridgeCall("onActivity");
          }
        } catch (_) {}
      }, 1000);
      try {
        await new Promise((resolve, reject) => {
          let fragments = null;
          let initSegment = null;
          let mediaStarted = false;
          let active = null;
          let next = 0;
          let settled = false;

          const fail = (error) => {
            if (settled) return;
            settled = true;
            reject(error instanceof Error ? error : new Error(String(error)));
          };
          const isSameFragment = (left, right) =>
            left === right ||
            (left && right && left.level === right.level && left.sn === right.sn);
          const advance = (capture) => {
            if (
              settled ||
              capture !== active ||
              !capture.buffered ||
              !capture.written ||
              capture.advancing
            ) {
              return;
            }
            capture.advancing = true;
            (async () => {
              next += 1;
              this.bridgeCall("onProgress", next, fragments.length);
              if (next === fragments.length) {
                await this.commitDownload();
                settled = true;
                resolve();
                return;
              }
              active = null;
              hls.resumeBuffering();
              media.currentTime = fragments[next].start;
            })().catch(fail);
          };
          const acceptPayload = (data) => {
            try {
              if (settled) return;
              const fragment = data && data.frag;
              if (!fragment || fragment.type !== "main") return;
              if (!data.payload) return;

              const bytes = new Uint8Array(data.payload);
              if (fragment.sn === "initSegment") return;
              if ((fragment.initSegment || null) !== initSegment) {
                fail(new Error("HLS init segment changes cannot be downloaded"));
                return;
              }

              const expected = fragments && fragments[next];
              if (active || !isSameFragment(fragment, expected)) return;
              const capture = (active = {
                fragment,
                buffered: false,
                written: false,
                advancing: false,
              });
              hls.pauseBuffering();
              (async () => {
                if (next === 0 && initSegment) {
                  if (!initSegment.data) {
                    throw new Error("HLS init segment is unavailable");
                  }
                  await this.putDownloadChunk(
                    new Uint8Array(initSegment.data).slice()
                  );
                }
                await this.putDownloadChunk(bytes);
                if (settled) return;
                capture.written = true;
                advance(capture);
              })().catch(fail);
            } catch (error) {
              fail(error);
            }
          };

          hls.on(Hls.Events.ERROR, (event, data) => {
            if (!data || (!data.fatal && data.details !== "fragDecryptError")) return;
            const detail = data.details || data.type || "unknown";
            const status = data.response && data.response.code ? ` (${data.response.code})` : "";
            fail(new Error(`HLS load failed: ${detail}${status}`));
          });
          hls.on(Hls.Events.FRAG_DECRYPTED, (event, data) => acceptPayload(data));
          hls.on(Hls.Events.FRAG_BUFFERED, (event, data) => {
            if (!active || !isSameFragment(data && data.frag, active.fragment)) return;
            active.buffered = true;
            advance(active);
          });
          hls.on(Hls.Events.MEDIA_ATTACHED, () => {
            if (settled || mediaStarted || !fragments) return;
            mediaStarted = true;
            hls.startLoad(fragments[0].start);
          });
          hls.on(Hls.Events.MANIFEST_PARSED, (event, data) => {
            const tracks = (data && data.audioTracks) || [];
            if (tracks.some((track) => track.url)) {
              fail(new Error("HLS with separate audio tracks cannot be downloaded"));
            }
          });
          hls.on(Hls.Events.LEVEL_LOADED, (event, data) => {
            if (settled || fragments) return;
            hls.stopLoad();
            if (data.details && data.details.live) {
              fail(new Error("Live HLS cannot be downloaded as a complete video"));
              return;
            }
            fragments = ((data.details && data.details.fragments) || []).filter(
              (fragment) => !fragment.gap
            );
            if (fragments.length === 0) {
              fail(new Error("HLS playlist has no segments"));
              return;
            }
            const unsupported = fragments.find((fragment) => {
              const method = fragment.decryptdata && fragment.decryptdata.method;
              return method && method !== "NONE" && !FULL_SEGMENT_HLS_METHODS.has(method);
            });
            if (unsupported) {
              const method = unsupported.decryptdata.method;
              fail(
                new Error(`HLS encryption method ${method} cannot be downloaded without transcoding`)
              );
              return;
            }
            initSegment = fragments[0].initSegment || null;
            if (Number.isInteger(data.level)) hls.loadLevel = data.level;
            this.readyDownload(initSegment ? "mp4" : "ts")
              .then(() => {
                if (!settled) hls.attachMedia(media);
              })
              .catch(fail);
          });

          try {
            hls.loadSource(String(url));
            hls.startLoad();
          } catch (error) {
            fail(
              new Error(`HLS loadSource failed: ${(error && error.message) || error}`)
            );
          }
        });
      } finally {
        window.clearInterval(activityTimer);
        try {
          hls.destroy();
        } catch (_) {}
        media.remove();
      }
    }

    attachEventListeners(video) {
      const saveProgress = (percent) => {
        if (!this.disableProgress) readerCall("post", { type: "save", data: percent });
      };

      video.addEventListener("loadedmetadata", () => {
        this.log("Video loadedmetadata");
        if (this.hasSeekedInitial || this.disableProgress || !(video.duration > 0)) {
          return;
        }
        const chapter = readerProp("chapter");
        if (!chapter) return;
        const initialProgress = chapter.progress || 0;
        this.log(`Initial progress: ${initialProgress}%`);
        if (initialProgress > 0 && initialProgress < 100) {
          video.currentTime = Math.floor((initialProgress / 100) * video.duration);
        }
        this.hasSeekedInitial = true;
      });

      video.addEventListener("timeupdate", () => {
        if (this.disableProgress || !(video.duration > 0)) return;
        const currentTime = video.currentTime;
        if (Math.abs(currentTime - this.lastSaveTime) < 3) return;
        this.lastSaveTime = currentTime;
        saveProgress(Math.floor((currentTime / video.duration) * 100));
      });

      video.addEventListener("ended", () => {
        this.log("Video ended");
        saveProgress(100);
        if (readerProp("nextChapter")) {
          this.log("Moving to next chapter");
          readerCall("post", { type: "next" });
        }
      });

      // A failing <video> re-fires error, and the first message is the useful one.
      video.addEventListener(
        "error",
        () => {
          const detail = video.error && video.error.message ? video.error.message : "unknown error";
          this.fail(`Video playback failed: ${detail}`);
        },
        { once: true }
      );
    }

    // Player composition is plain DOM against upstream's custom elements, never a Tsundoku-specific
    // runtime API, so swapping videojs.min.js for a CDN build of the same version needs no change here.
    buildPlayer(mediaTag) {
      const tags = this.disableProgress ? PLAYER_TAGS.live : PLAYER_TAGS.vod;
      for (const tag of [tags.player, tags.skin, "media-i18n", mediaTag]) {
        if (tag !== "video" && !customElements.get(tag)) {
          throw new Error(`custom element ${tag} is unavailable`);
        }
      }
      const thumbnailsVtt = metaContent("lnreader-video-thumbnails");
      const poster = metaContent("lnreader-video-poster");

      const media = document.createElement(mediaTag);
      media.setAttribute("playsinline", "");
      media.setAttribute("preload", "auto");
      // A cross-origin thumbnail VTT is only readable when the media element opts into CORS. On a
      // plain <video> that also forces a CORS fetch of the video itself, so it stays opt-in.
      if (mediaTag !== "video" || thumbnailsVtt) {
        media.setAttribute("crossorigin", "anonymous");
      }
      // No slot="media": upstream removed it in #997 and only restored it as deprecated in #1020.
      // The skin's default slot picks the media element up.
      if (thumbnailsVtt) {
        const track = document.createElement("track");
        track.kind = "metadata";
        track.label = "thumbnails";
        track.src = thumbnailsVtt;
        track.default = true;
        media.append(track);
      }

      const skin = document.createElement(tags.skin);
      this.stripUnsupportedControls(skin);
      skin.append(media);
      if (poster) {
        const image = document.createElement("img");
        image.slot = "poster";
        image.src = poster;
        image.alt = "";
        skin.append(image);
      }

      const player = document.createElement(tags.player);
      player.append(skin);

      // Every media-text in the skin reads its translator from the i18n context, and only media-i18n
      // provides it — without this wrapper the controls stay English no matter what <html lang> says.
      // The element takes no lang of its own so it resolves through the ancestor lang chain.
      const root = document.createElement("media-i18n");
      root.append(player);
      return { root, media };
    }

    // A CDN build ships the Cast/AirPlay/PiP controls that the offline bundle tree-shakes away. None
    // of them work in this WebView, so strip them here and one skin serves both builds.
    stripUnsupportedControls(skin) {
      const shadow = skin.shadowRoot;
      if (!shadow) throw new Error(`${skin.localName} shadow root is unavailable`);
      shadow
        .querySelectorAll(
          "media-cast-button, media-airplay-button, media-pip-button, " +
            "#cast-tooltip, #airplay-tooltip, #pip-tooltip, " +
            'media-hotkey[action="togglePictureInPicture"], ' +
            ".media-icon--pip-enter, .media-icon--pip-exit"
        )
        .forEach((element) => element.remove());
      shadow.querySelectorAll("media-status-indicator[actions]").forEach((element) => {
        const actions = (element.getAttribute("actions") || "")
          .split(/\s+/)
          .filter((action) => action && action !== "togglePictureInPicture");
        element.setAttribute("actions", actions.join(" "));
      });
      const container = shadow.querySelector("media-container");
      if (container) {
        container.style.setProperty("--media-border-radius", "0");
        container.style.setProperty("--media-video-border-radius", "0");
      }
    }

    mountVideo(mediaTag) {
      this.destroyCurrentMedia();
      try {
        const { root, media } = this.buildPlayer(mediaTag);
        this.attachEventListeners(media);
        this.container.prepend(root);
        this.playerElement = root;
        this.videoElement = media;
        return media;
      } catch (error) {
        this.fail(`Video player initialization failed: ${(error && error.message) || error}`);
        return null;
      }
    }

    // hlsjs-video and dash-video differ only in how their engine is configured; setting src is what
    // starts either one. Throwing from `configure` reports through the shared failure channel.
    playAdaptive(kind, mediaTag, url, configure) {
      const video = this.mountVideo(mediaTag);
      if (!video) return;
      try {
        configure(video);
        video.src = String(url);
        video.addEventListener("loadedmetadata", () => this.tryPlay(video), { once: true });
      } catch (error) {
        this.fail(`${kind} playback failed: ${(error && error.message) || error}`);
      }
    }

    tryPlay(video) {
      video.play().catch((e) => this.log(`Auto-play prevented: ${e.message}`));
    }

    playDirect(url) {
      this.init();
      this.log(`playDirect called with ${url}`);
      if (this.isDownloadMode()) {
        return this.startDownload(() => this.downloadDirect(url));
      }
      const video = this.mountVideo("video");
      if (!video) return;
      video.src = url;
      this.tryPlay(video);
    }

    playHls(url, customHlsConfig = {}) {
      this.init();
      this.log(`playHls called with ${url}`);
      if (this.isDownloadMode()) {
        return this.startDownload(() => this.downloadHls(url, customHlsConfig));
      }
      this.playAdaptive("HLS", "hlsjs-video", url, (video) => {
        // Chromium has no native HLS, so hls.js over MSE is the only playback path here.
        if (!window.Hls || !Hls.isSupported()) throw new Error("hls.js is unavailable");
        video.config = {
          preferPlayback: "mse",
          contentType: "application/vnd.apple.mpegurl",
          // The download-capture patch must stay off during playback; it forwards every decrypted
          // fragment and only the headless download WebView has a sink to receive them.
          hlsJs: Object.assign({ debug: this.isDebugMode }, customHlsConfig, {
            tsundokuCaptureFragments: false,
          }),
        };
        this.hlsInstance = video.engine || null;
      });
    }

    playDash(url, dashConfig = {}) {
      this.init();
      this.log(`playDash called with ${url}`);
      if (this.isDownloadMode()) {
        return this.startDownload(() => {
          throw new Error("DASH and DRM video downloads are not supported");
        });
      }
      this.playAdaptive("DASH", "dash-video", url, (video) => {
        const engine = video.engine;
        if (!engine) throw new Error("dash.js engine is unavailable");
        // dash.js ProtectionDataSet, passed through untouched: plugins write standard dash.js config.
        const protectionData = dashConfig.protectionData;
        if (protectionData && Object.keys(protectionData).length > 0) {
          // Widevine in a WebView needs the device DRM identifier even at L3, and Kotlin only honours
          // the grant right after this call. It refuses while incognito is on, because that identifier
          // is permanent and unresettable.
          if (!hostCall(window.Android, "requestProtectedMediaPlayback")) {
            throw new Error(
              "DRM video needs the device media identifier, which is not shared while incognito is on"
            );
          }
          // Not part of dash.js settings, and it must land before attachSource, which the `source`
          // assignment below triggers.
          engine.setProtectionData(protectionData);
        }
        // Upstream's structured source is the supported way in: it resets and re-applies dash.js
        // settings on the live engine. Assigning no `src` here leaves the manifest to playAdaptive,
        // whose `video.src = url` re-derives the source and carries these settings over.
        if (dashConfig.settings) video.source = { engine: { dashJs: dashConfig.settings } };
        this.dashInstance = engine;
        if (typeof engine.on === "function") {
          engine.on((engine.events && engine.events.ERROR) || "error", (event) => {
            const detail =
              (event && ((event.error && event.error.message) || (event.event && event.event.message) || event.message)) ||
              "unknown error";
            this.fail(`DASH playback failed: ${detail}`);
          });
        }
      });
    }

    playIframe(url) {
      this.init();
      this.log(`playIframe called with ${url}`);
      if (this.isDownloadMode()) {
        const message = "Iframe video downloads are not supported";
        this.bridgeCall("onError", "iframe", message);
        throw new Error(message);
      }
      let iframeUrl;
      try {
        iframeUrl = new URL(String(url), document.baseURI);
      } catch (_) {
        this.fail(`Invalid iframe URL: ${url}`);
        return;
      }
      if (iframeUrl.protocol !== "http:" && iframeUrl.protocol !== "https:") {
        this.fail(`Unsupported iframe protocol: ${iframeUrl.protocol}`);
        return;
      }
      this.destroyCurrentMedia();

      const iframe = document.createElement("iframe");
      iframe.src = iframeUrl.href;
      // Using sandbox without allow-popups and allow-popups-to-escape-sandbox
      // will effectively block window.open and target="_blank"
      iframe.sandbox = "allow-scripts allow-same-origin allow-presentation";
      iframe.allowFullscreen = true; // reflects to the allowfullscreen attribute
      iframe.onload = () => this.log("Iframe loaded");
      iframe.onerror = () => this.log("Iframe failed to load");

      this.container.appendChild(iframe);
      this.iframeElement = iframe;
    }
  }

  // Make it global
  window.LNReaderPlayer = new LNReaderPlayer();

  // Auto-init when DOM is ready
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () =>
      window.LNReaderPlayer.init()
    );
  } else {
    window.LNReaderPlayer.init();
  }
})();
