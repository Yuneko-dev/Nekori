import type { ReaderHost, Unsubscribe } from '../protocol/reader-api.ts';

export function registerVerticalScrollCommands(host: ReaderHost): Unsubscribe[] {
  const document = host.root.ownerDocument;
  const window = document.defaultView;
  if (!window) throw new Error('Vertical scrolling requires an active document window');
  let autoFrame = 0;
  let lastFrame = 0;
  let pixelsPerSecond = 0;

  const maxScroll = (): number => Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
  const stopAutoScroll = (): void => {
    if (autoFrame) window.cancelAnimationFrame(autoFrame);
    autoFrame = 0;
    lastFrame = 0;
  };
  const tick = (time: number): void => {
    if (lastFrame > 0) window.scrollBy({ top: pixelsPerSecond * (time - lastFrame) / 1000, behavior: 'auto' });
    lastFrame = time;
    if (autoFrame) autoFrame = window.requestAnimationFrame(tick);
  };
  const startAutoScroll = (payload: unknown): void => {
    if (!isRecord(payload) || typeof payload.pxPerSecond !== 'number' || !Number.isFinite(payload.pxPerSecond)) {
      throw new TypeError('Invalid automatic-scroll speed');
    }
    stopAutoScroll();
    pixelsPerSecond = Math.max(0, payload.pxPerSecond);
    lastFrame = 0;
    autoFrame = window.requestAnimationFrame(tick);
  };

  const commands: Unsubscribe[] = [
    host.onCommand('scroll.restoreVertical', (payload) => {
      if (!isRecord(payload) || typeof payload.progress !== 'number' || typeof payload.token !== 'number') {
        throw new TypeError('Invalid vertical scroll restore payload');
      }
      stopAutoScroll();
      window.scrollTo({ top: maxScroll() * clamp01(payload.progress), behavior: 'instant' });
      window.requestAnimationFrame(() => host.emit('scroll.restoreComplete', { token: payload.token as number }));
    }),
    host.onCommand('scroll.byViewport', (payload) => {
      if (!isRecord(payload) || typeof payload.direction !== 'number' || typeof payload.fraction !== 'number') {
        throw new TypeError('Invalid viewport scroll payload');
      }
      stopAutoScroll();
      const behavior = payload.behavior === 'smooth' ? 'smooth' : 'auto';
      window.scrollBy({ top: Math.sign(payload.direction) * window.innerHeight * Math.max(0, payload.fraction), behavior });
    }),
    host.onCommand('scroll.seekVertical', (payload) => {
      if (!isRecord(payload) || typeof payload.percent !== 'number' || !Number.isFinite(payload.percent)) {
        throw new TypeError('Invalid vertical seek payload');
      }
      stopAutoScroll();
      window.scrollTo({ top: maxScroll() * Math.max(0, Math.min(100, payload.percent)) / 100, behavior: 'instant' });
    }),
    host.onCommand('scroll.autoStart', startAutoScroll),
    host.onCommand('scroll.autoStop', stopAutoScroll),
    host.onCommand('scroll.autoStatus', () => autoFrame !== 0),
  ];
  return [...commands, stopAutoScroll];
}

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
