export function toBytes(value: ArrayBuffer | Uint8Array): Uint8Array {
  return value instanceof Uint8Array ? value : new Uint8Array(value);
}

export function boxType(bytes: Uint8Array): string {
  return String.fromCharCode(bytes[4] ?? 0, bytes[5] ?? 0, bytes[6] ?? 0, bytes[7] ?? 0);
}

export function sameBytes(left: Uint8Array | undefined, right: Uint8Array | null): boolean {
  return Boolean(left && right && left.byteLength === right.byteLength && left.every((value, index) => value === right[index]));
}
