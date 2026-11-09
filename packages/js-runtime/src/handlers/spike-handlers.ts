import { registerHandler } from '../bridge/nativeHost';

export function registerSpikeHandlers(): void {
  registerHandler('sum', (args) => {
    const { a, b } = args as { a: number; b: number };
    return { result: a + b };
  });

  registerHandler('boom', (args) => {
    const { message } = args as { message: string };
    throw new Error(message);
  });

  // Never settles. Exists so the Kotlin side can be tested for cancellation and pending-map cleanup —
  // a call that is abandoned must not leak its continuation.
  registerHandler('never', () => new Promise<never>(() => {}));

  registerHandler('secureRandom.sample', (args) => {
    const { size } = args as { size: number };
    const backing = new Uint8Array(size + 8);
    const first = new Uint8Array(backing.buffer, 4, size);
    const returned = global.crypto.getRandomValues(first);
    const second = new Uint8Array(size);
    global.crypto.getRandomValues(second);
    let floatError = '';
    let quotaError = '';
    try {
      global.crypto.getRandomValues(new Float32Array(1) as never);
    } catch (error) {
      floatError = error instanceof Error ? error.name : String(error);
    }
    try {
      global.crypto.getRandomValues(new Uint8Array(65_537));
    } catch (error) {
      quotaError = error instanceof Error ? error.name : String(error);
    }
    return {
      size,
      sameObject: returned === first,
      prefixUntouched: backing.slice(0, 4).every((value) => value === 0),
      suffixUntouched: backing.slice(size + 4).every((value) => value === 0),
      different: first.some((value, index) => value !== second[index]),
      floatError,
      quotaError,
    };
  });
}

declare const global: {
  crypto: { getRandomValues<T extends Uint8Array>(array: T): T };
};
