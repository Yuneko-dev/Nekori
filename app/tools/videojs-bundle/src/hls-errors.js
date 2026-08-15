import { MediaError } from "@videojs/media";
import Hls from "hls.js";

const hlsErrorTypeToCode = {
  [Hls.ErrorTypes.NETWORK_ERROR]: MediaError.MEDIA_ERR_NETWORK,
  [Hls.ErrorTypes.MEDIA_ERROR]: MediaError.MEDIA_ERR_DECODE,
  [Hls.ErrorTypes.KEY_SYSTEM_ERROR]: MediaError.MEDIA_ERR_ENCRYPTED,
  [Hls.ErrorTypes.MUX_ERROR]: MediaError.MEDIA_ERR_DECODE,
  [Hls.ErrorTypes.OTHER_ERROR]: MediaError.MEDIA_ERR_CUSTOM,
};

export function HlsJsMediaErrorsMixin(BaseClass) {
  return class extends BaseClass {
    #disconnect = null;
    #error = null;
    #networkRecovered = false;
    #mediaRecovered = false;
    #manifestUrl = null;

    constructor(...args) {
      super(...args);
      this.engine?.on(Hls.Events.MANIFEST_LOADING, (_event, data) => {
        if (!data?.url || data.url === this.#manifestUrl) return;
        this.#manifestUrl = data.url;
        this.#error = null;
        this.#networkRecovered = false;
        this.#mediaRecovered = false;
      });
      this.engine?.on(Hls.Events.DESTROYING, () => this.#destroy());
      this.#init();
    }

    get error() {
      return this.#error;
    }

    #destroy() {
      this.#disconnect?.abort();
      this.#disconnect = null;
    }

    #init() {
      this.#destroy();
      this.#disconnect = new AbortController();
      const { engine } = this;
      if (!engine) return;

      const onError = (_event, data) => {
        if (!data?.fatal) return;
        if (data.type === Hls.ErrorTypes.NETWORK_ERROR && !this.#networkRecovered) {
          this.#networkRecovered = true;
          engine.startLoad();
          return;
        }
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !this.#mediaRecovered) {
          this.#mediaRecovered = true;
          engine.recoverMediaError();
          return;
        }

        const code = hlsErrorTypeToCode[data.type] ?? MediaError.MEDIA_ERR_CUSTOM;
        const error = new MediaError(data.error?.message, code, true, data.details);
        error.data = data;
        this.#error = error;
        this.dispatchEvent(new ErrorEvent("error", { error, message: error.message }));
      };

      engine.on(Hls.Events.ERROR, onError);
      this.#disconnect.signal.addEventListener(
        "abort",
        () => {
          engine.off(Hls.Events.ERROR, onError);
          this.#error = null;
        },
        { once: true },
      );
    }
  };
}
