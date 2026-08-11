(function () {
  "use strict";

  const configElement = document.getElementById("lnreader-compat-config");
  const config = configElement
    ? JSON.parse(configElement.textContent || "{}")
    : {};
  const chapterElement = document.getElementById("LNReader-chapter");
  const rawHTML = chapterElement ? chapterElement.innerHTML : "";

  function mock(overrides) {
    const fixed = overrides || {};
    let proxy;
    const target = function () {};
    proxy = new Proxy(target, {
      get: function (_, property) {
        if (property === "then") return undefined;
        if (property === Symbol.toPrimitive)
          return function () {
            return "";
          };
        if (property === "toString")
          return function () {
            return "";
          };
        return Object.prototype.hasOwnProperty.call(fixed, property)
          ? fixed[property]
          : proxy;
      },
      apply: function () {
        return proxy;
      },
      construct: function () {
        return proxy;
      },
      set: function () {
        return true;
      },
    });
    return proxy;
  }

  function state(value) {
    return mock({ val: value });
  }

  window.ReactNativeWebView = {
    postMessage: function (message) {
      if (
        window.Android &&
        typeof window.Android.onReaderMessage === "function"
      ) {
        window.Android.onReaderMessage(String(message));
      }
    },
  };

  window.van = mock();
  window.tts = mock({ started: false, reading: false });
  window.pageReader = mock({
    page: state(1),
    totalPages: state(1),
  });

  const originalFetch = window.fetch.bind(window);
  const originalFetchSymbol = Symbol("tsundoku.reader.originalFetch");
  const proxyUnavailableError =
    "This plugin cannot use window.reader.fetch because the local proxy is disabled. " +
    "Go to Settings > Advanced > Novel WebView, enable Allow local proxy API, then reopen the reader.";

  const reader = {
    novel: config.novel || {},
    chapter: config.chapter || {},
    nextChapter: config.nextChapter || null,
    autoSaveInterval: config.autoSaveInterval || 5000,
    rawHTML: rawHTML,
    strings: config.strings || {},
    generalSettings: state({}),
    readerSettings: state({}),
    batteryLevel: state(0),
    hidden: state(false),
    post: function (message) {
      if (!message || typeof message !== "object") return;
      const prototype = Object.getPrototypeOf(message);
      if (prototype !== Object.prototype && prototype !== null) return;
      window.ReactNativeWebView.postMessage(JSON.stringify(message));
    },
    refetch: function () {
      reader.post({ type: "refetch" });
    },
    fetch: function (url, init) {
      if (!config.proxyEndpoint) {
        reader.post({ type: "proxyUnavailable", data: proxyUnavailableError });
        return Promise.reject(new Error(proxyUnavailableError));
      }
      const requestInit = init || {};
      const incomingHeaders = new Headers(requestInit.headers || {});
      const forwardedHeaders = new Headers();
      incomingHeaders.forEach(function (value, name) {
        forwardedHeaders.set(
          "x-ln-forward-header-" + name.toLowerCase(),
          value
        );
      });
      return originalFetch(
        config.proxyEndpoint + "?url=" + encodeURIComponent(String(url)),
        Object.assign({}, requestInit, { headers: forwardedHeaders })
      );
    },
  };

  Object.defineProperties(reader, {
    chapterElement: {
      get: function () {
        return chapterElement;
      },
    },
    viewport: {
      get: function () {
        return document.querySelector('meta[name="viewport"]');
      },
    },
    selection: {
      get: function () {
        return window.getSelection();
      },
    },
    paddingTop: {
      get: function () {
        return chapterElement
          ? parseFloat(getComputedStyle(chapterElement).paddingTop) || 0
          : 0;
      },
    },
    layoutHeight: {
      get: function () {
        return document.documentElement.clientHeight;
      },
    },
    layoutWidth: {
      get: function () {
        return document.documentElement.clientWidth;
      },
    },
    chapterHeight: {
      get: function () {
        return chapterElement ? chapterElement.scrollHeight : 0;
      },
    },
    chapterWidth: {
      get: function () {
        return chapterElement ? chapterElement.scrollWidth : 0;
      },
    },
  });

  Object.defineProperty(reader, originalFetchSymbol, { value: originalFetch });
  window.reader = reader;
  window.LNReaderPlayer = undefined;
})();
