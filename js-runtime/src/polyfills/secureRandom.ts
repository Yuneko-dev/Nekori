import type { Spec } from '../../specs/NativeHostApi';

type RandomValuesArray =
  | Int8Array
  | Uint8Array
  | Uint8ClampedArray
  | Int16Array
  | Uint16Array
  | Int32Array
  | Uint32Array;

type CryptoWithRandomValues = {
  getRandomValues<T extends RandomValuesArray>(array: T): T;
};

declare const global: {
  crypto?: Partial<CryptoWithRandomValues>;
};

function nativeHostApi(): Spec {
  // Resolved lazily for the same reason as the command bridge: module-scope resolution runs before
  // React Native has populated the TurboModule registry.

  return require('../../specs/NativeHostApi').default as Spec;
}

function getRandomValues<T extends RandomValuesArray>(array: T): T {
  const isIntegerArray =
    array instanceof Int8Array ||
    array instanceof Uint8Array ||
    array instanceof Uint8ClampedArray ||
    array instanceof Int16Array ||
    array instanceof Uint16Array ||
    array instanceof Int32Array ||
    array instanceof Uint32Array;
  if (!isIntegerArray) {
    const error = new Error('Expected an integer typed array');
    error.name = 'TypeMismatchError';
    throw error;
  }
  if (array.byteLength > 65_536) {
    const error = new Error(
      'getRandomValues cannot fill more than 65536 bytes',
    );
    error.name = 'QuotaExceededError';
    throw error;
  }

  const binary = atob(nativeHostApi().getRandomBase64(array.byteLength));
  const target = new Uint8Array(
    array.buffer,
    array.byteOffset,
    array.byteLength,
  );
  for (let index = 0; index < binary.length; index += 1) {
    target[index] = binary.charCodeAt(index);
  }
  return array;
}

export function installSecureRandom(): void {
  const crypto = global.crypto ?? {};
  if (typeof crypto.getRandomValues !== 'function') {
    crypto.getRandomValues = getRandomValues;
  }
  global.crypto = crypto;
}

// This module is imported before crypto-browserify. Its randombytes dependency snapshots
// global.crypto during module evaluation, so installing later in index.ts would permanently select
// its unsupported-browser branch.
installSecureRandom();
