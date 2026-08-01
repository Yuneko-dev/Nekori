/* eslint-disable */

// core-player.js

(function () {
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

        if (urlMeta && typeMeta) {
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
          }
        } else {
          this.log("Direct mode missing url or type meta tag");
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
