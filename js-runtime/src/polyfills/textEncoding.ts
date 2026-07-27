import { Buffer } from 'buffer';

type BufferSource = ArrayBuffer | ArrayBufferView;

class TextDecoderPolyfill {
  private readonly fatal: boolean;
  private readonly ignoreBOM: boolean;

  constructor(
    label = 'utf-8',
    options: { fatal?: boolean; ignoreBOM?: boolean } = {},
  ) {
    if (!/^utf-?8$/i.test(label)) {
      throw new RangeError(`Unsupported encoding: ${label}`);
    }
    this.fatal = options.fatal === true;
    this.ignoreBOM = options.ignoreBOM === true;
  }

  decode(input?: BufferSource): string {
    if (input === undefined) return '';
    const bytes = ArrayBuffer.isView(input)
      ? Buffer.from(
          new Uint8Array(input.buffer, input.byteOffset, input.byteLength),
        )
      : Buffer.from(input);
    const decoded = bytes.toString('utf8');
    if (this.fatal && decoded.includes('\uFFFD')) {
      throw new TypeError('The encoded data was not valid UTF-8');
    }
    return !this.ignoreBOM && decoded.charCodeAt(0) === 0xfeff
      ? decoded.slice(1)
      : decoded;
  }
}

class TextEncoderPolyfill {
  encode(input = ''): Uint8Array {
    return Uint8Array.from(Buffer.from(input, 'utf8'));
  }
}

const encodingGlobal = globalThis as unknown as {
  TextDecoder?: typeof TextDecoderPolyfill;
  TextEncoder?: typeof TextEncoderPolyfill;
};

encodingGlobal.TextDecoder ??= TextDecoderPolyfill;
encodingGlobal.TextEncoder ??= TextEncoderPolyfill;
