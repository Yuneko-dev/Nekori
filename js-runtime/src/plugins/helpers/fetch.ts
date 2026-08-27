import { Buffer } from 'buffer';
import { parse as parseProto } from 'protobufjs';

import { getUserAgent } from './nativeHost';

type FetchInit = {
  headers?: Record<string, string> | Headers;
  method?: string;
  body?: FormData | string;
  [x: string]: string | Record<string, string> | undefined | FormData | Headers;
};

const makeInit = (init?: FetchInit) => {
  const defaultHeaders = {
    Connection: 'keep-alive',
    Accept: '*/*',
    'Accept-Language': '*',
    'Sec-Fetch-Mode': 'cors',
    'Accept-Encoding': 'gzip, deflate',
    'Cache-Control': 'max-age=0',
    'User-Agent': getUserAgent(),
  };
  if (init?.headers) {
    if (init.headers instanceof Headers) {
      if (!init.headers.get('User-Agent') && defaultHeaders['User-Agent']) {
        init.headers.set('User-Agent', defaultHeaders['User-Agent']);
      }
    } else {
      init.headers = {
        ...defaultHeaders,
        ...init.headers,
      };
    }
  } else {
    init = {
      ...init,
      headers: defaultHeaders,
    };
  }
  return init;
};

export const fetchApi = async (
  url: string,
  init?: FetchInit,
): Promise<Response> => {
  init = makeInit(init);
  return await fetch(url, init);
};

/**
 *
 * @param url
 * @param init
 * @param encoding link: https://developer.mozilla.org/en-US/docs/Web/API/TextDecoder/encoding
 * @returns plain text
 */
export const fetchText = async (
  url: string,
  init?: FetchInit,
  encoding?: string,
): Promise<string> => {
  init = makeInit(init);
  try {
    const res = await fetch(url, init as RequestInit);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const arrayBuffer = await res.arrayBuffer();
    const decoder = new TextDecoder(encoding);
    const result = decoder.decode(arrayBuffer);
    return result;
  } catch (e) {
    console.error(`fetchText failed for ${url}:`, e);
    return '';
  }
};

interface ProtoRequestInit {
  // merged .proto file
  proto: string;
  requestType: string;
  requestData?: Record<string, unknown>;
  responseType: string;
}

/**
 * Encodes and decodes the gRPC-web frame used by LNReader protobuf plugins while still sending the
 * request through RN Fetch. Trailer frames are ignored; the first uncompressed data frame wins.
 */
export const fetchProto = async (
  config: ProtoRequestInit,
  url: string,
  init?: FetchInit,
): Promise<Record<string, unknown>> => {
  init = makeInit(init);
  const root = parseProto(config.proto).root;
  const requestType = root.lookupType(config.requestType);
  const requestData = config.requestData ?? {};
  const validationError = requestType.verify(requestData);
  if (validationError) {
    throw new Error(`Invalid ${config.requestType}: ${validationError}`);
  }

  const payload = requestType.encode(requestType.create(requestData)).finish();
  const frame = Buffer.allocUnsafe(payload.length + 5);
  frame[0] = 0;
  frame.writeUInt32BE(payload.length, 1);
  frame.set(payload, 5);

  const headers = new Headers(init.headers);
  headers.set('Content-Type', 'application/grpc-web+proto');
  headers.set('X-Binary-Base64', 'true');
  const response = await fetch(url, {
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
      const decoded = responseType.decode(
        bytes.subarray(offset, offset + length),
      );
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
