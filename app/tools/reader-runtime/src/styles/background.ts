import type { ReaderHost, Unsubscribe } from '../protocol/reader-api.ts';

interface BackgroundConfig {
  image: string;
  opacity?: number;
  blur?: number;
  size?: string;
  position?: string;
  repeat?: boolean;
  errorMessage?: string;
}

export function createReaderBackground(host: ReaderHost): { apply(value: unknown): void; dispose(): void } {
  const document = host.root.ownerDocument;
  const window = document.defaultView;
  if (!window) throw new Error('Reader background requires an active document window');
  let layer = document.getElementById('nekori-reader-background') as HTMLDivElement | null;
  if (!layer) {
    layer = document.createElement('div');
    layer.id = 'nekori-reader-background';
    layer.setAttribute('aria-hidden', 'true');
    document.body.prepend(layer);
  }
  let generation = 0;
  let loadedUrl = '';
  const apply = (value: unknown): void => {
    if (!isConfig(value)) throw new TypeError('Invalid reader background config');
    const token = ++generation;
    layer!.style.backgroundImage = 'none';
    if (!value.image) { loadedUrl = ''; return; }
    const opacity = clamp(value.opacity ?? 100, 0, 100) / 100;
    const blur = clamp(value.blur ?? 0, 0, 20);
    layer!.style.backgroundSize = value.size === 'stretch' ? '100% 100%'
      : ['cover', 'contain', 'auto'].includes(value.size ?? '') ? value.size! : 'cover';
    layer!.style.backgroundPosition = ['center', 'top', 'bottom', 'left', 'right', 'top left'].includes(value.position ?? '')
      ? value.position! : 'center';
    layer!.style.backgroundRepeat = value.repeat ? 'repeat' : 'no-repeat';
    layer!.style.opacity = String(opacity);
    layer!.style.filter = blur ? `blur(${blur}px)` : 'none';
    layer!.style.inset = `${-blur * 3}px`;
    const display = (): void => {
      if (token === generation) layer!.style.backgroundImage = `url(${JSON.stringify(value.image)})`;
    };
    if (loadedUrl === value.image) { display(); return; }
    const image = new window.Image();
    image.onload = () => { if (token === generation) { loadedUrl = value.image; display(); } };
    image.onerror = () => {
      if (token === generation) { loadedUrl = ''; host.emit('reader.error', { message: value.errorMessage ?? '' }); }
    };
    image.src = value.image;
  };
  const config = document.getElementById('nekori-reader-background-config');
  if (config?.textContent) apply(JSON.parse(config.textContent) as unknown);
  return {
    apply,
    dispose() { generation++; layer?.remove(); layer = null; },
  };
}

function isConfig(value: unknown): value is BackgroundConfig {
  return isRecord(value) && typeof value.image === 'string' &&
    (value.opacity === undefined || typeof value.opacity === 'number') &&
    (value.blur === undefined || typeof value.blur === 'number') &&
    (value.size === undefined || typeof value.size === 'string') &&
    (value.position === undefined || typeof value.position === 'string') &&
    (value.repeat === undefined || typeof value.repeat === 'boolean') &&
    (value.errorMessage === undefined || typeof value.errorMessage === 'string');
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(Math.max(value, low), high);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
