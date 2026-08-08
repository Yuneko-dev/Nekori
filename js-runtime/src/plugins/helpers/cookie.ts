// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Preeternal

import CookieManagerNative, {
  type Cookie,
  type Cookies,
  type CookieSameSite,
} from '../../../specs/NativeCookieManager';

export type IOSCookieStore = 'foundation' | 'webKit' | 'both';

export type RemoveSessionCookiesOptions = {
  iosCookieStore?: IOSCookieStore;
};

const removeSessionCookies = (
  options: RemoveSessionCookiesOptions = {},
): Promise<boolean> => {
  switch (options.iosCookieStore ?? 'both') {
    case 'foundation':
      return CookieManagerNative.removeSessionCookies(true, false);
    case 'webKit':
      return CookieManagerNative.removeSessionCookies(false, true);
    case 'both':
      return CookieManagerNative.removeSessionCookies(true, true);
    default:
      return Promise.reject(
        new Error('iosCookieStore must be "foundation", "webKit", or "both"'),
      );
  }
};

const CookieManager = {
  getAll: (useWebKit = false) => CookieManagerNative.getAll(useWebKit),
  getAllAsArray: (useWebKit = false) =>
    CookieManagerNative.getAllAsArray(useWebKit),
  clearAll: (useWebKit = false) => CookieManagerNative.clearAll(useWebKit),
  clearAllStores: () => CookieManagerNative.clearAllStores(),
  get: (url: string, useWebKit = false) =>
    CookieManagerNative.getCookies(url, useWebKit),
  getAsArray: (url: string, useWebKit = false) =>
    CookieManagerNative.getAsArray(url, useWebKit),
  getCookieHeader: (url: string, useWebKit = false) =>
    CookieManagerNative.getCookieHeader(url, useWebKit),
  set: (url: string, cookie: Cookie, useWebKit = false) =>
    CookieManagerNative.setCookie(url, cookie, useWebKit),
  clearByName: (url: string, name: string, useWebKit = false) =>
    CookieManagerNative.clearByName(url, name, useWebKit),
  // Upstream guards this with `Platform.OS === 'android'`. This runtime only ever runs on Android,
  // and the guard was the sole reason to import from the `react-native` barrel — which costs the
  // bundle every RN component. See the note in specs/NativeHostApi.ts.
  flush: () => CookieManagerNative.flush(),
  removeSessionCookies,
  setFromResponse: (url: string, cookie: string) =>
    CookieManagerNative.setFromResponse(url, cookie),
  /**
   * @deprecated Make the request with `fetch`/Axios and then call `get()`.
   * This upstream-compatible method performs its own GET request.
   */
  getFromResponse: (url: string) => CookieManagerNative.getFromResponse(url),
};

export type { Cookie, Cookies, CookieSameSite };
export default CookieManager;
