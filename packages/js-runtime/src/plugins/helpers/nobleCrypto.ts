import { Buffer } from 'buffer';
import { createCipheriv, createDecipheriv } from 'crypto';

export type BlockOpts = {
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

export type BasicMode = 'ctr' | 'cbc' | 'ecb' | 'cfb';

export type CipherFactoryMeta = {
  blockSize: number;
  nonceLength?: number;
  tagLength?: number;
  withAAD?: true;
  varSizeNonce?: true;
};

export function abytes(
  value: unknown,
  name = 'data',
): asserts value is Uint8Array {
  if (!(value instanceof Uint8Array)) {
    throw new TypeError(`"${name}" expected Uint8Array`);
  }
}

export function validateKey(key: Uint8Array): 128 | 192 | 256 {
  abytes(key, 'aes key');
  if (key.length === 16) return 128;
  if (key.length === 24) return 192;
  if (key.length === 32) return 256;
  throw new Error(
    `"aes key" expected Uint8Array of length 16/24/32, got length=${key.length}`,
  );
}

export function validateExactLength(
  value: Uint8Array,
  length: number,
  name: string,
): void {
  abytes(value, name);
  if (value.length !== length) {
    throw new Error(
      `"${name}" expected Uint8Array of length ${length}, got length=${value.length}`,
    );
  }
}

export function asBuffer(value: Uint8Array): Buffer {
  return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
}

export function asUint8Array(value: Uint8Array): Uint8Array {
  return Uint8Array.from(value);
}

export function concat(...chunks: Uint8Array[]): Uint8Array {
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

export function algorithmFor(key: Uint8Array, mode: BasicMode | 'gcm'): string {
  return `aes-${validateKey(key)}-${mode}`;
}

export function ensureAlignedOutput(output: Uint8Array): void {
  abytes(output, 'output');
  if (output.byteOffset % 4 !== 0)
    throw new Error('invalid output, must be aligned');
}

export function writeOutput(
  result: Uint8Array,
  output?: Uint8Array,
  expectedLength = result.length,
): Uint8Array {
  if (output === undefined) return asUint8Array(result);
  ensureAlignedOutput(output);
  if (output.length !== expectedLength) {
    throw new RangeError(
      `"output" expected Uint8Array of length ${expectedLength}, got length=${output.length}`,
    );
  }
  if (result.length > output.length) {
    throw new RangeError('output is too small');
  }
  output.set(result, 0);
  return output.subarray(0, result.length);
}

export function runBasic(
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
  const ctx = (mode === 'ecb'
    ? encrypting
      ? createCipheriv(algorithm, keyBuffer, null as unknown as Buffer)
      : createDecipheriv(algorithm, keyBuffer, null as unknown as Buffer)
    : encrypting
    ? createCipheriv(algorithm, keyBuffer, asBuffer(iv as Uint8Array))
    : createDecipheriv(
        algorithm,
        keyBuffer,
        asBuffer(iv as Uint8Array),
      )) as unknown as BasicCipherContext;

  if (mode === 'cbc' || mode === 'ecb') ctx.setAutoPadding(autoPadding);

  const a = ctx.update(asBuffer(input));
  const b = ctx.final();
  return concat(a, b);
}
