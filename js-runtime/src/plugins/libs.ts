import { Buffer } from 'buffer';

/**
 * Helpers layered on top of the standard React Native Fetch implementation. Network integration
 * configures RN's OkHttpClientProvider with Tsundoku's exact client; none of these functions
 * implement a second transport.
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

export const fetchText = async (
  input: RequestInfo,
  init?: RequestInit,
  encoding = 'utf-8',
): Promise<string> => {
  const response = await fetchApi(input, init);
  const blob = await response.blob();
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = () => reject(reader.error ?? new Error('Could not decode response body'));
    reader.readAsText(blob, encoding);
  });
};

export const fetchFile = async (
  input: RequestInfo,
  init?: RequestInit,
): Promise<string> => {
  const response = await fetchApi(input, init);
  const blob = await response.blob();
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result ?? '');
      resolve(dataUrl.slice(dataUrl.indexOf(',') + 1));
    };
    reader.onerror = () => reject(reader.error ?? new Error('Could not read response body'));
    reader.readAsDataURL(blob);
  });
};

type ProtoRequest = {
  proto: string;
  requestType: string;
  responseType: string;
  requestData: Record<string, unknown>;
};

/**
 * Encodes and decodes the gRPC-web frame used by LNReader protobuf plugins while still sending the
 * request through RN Fetch. Trailer frames are ignored; the first uncompressed data frame wins.
 */
export const fetchProto = async (
  config: ProtoRequest,
  url: string,
  init: RequestInit = {},
): Promise<Record<string, unknown>> => {
  // Metro includes literal requires in the bundle but evaluates them only on first use.
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const protobuf = require('protobufjs') as typeof import('protobufjs');
  const root = protobuf.parse(config.proto).root;
  const requestType = root.lookupType(config.requestType);
  const validationError = requestType.verify(config.requestData);
  if (validationError) {
    throw new Error(`Invalid ${config.requestType}: ${validationError}`);
  }

  const payload = requestType.encode(requestType.create(config.requestData)).finish();
  const frame = Buffer.allocUnsafe(payload.length + 5);
  frame[0] = 0;
  frame.writeUInt32BE(payload.length, 1);
  frame.set(payload, 5);

  const headers = new Headers(init.headers);
  headers.set('Content-Type', 'application/grpc-web+proto');
  headers.set('X-Binary-Base64', 'true');
  const response = await fetchApi(url, {
    ...init,
    method: 'POST',
    headers,
    body: frame.toString('base64'),
  });
  if (!response.ok) {
    throw new Error(`fetchProto failed: HTTP ${response.status}`);
  }

  const bytes = new Uint8Array(await response.arrayBuffer());
  let offset = 0;
  while (offset + 5 <= bytes.length) {
    const flags = bytes[offset];
    const length =
      ((bytes[offset + 1] << 24) |
        (bytes[offset + 2] << 16) |
        (bytes[offset + 3] << 8) |
        bytes[offset + 4]) >>>
      0;
    offset += 5;
    if (offset + length > bytes.length) {
      throw new Error('Truncated gRPC-web response frame');
    }
    if ((flags & 0x80) === 0) {
      if ((flags & 0x01) !== 0) {
        throw new Error('Compressed gRPC-web frames are not supported');
      }
      const responseType = root.lookupType(config.responseType);
      const decoded = responseType.decode(bytes.subarray(offset, offset + length));
      return responseType.toObject(decoded, {
        longs: String,
        enums: String,
        bytes: String,
        defaults: true,
      }) as Record<string, unknown>;
    }
    offset += length;
  }
  throw new Error('gRPC-web response contained no data frame');
};

export const isAbsoluteUrl = (value: string): boolean =>
  value.startsWith('http://') || value.startsWith('https://');

class UnsupportedPluginCapabilityError extends Error {
  readonly code = 'UNSUPPORTED_CAPABILITY';

  constructor(readonly capability: string) {
    super(`Plugin capability "${capability}" is not supported yet`);
    this.name = 'UnsupportedPluginCapabilityError';
  }
}

function unsupportedCloudflare(): never {
  throw new UnsupportedPluginCapabilityError('cloudflare-cdp');
}

export const unsupportedWebView = {
  solveCloudflare: unsupportedCloudflare,
  solveCloudflareTurnstile: unsupportedCloudflare,
};
