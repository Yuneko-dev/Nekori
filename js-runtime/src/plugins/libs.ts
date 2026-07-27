/**
 * The `@libs/*` surface, cut down to what the M0 spike's plugin actually requires.
 *
 * This is **not** the M1 module surface. M1 adds cookies, storage, crypto and the rest. Network
 * integration must keep React Native's complete Fetch implementation and configure its
 * `OkHttpClientProvider` to use Tsundoku's client; it must not replace Fetch with a narrow
 * TurboModule request function.
 */

/** `lnreader/src/plugins/types/index.ts:65`. */
export const NovelStatus = {
  Unknown: 'Unknown',
  Ongoing: 'Ongoing',
  Completed: 'Completed',
  Licensed: 'Licensed',
  PublishingFinished: 'Publishing Finished',
  Cancelled: 'Cancelled',
  OnHiatus: 'On Hiatus',
} as const;

/** `lnreader/src/plugins/types/filterTypes.ts:5`. */
export const FilterTypes = {
  TextInput: 'Text',
  Picker: 'Picker',
  CheckboxGroup: 'Checkbox',
  Switch: 'Switch',
  ExcludableCheckboxGroup: 'XCheckbox',
} as const;

/** `lnreader/src/plugins/types/index.ts:75`. */
export const ContentWarning = {
  UNSPECIFIED: 0,
  SAFE: 1,
  MIXED: 2,
  NSFW: 3,
} as const;

/** `lnreader/src/plugins/types/index.ts:82`. */
export const ContentType = {
  NOVEL: 'novel',
  IMAGE: 'image',
  VIDEO: 'video',
  MIXED: 'mixed',
} as const;

/** `lnreader/src/plugins/helpers/constants.ts:1`. */
export const defaultCover =
  'https://github.com/Yuneko-dev/lnreader-plugins/blob/master/public/static/coverNotAvailable.webp?raw=true';

/**
 * Spike-grade `@libs/fetch`.
 *
 * This forwards the full React Native Fetch contract unchanged. `fetchText` is only the plugin
 * helper that explicitly asks for text; it is not a native transport.
 */
export const fetchApi: typeof fetch = (input, init) => fetch(input, init);

export const fetchText = async (input: RequestInfo, init?: RequestInit): Promise<string> => {
  const response = await fetchApi(input, init);
  if (!response.ok) {
    throw new Error(`fetchText failed: HTTP ${response.status}`);
  }
  return response.text();
};
