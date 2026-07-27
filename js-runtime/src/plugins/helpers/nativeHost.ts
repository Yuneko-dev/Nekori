import type { Spec } from "../../../specs/NativeHostApi";

function nativeHostApi(): Spec {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  return require("../../../specs/NativeHostApi").default as Spec;
}

export function getUserAgent(): string {
  return nativeHostApi().getUserAgent();
}
