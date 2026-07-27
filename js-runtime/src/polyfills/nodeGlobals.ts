import { Buffer } from 'buffer';

declare const global: {
  Buffer?: typeof Buffer;
  process?: {
    browser?: boolean;
    env?: Record<string, string | undefined>;
    nextTick?: (
      callback: (...args: unknown[]) => void,
      ...args: unknown[]
    ) => void;
    version?: string;
  };
};

global.Buffer ??= Buffer;
global.process ??= {};
global.process.browser ??= true;
global.process.env ??= {};
global.process.version ??= '';
global.process.nextTick ??= (callback, ...args) => {
  setImmediate(() => callback(...args));
};
