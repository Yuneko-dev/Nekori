import type { Spec } from '../../../specs/NativeHostApi';

function nativeHostApi(): Spec {
  return require('../../../specs/NativeHostApi').default as Spec;
}

export function getUserAgent(): string {
  return nativeHostApi().getUserAgent();
}
