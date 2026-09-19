/**
 * A small React Native `react-native-quick-crypto` backend with an API shaped like
 * `@noble/ciphers/aes.js` for common AES modes.
 *
 * Supported:
 *   - ctr(key, nonce)
 *   - cbc(key, iv, { disablePadding? })
 *   - ecb(key, { disablePadding? })
 *   - cfb(key, iv)
 *   - gcm(key, nonce, aad?)
 *
 * Not implemented on purpose: gcmsiv, aessiv, aeskw/aeskwp, FF1, CMAC, DRBG.
 *
 * Notes on compatibility with @noble/ciphers:
 *   - AES-128/192/256 are selected from key length (16/24/32 bytes).
 *   - CBC/ECB use PKCS#7 padding by default; `{ disablePadding: true }` disables it.
 *   - GCM returns `ciphertext || 16-byte-tag`, just like noble-ciphers.
 *   - `encrypt()` is single-use per cipher instance, matching noble's wrapped cipher API.
 *   - Common non-AEAD modes accept an optional destination Uint8Array.
 * 
 * This file will also be copied as a version for Nekori-plugins.
 */

import { Buffer } from 'buffer'; // react-native-nitro-buffer
import { createCipheriv, createDecipheriv } from 'crypto'; // react-native-quick-crypto

const AES_BLOCK_SIZE = 16;
const GCM_TAG_LENGTH = 16;

export type BlockOpts = {
  /** Disable PKCS#7 padding and require exact 16-byte blocks. */
  disablePadding?: boolean;
};

export type Cipher = {
  encrypt(data: Uint8Array): Uint8Array;
  decrypt(data: Uint8Array): Uint8Array;
};

export type CipherWithOutput = {
  encrypt(data: Uint8Array, output?: Uint8Array): Uint8Array;
  decrypt(data: Uint8Array, output?: Uint8Array): Uint8Array;
};

type CryptoResult = Uint8Array;

type BasicCipherContext = {
  update(data: Uint8Array): CryptoResult;
  final(): CryptoResult;
  setAutoPadding(autoPadding?: boolean): unknown;
};

type GcmCipherContext = {
  update(data: Uint8Array): CryptoResult;
  final(): CryptoResult;
  setAAD(data: Uint8Array): unknown;
  getAuthTag(): CryptoResult;
};

type GcmDecipherContext = {
  update(data: Uint8Array): CryptoResult;
  final(): CryptoResult;
  setAAD(data: Uint8Array): unknown;
  setAuthTag(tag: Uint8Array): unknown;
};

type BasicMode = 'ctr' | 'cbc' | 'ecb' | 'cfb';

type CipherFactoryMeta = {
  blockSize: number;
  nonceLength?: number;
  tagLength?: number;
  withAAD?: true;
  varSizeNonce?: true;
};

function abytes(value: unknown, name = 'data'): asserts value is Uint8Array {
  if (!(value instanceof Uint8Array)) {
    throw new TypeError(`"${name}" expected Uint8Array`);
  }
}

function validateKey(key: Uint8Array): 128 | 192 | 256 {
  abytes(key, 'aes key');
  if (key.length === 16) return 128;
  if (key.length === 24) return 192;
  if (key.length === 32) return 256;
  throw new Error(`"aes key" expected Uint8Array of length 16/24/32, got length=${key.length}`);
}

function validateExactLength(value: Uint8Array, length: number, name: string): void {
  abytes(value, name);
  if (value.length !== length) {
    throw new Error(`"${name}" expected Uint8Array of length ${length}, got length=${value.length}`);
  }
}

/** Zero-copy Buffer view of a Uint8Array. Quick Crypto does not mutate it. */
function asBuffer(value: Uint8Array): Buffer {
  return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
}

/** Return a standalone Uint8Array rather than exposing Buffer-specific behavior. */
function asUint8Array(value: Uint8Array): Uint8Array {
  return Uint8Array.from(value);
}

