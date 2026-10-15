import type { ReaderHost } from '../protocol/reader-api.ts';
import { createReaderBackground } from './background.ts';

export function registerStyleCommand(host: ReaderHost): { dispose(): void } {
  const document = host.root.ownerDocument;
  const background = createReaderBackground(host);
  const initialClasses = host.config.style?.bodyClasses;
  const appliedClasses = new Set<string>(typeof initialClasses === 'string' ? initialClasses.split(/\s+/).filter(Boolean) : []);
  const remove = host.onCommand('style.apply', (value) => {
    if (!isRecord(value) || typeof value.css !== 'string' || typeof value.bodyClasses !== 'string') {
      throw new TypeError('Invalid reader style payload');
    }
    const styleId = 'nekori-custom-style';
    let style = document.getElementById(styleId) as HTMLStyleElement | null;
    if (!style) {
      style = document.createElement('style');
      style.id = styleId;
      document.head.append(style);
    }
    style.textContent = value.css;
    for (const className of appliedClasses) document.body.classList.remove(className);
    appliedClasses.clear();
    for (const className of value.bodyClasses.split(/\s+/).filter(Boolean)) {
      document.body.classList.add(className);
      appliedClasses.add(className);
    }
    if (typeof value.backgroundColor === 'number' && Number.isFinite(value.backgroundColor)) {
      document.body.style.backgroundColor = androidColor(value.backgroundColor);
    }
    if (value.background !== undefined) background.apply(value.background);
  });
  return { dispose() { remove(); background.dispose(); document.getElementById('nekori-custom-style')?.remove(); } };
}

function androidColor(color: number): string {
  const value = color >>> 0;
  const alpha = (value >>> 24) & 0xff;
  const red = (value >>> 16) & 0xff;
  const green = (value >>> 8) & 0xff;
  const blue = value & 0xff;
  return alpha === 255 ? `rgb(${red} ${green} ${blue})` : `rgb(${red} ${green} ${blue} / ${alpha / 255})`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
