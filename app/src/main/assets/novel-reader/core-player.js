/* eslint-disable */

// core-player.js

(function () {
  const FULL_SEGMENT_HLS_METHODS = new Set(["AES-128", "AES-256", "AES-256-CTR"]);

  class LNReaderPlayer {
    constructor() {
      this.container = null;
      this.videoElement = null;
      this.iframeElement = null;
      this.hlsInstance = null;
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
      // Check debug mode
      const debugMeta = document.querySelector(
        'meta[name="lnreader-debug-mode"]'
      );
      if (debugMeta && debugMeta.content === "true") {
        this.isDebugMode = true;
      }

      // Check disable progress mode
      const disableProgressMeta = document.getElementById(
        "lnreader-video-disable-progress"
      );
      if (
        disableProgressMeta &&
        disableProgressMeta.tagName.toLowerCase() === "meta"
      ) {
        this.disableProgress = true;
      }

      const downloadMeta = document.querySelector(
        'meta[name="lnreader-video-download"]'
      );
      if (downloadMeta && downloadMeta.content) {
        this.downloadEndpoint = downloadMeta.content.trim().replace(/\/+$/, "");
      }

      // Get chapter content element to append player inside
      const chapterEl = document.getElementById("LNReader-chapter");

      // Create container
      this.container = document.createElement("div");
      this.container.id = "lnreader-player-container";

      // Create debug overlay
      this.debugOverlay = document.createElement("div");
      this.debugOverlay.id = "lnreader-debug-overlay";
      if (this.isDebugMode) {
        this.debugOverlay.classList.add("active");
      }
      document.body.appendChild(this.debugOverlay);
      if (this.isDebugMode) {
        const debugToggle = document.createElement("button");
        debugToggle.id = "lnreader-debug-toggle";
        debugToggle.type = "button";
        debugToggle.setAttribute("aria-label", "Hide player log");
        debugToggle.setAttribute("aria-expanded", "true");
        debugToggle.innerHTML =
          '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 7 4 5-4 5m6 0h8"/></svg>';
        debugToggle.addEventListener("click", () => {
          const visible = this.debugOverlay.classList.toggle("active");
          debugToggle.setAttribute("aria-expanded", String(visible));
          debugToggle.setAttribute(
            "aria-label",
            visible ? "Hide player log" : "Show player log"
          );
        });
        document.body.appendChild(debugToggle);
      }
      if (chapterEl) {
        chapterEl.appendChild(this.container);
      } else {
        document.body.appendChild(this.container);
      }

      this.log("LNReaderPlayer initialized");

      // Check auto-play direct mode
      const modeMeta = document.querySelector(
        'meta[name="lnreader-video-mode"]'
      );
      if (modeMeta && modeMeta.content === "direct") {
        this.log("Direct mode detected");
        const urlMeta = document.querySelector(
          'meta[name="lnreader-video-url"]'
        );
        const typeMeta = document.querySelector(
          'meta[name="lnreader-video-type"]'
        );

        if (urlMeta && urlMeta.content && typeMeta && typeMeta.content) {
          const url = urlMeta.content;
          const type = typeMeta.content;
          this.log(`Auto-playing direct: type=${type}, url=${url}`);

          if (type === "m3u8") {
            this.playHls(url);
          } else if (type === "video-file") {
            this.playDirect(url);
          } else if (type === "iframe") {
            this.playIframe(url);
          } else {
            this.log(`Unknown direct type: ${type}`);
            if (this.isDownloadMode()) {
              this.startDownload(() =>
                this.rejectNoStream(`Unknown video type: ${type}`)
              );
            }
          }
        } else {
          this.log("Direct mode missing url or type meta tag");
          if (this.isDownloadMode()) {
            this.startDownload(() =>
              this.rejectNoStream("Direct video URL or type is missing")
            );
          }
        }
      } else {
        this.log("Lazy mode or no mode detected, waiting for plugin...");
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
      if (this.hlsInstance) {
        this.hlsInstance.destroy();
        this.hlsInstance = null;
      }
      if (this.videoElement) {
        this.container.removeChild(this.videoElement);
        this.videoElement = null;
      }
      if (this.iframeElement) {
        this.container.removeChild(this.iframeElement);
        this.iframeElement = null;
      }
      this.hasSeekedInitial = false;
      this.lastSaveTime = 0;
    }

    ensureInit() {
      if (!this.container) {
        this.init();
      }
    }

    isDownloadMode() {
      return Boolean(this.downloadEndpoint);
    }

    bridgeCall(name, ...args) {
      const bridge = window.TsundokuVideoDownload;
      if (bridge && typeof bridge[name] === "function") {
        try {
          bridge[name](...args);
        } catch (_) {
          // The WebView may be torn down while a final callback is in flight.
        }
      }
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

    async sinkRequest(route, init) {
      try {
        return await this.sinkFetch(`${this.downloadEndpoint}${route}`, init);
      } catch (error) {
        if (error && error.name === "AbortError") throw error;
        throw new Error(
          `Download sink ${route} unreachable: ${(error && error.message) || error}`
        );
      }
    }

    deleteDownload() {
      return this.sinkRequest("/sink", { method: "DELETE" }).catch(() => {});
    }

    // Only downloadDirect still fetches by hand; HLS goes through hls.js's own loader. The proxy is
    // kept here because a plain cross-origin video file usually carries no CORS headers of its own.
    async sourceFetch(url) {
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

    async readyDownload(container) {
      const response = await this.sinkRequest(
        `/sink?container=${encodeURIComponent(container)}`,
        { method: "POST" }
      );
      if (!response.ok) {
        throw new Error(`Download sink rejected ready: ${response.status}`);
      }
    }

    rejectNoStream(message) {
      throw new Error(message);
    }

    async putDownloadChunk(bytes) {
      if (!bytes || bytes.byteLength === 0) return;
      const response = await this.sinkRequest("/sink", {
        method: "PUT",
        body: bytes,
      });
      if (!response.ok) {
        throw new Error(`Download sink rejected chunk: ${response.status}`);
      }
    }

    async commitDownload() {
      const response = await this.sinkRequest("/sink", { method: "POST" });
      if (!response.ok) {
        throw new Error(`Download sink rejected commit: ${response.status}`);
      }
    }

    videoContainer(url, contentType) {
      const mimeContainers = {
        "video/mp4": "mp4",
        "video/x-matroska": "mkv",
        "video/webm": "webm",
        "video/quicktime": "mov",
        "video/x-msvideo": "avi",
        "video/mp2t": "ts",
      };
      const mime = String(contentType || "").split(";", 1)[0].toLowerCase();
      const match = new URL(String(url), document.baseURI).pathname.match(
        /\.([a-z0-9]{1,5})$/i
      );
      const extension = match ? match[1].toLowerCase() : "";
      if (["mp4", "m4v", "mkv", "webm", "mov", "avi", "ts"].includes(extension)) {
        return extension;
      }
      return mimeContainers[mime] || "mp4";
    }

    async downloadDirect(url) {
      // ponytail: direct video could use OkHttp with resume; phase 1 keeps one WebView pipeline.
      let response;
      try {
        response = await this.sourceFetch(url);
      } catch (error) {
        return this.rejectNoStream((error && error.message) || "Video fetch failed");
      }
      const container = this.videoContainer(url, response.headers.get("content-type"));
      if (!response.ok) {
        return this.rejectNoStream(`Video fetch failed: ${response.status}`);
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
      await this.readyDownload(container);

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
      const self = this;

      video.addEventListener("loadedmetadata", function () {
        self.log("Video loadedmetadata");
        try {
          if (
            !self.hasSeekedInitial &&
            video.duration > 0 &&
            window.reader &&
            window.reader.chapter &&
            !self.disableProgress
          ) {
            var initialProgress = window.reader.chapter.progress || 0;
            self.log(`Initial progress: ${initialProgress}%`);
            if (initialProgress > 0 && initialProgress < 100) {
              video.currentTime = Math.floor(
                (initialProgress / 100) * video.duration
              );
            }
            self.hasSeekedInitial = true;
          }
        } catch (e) {
          self.log(`Error in loadedmetadata: ${e.message}`);
        }
      });

      video.addEventListener("timeupdate", function () {
        try {
          if (
            video.duration > 0 &&
            window.reader &&
            typeof window.reader.post === "function" &&
            !self.disableProgress
          ) {
            var currentTime = video.currentTime;
            if (Math.abs(currentTime - self.lastSaveTime) >= 5) {
              self.lastSaveTime = currentTime;
              var progressInt = Math.floor(
                (currentTime / video.duration) * 100
              );
              window.reader.post({
                type: "save",
                data: progressInt,
              });
            }
          }
        } catch (e) {
          // skip
        }
      });

      video.addEventListener("ended", function () {
        self.log("Video ended");
        try {
          if (window.reader && typeof window.reader.post === "function") {
            // mark as completed
            if (!self.disableProgress) {
              window.reader.post({
                type: "save",
                data: 100,
              });
            }
            // move to next chapter
            if (window.reader.nextChapter) {
              self.log("Moving to next chapter");
              window.reader.post({ type: "next" });
            }
          }
        } catch (e) {
          self.log(`Error in ended event: ${e.message}`);
        }
      });

      video.addEventListener("error", (e) => {
        self.log(
          `Video error: ${video.error ? video.error.message : "Unknown"}`
        );
      });
    }

    generateHTML5Video() {
      const video = document.createElement("video");
      video.controls = true;
      video.playsInline = true;
      video.preload = "auto";
      return video;
    }

    generateHTMLVideo(metaPlayerType) {
      // In the future, parse metaPlayerType (e.g. 'artplayer') and return different wrapper
      return this.generateHTML5Video();
    }

    playDirect(url) {
      this.ensureInit();
      this.log(`playDirect called with ${url}`);
      if (this.isDownloadMode()) {
        return this.startDownload(() => this.downloadDirect(url));
      }
      this.destroyCurrentMedia();

      const playerTypeMeta = document.querySelector(
        'meta[name="lnreader-player-type"]'
      );
      const playerType = playerTypeMeta ? playerTypeMeta.content : "html5";

      this.videoElement = this.generateHTMLVideo(playerType);
      this.videoElement.src = url;
      this.attachEventListeners(this.videoElement);
      this.container.appendChild(this.videoElement);

      this.videoElement
        .play()
        .catch((e) => this.log(`Auto-play prevented: ${e.message}`));
    }

    playHls(url, customHlsConfig = {}) {
      this.ensureInit();
      this.log(`playHls called with ${url}`);
      if (this.isDownloadMode()) {
        return this.startDownload(() => this.downloadHls(url, customHlsConfig));
      }
      this.destroyCurrentMedia();

      const playerTypeMeta = document.querySelector(
        'meta[name="lnreader-player-type"]'
      );
      const playerType = playerTypeMeta ? playerTypeMeta.content : "html5";

      this.videoElement = this.generateHTMLVideo(playerType);
      this.attachEventListeners(this.videoElement);
      this.container.appendChild(this.videoElement);

      if (window.Hls && Hls.isSupported()) {
        this.log("Hls.js is supported");
        const config = Object.assign(
          {
            debug: this.isDebugMode,
          },
          customHlsConfig
        );

        this.hlsInstance = new Hls(config);
        this.hlsInstance.loadSource(url);
        this.hlsInstance.attachMedia(this.videoElement);

        this.hlsInstance.on(Hls.Events.MANIFEST_PARSED, () => {
          this.log("HLS manifest parsed, playing...");
          this.videoElement
            .play()
            .catch((e) => this.log(`Auto-play prevented: ${e.message}`));
        });

        this.hlsInstance.on(Hls.Events.ERROR, (event, data) => {
          if (data.fatal) {
            this.log(`Fatal HLS error: ${data.type} - ${data.details}`);
            switch (data.type) {
              case Hls.ErrorTypes.NETWORK_ERROR:
                this.log("Fatal network error encountered, try to recover");
                this.hlsInstance.startLoad();
                break;
              case Hls.ErrorTypes.MEDIA_ERROR:
                this.log("Fatal media error encountered, try to recover");
                this.hlsInstance.recoverMediaError();
                break;
              default:
                this.destroyCurrentMedia();
                break;
            }
          } else {
            this.log(`HLS error: ${data.details}`);
          }
        });
      } else if (
        this.videoElement.canPlayType("application/vnd.apple.mpegurl")
      ) {
        this.log("Native HLS playback supported (Safari/iOS)");
        this.videoElement.src = url;
        this.videoElement.addEventListener("loadedmetadata", () => {
          this.videoElement
            .play()
            .catch((e) => this.log(`Auto-play prevented: ${e.message}`));
        });
      } else {
        this.log("HLS not supported on this platform");
      }
    }

    playIframe(url) {
      this.ensureInit();
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
        this.log("Invalid iframe URL");
        return;
      }
      if (iframeUrl.protocol !== "http:" && iframeUrl.protocol !== "https:") {
        this.log(`Unsupported iframe protocol: ${iframeUrl.protocol}`);
        return;
      }
      this.destroyCurrentMedia();

      this.iframeElement = document.createElement("iframe");
      this.iframeElement.src = iframeUrl.href;
      // Using sandbox without allow-popups and allow-popups-to-escape-sandbox
      // will effectively block window.open and target="_blank"
      this.iframeElement.sandbox =
        "allow-scripts allow-same-origin allow-presentation";

      // Additional attributes requested
      this.iframeElement.allowFullscreen = true;
      this.iframeElement.setAttribute("webkitallowfullscreen", "true");
      this.iframeElement.setAttribute("mozallowfullscreen", "true");
      this.iframeElement.setAttribute("allowfullscreen", "true");

      this.iframeElement.onload = () => {
        this.log("Iframe loaded");
      };

      this.iframeElement.onerror = () => {
        this.log("Iframe failed to load");
      };

      this.container.appendChild(this.iframeElement);
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