function concat(...chunks: Uint8Array[]): Uint8Array {
  let length = 0;
  for (const chunk of chunks) length += chunk.length;
  const out = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

function algorithmFor(key: Uint8Array, mode: BasicMode | 'gcm'): string {
  return `aes-${validateKey(key)}-${mode}`;
}

function ensureAlignedOutput(output: Uint8Array): void {
  abytes(output, 'output');
  // Noble's AES CipherWithOutput implementations require 4-byte aligned destinations.
  if (output.byteOffset % 4 !== 0) throw new Error('invalid output, must be aligned');
}

function writeOutput(result: Uint8Array, output?: Uint8Array, expectedLength = result.length): Uint8Array {
  if (output === undefined) return asUint8Array(result);
  ensureAlignedOutput(output);
  if (output.length !== expectedLength) {
    throw new RangeError(`"output" expected Uint8Array of length ${expectedLength}, got length=${output.length}`);
  }
  if (result.length > output.length) {
    throw new RangeError('output is too small');
  }
  output.set(result, 0);
  return output.subarray(0, result.length);
}

function runBasic(
  mode: BasicMode,
  encrypting: boolean,
  key: Uint8Array,
  iv: Uint8Array | null,
  input: Uint8Array,
  autoPadding: boolean,
): Uint8Array {
  abytes(input);
  const algorithm = algorithmFor(key, mode);
  const keyBuffer = asBuffer(key);

  // react-native-quick-crypto's typings don't accept `null` for IV even
  // though ECB follows Node's runtime API and requires a null IV. Keep the
  // null cast isolated to the ECB branch instead of leaking Buffer | null
  // into every createCipheriv/createDecipheriv call.
  const ctx = (
    mode === 'ecb'
      ? encrypting
        ? createCipheriv(algorithm, keyBuffer, null as unknown as Buffer)
        : createDecipheriv(algorithm, keyBuffer, null as unknown as Buffer)
      : encrypting
        ? createCipheriv(algorithm, keyBuffer, asBuffer(iv as Uint8Array))
        : createDecipheriv(algorithm, keyBuffer, asBuffer(iv as Uint8Array))
  ) as unknown as BasicCipherContext;

  if (mode === 'cbc' || mode === 'ecb') ctx.setAutoPadding(autoPadding);

  const a = ctx.update(asBuffer(input));
  const b = ctx.final();
  return concat(a, b);
}

function makeStreamCipher(mode: 'ctr' | 'cfb', key: Uint8Array, iv: Uint8Array): CipherWithOutput {
  validateKey(key);
  validateExactLength(iv, AES_BLOCK_SIZE, mode === 'ctr' ? 'nonce' : 'iv');
  let encrypted = false;

  const process = (data: Uint8Array, encrypting: boolean, output?: Uint8Array): Uint8Array => {
    abytes(data);
    if (output !== undefined) {
      ensureAlignedOutput(output);
      if (output.length !== data.length) {
        throw new RangeError(`"output" expected Uint8Array of length ${data.length}, got length=${output.length}`);
      }
    }
    const result = runBasic(mode, encrypting, key, iv, data, false);
    return writeOutput(result, output, data.length);
  };

  return {
    encrypt(data, output) {
      if (encrypted) throw new Error('cannot encrypt() twice with same key + nonce');
      encrypted = true;
      return process(data, true, output);
    },
    decrypt(data, output) {
      return process(data, false, output);
    },
  };
}

function makeBlockCipher(
  mode: 'cbc' | 'ecb',
  key: Uint8Array,
  iv: Uint8Array | null,
  opts: BlockOpts,
): CipherWithOutput {
  validateKey(key);
  if (mode === 'cbc') validateExactLength(iv as Uint8Array, AES_BLOCK_SIZE, 'iv');

  const autoPadding = !opts.disablePadding;
  let encrypted = false;

  return {
    encrypt(data, output) {
      if (encrypted) throw new Error('cannot encrypt() twice with same key + nonce');
      encrypted = true;
      abytes(data);

      if (!autoPadding && data.length % AES_BLOCK_SIZE !== 0) {
        throw new Error(`plaintext must be multiple of ${AES_BLOCK_SIZE}`);
      }

      const expectedLength = autoPadding
        ? data.length + (AES_BLOCK_SIZE - (data.length % AES_BLOCK_SIZE || 0))
        : data.length;

      if (output !== undefined) {
        ensureAlignedOutput(output);
        if (output.length !== expectedLength) {
          throw new RangeError(
            `"output" expected Uint8Array of length ${expectedLength}, got length=${output.length}`,
          );
        }
      }

      const result = runBasic(mode, true, key, iv, data, autoPadding);
      return writeOutput(result, output, expectedLength);
    },

    decrypt(data, output) {
      abytes(data);
      if (data.length % AES_BLOCK_SIZE !== 0) {
        throw new Error(`ciphertext must be multiple of ${AES_BLOCK_SIZE}`);
      }
      if (autoPadding && data.length === 0) {
        throw new Error('pkcs7: empty ciphertext not allowed');
      }

      // Noble requires a destination buffer as large as the ciphertext, even
      // though PKCS#7 unpadding may make the returned subarray shorter.
      if (output !== undefined) {
        ensureAlignedOutput(output);
        if (output.length !== data.length) {
          throw new RangeError(`"output" expected Uint8Array of length ${data.length}, got length=${output.length}`);
        }
      }

      let result: Uint8Array;
      try {
        result = runBasic(mode, false, key, iv, data, autoPadding);
      } catch (error) {
        if (autoPadding) {
          const wrapped = new Error('aes: bad decrypt');
          (wrapped as Error & { cause?: unknown }).cause = error;
          throw wrapped;
        }
        throw error;
      }
      return writeOutput(result, output, output?.length ?? result.length);
    },
  };
}

type CtrFactory = ((key: Uint8Array, nonce: Uint8Array) => CipherWithOutput) & {
  blockSize: 16;
  nonceLength: 16;
};

export const ctr: CtrFactory = Object.assign(
  (key: Uint8Array, nonce: Uint8Array) => makeStreamCipher('ctr', key, nonce),
  { blockSize: 16 as const, nonceLength: 16 as const },
);

type CfbFactory = ((key: Uint8Array, iv: Uint8Array) => CipherWithOutput) & {
  blockSize: 16;
  nonceLength: 16;
};

export const cfb: CfbFactory = Object.assign(
  (key: Uint8Array, iv: Uint8Array) => makeStreamCipher('cfb', key, iv),
  { blockSize: 16 as const, nonceLength: 16 as const },
);

type CbcFactory = ((key: Uint8Array, iv: Uint8Array, opts?: BlockOpts) => CipherWithOutput) & {
  blockSize: 16;
  nonceLength: 16;
};

export const cbc: CbcFactory = Object.assign(
  (key: Uint8Array, iv: Uint8Array, opts: BlockOpts = {}) => makeBlockCipher('cbc', key, iv, opts),
  { blockSize: 16 as const, nonceLength: 16 as const },
);

type EcbFactory = ((key: Uint8Array, opts?: BlockOpts) => CipherWithOutput) & {
  blockSize: 16;
};

export const ecb: EcbFactory = Object.assign(
  (key: Uint8Array, opts: BlockOpts = {}) => makeBlockCipher('ecb', key, null, opts),
  { blockSize: 16 as const },
);

type GcmFactory = ((key: Uint8Array, nonce: Uint8Array, aad?: Uint8Array) => Cipher) & {
  blockSize: 16;
  nonceLength: 12;
  tagLength: 16;
  withAAD: true;
  varSizeNonce: true;
};

export const gcm: GcmFactory = Object.assign(
  (key: Uint8Array, nonce: Uint8Array, aad?: Uint8Array): Cipher => {
    validateKey(key);
    abytes(nonce, 'nonce');
    if (nonce.length < 8) throw new Error('aes/gcm: invalid nonce length');
    if (aad !== undefined) abytes(aad, 'AAD');

    const algorithm = algorithmFor(key, 'gcm');
    let encrypted = false;

    return {
      encrypt(plaintext: Uint8Array): Uint8Array {
        if (encrypted) throw new Error('cannot encrypt() twice with same key + nonce');
        encrypted = true;
        abytes(plaintext, 'data');

        const cipher = createCipheriv(
          algorithm,
          asBuffer(key),
          asBuffer(nonce),
        ) as unknown as GcmCipherContext;

        if (aad !== undefined) cipher.setAAD(asBuffer(aad));

        const a = cipher.update(asBuffer(plaintext));
        const b = cipher.final();
        const tag = cipher.getAuthTag();
        return concat(a, b, tag);
      },

      decrypt(ciphertext: Uint8Array): Uint8Array {
        abytes(ciphertext, 'data');
        if (ciphertext.length < GCM_TAG_LENGTH) {
          throw new Error(`"ciphertext" expected length >= tagLength=${GCM_TAG_LENGTH}`);
        }

        const body = ciphertext.subarray(0, ciphertext.length - GCM_TAG_LENGTH);
        const tag = ciphertext.subarray(ciphertext.length - GCM_TAG_LENGTH);
        const decipher = createDecipheriv(
          algorithm,
          asBuffer(key),
          asBuffer(nonce),
        ) as unknown as GcmDecipherContext;

        if (aad !== undefined) decipher.setAAD(asBuffer(aad));
        decipher.setAuthTag(asBuffer(tag));

        try {
          const a = decipher.update(asBuffer(body));
          const b = decipher.final();
          return concat(a, b);
        } catch (error) {
          const wrapped = new Error('aes-gcm: invalid tag');
          (wrapped as Error & { cause?: unknown }).cause = error;
          throw wrapped;
        }
      },
    };
  },
  {
    blockSize: 16 as const,
    nonceLength: 12 as const,
    tagLength: 16 as const,
    withAAD: true as const,
    varSizeNonce: true as const,
  } satisfies CipherFactoryMeta,
);

// @noble/ciphers/utils.js
// https://github.com/paulmillr/noble-ciphers/blob/main/src/utils.ts
/**
 * Converts string to bytes using UTF8 encoding.
 * Returns a fresh plain Uint8Array copy.
 */
export function utf8ToBytes(str: string): Uint8Array {
  if (typeof str !== 'string') {
    throw new TypeError('string expected');
  }

  // Buffer.from(str) allocates fresh storage.
  // new Uint8Array(buffer) creates another detached plain Uint8Array copy,
  // so the returned value is not a Buffer and doesn't share its backing store.
  return new Uint8Array(Buffer.from(str, 'utf8'));
}

/**
 * Converts UTF-8 bytes to string.
 * Malformed UTF-8 is replacement-decoded with U+FFFD,
 * matching TextDecoder's default behavior.
 */
export function bytesToUtf8(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString('utf8');
}