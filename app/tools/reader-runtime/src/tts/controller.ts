import van from 'vanjs-core';
import type { ReaderHost } from '../protocol/reader-api.ts';
import type { ReaderConfig } from '../protocol/reader-config.ts';
import { readableBlockAtPoint } from './paragraphs.ts';

const POSITION_KEY = 'nekori.tts.position';
const ICONS = {
  volume: 'M144-385v-192h144l192-192v576L288-385H144Zm408 55v-302q45 20 70.5 61t25.5 90q0 49-25.5 89.5T552-330Zm0 178v-75q86-23 139-93.5T744-480q0-89-53.5-158.5T552-734v-75q116 25 190 117t74 211q0 119-73.5 211.5T552-152Z',
  pause: 'M228-228v-504h168v504H228Zm336 0v-504h168v504H564Z',
  play: 'M240-240v-480l384 240-384 240Z',
} as const;

interface DockPosition { right: boolean; top: number }

export interface TtsController {
  refresh(): void;
  setEnabled(enabled: boolean): void;
  setLabel(label: string): void;
  dispose(): void;
}

export function createTtsController(root: HTMLElement, host: ReaderHost, config: ReaderConfig): TtsController {
  const document = root.ownerDocument;
  const window = document.defaultView;
  if (!window) throw new Error('TTS controller requires an active document window');
  const enabled = van.state(config.ui?.ttsEnabled ?? true);
  const state = van.state(config.runtime.ttsState);
  const dock = readDock(window, window.innerHeight / 2);
  const controller = document.createElement('div');
  controller.id = 'nekori-tts-controller';
  const button = van.tags.button;
  const actionLabel = config.ui?.labels.tts ?? config.strings.tts ?? 'TTS';
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 -960 960 960');
  svg.setAttribute('aria-hidden', 'true');
  const icon = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  svg.append(icon);
  const action = button({ type: 'button', 'aria-label': actionLabel, title: actionLabel }, svg);
  const iconBinding = van.derive(() => {
    icon.setAttribute('d', pathFor(state.val));
    return null;
  });
  van.add(controller, action, iconBinding);
  (document.getElementById('reader-ui') ?? document.body).append(controller);
  let pointer: { id: number; x: number; y: number; moved: boolean } | null = null;
  let hover: HTMLElement | null = null;

  const applyDock = (): void => {
    controller.style.left = dock.right ? 'calc(100% - 20px)' : '20px';
    controller.style.transform = dock.right ? 'translateX(-100%)' : 'translateX(0)';
    const margin = Math.min(120, window.innerHeight / 2);
    controller.style.top = `${Math.max(margin, Math.min(dock.top, window.innerHeight - margin))}px`;
  };
  const clearHover = (): void => {
    hover?.classList.remove('highlight');
    hover = null;
  };
  const updateHover = (x: number, y: number): void => {
    const target = readableBlockAtPoint(root, x, y, config.ttsNormalizationRules);
    if (target === hover) return;
    clearHover();
    if (target instanceof window.HTMLElement) {
      hover = target;
      hover.classList.add('highlight');
    }
  };
  const onPointerDown = (event: PointerEvent): void => {
    if (!enabled.val) return;
    event.preventDefault(); event.stopPropagation();
    pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, moved: false };
    controller.classList.add('active');
    controller.style.transition = '';
    controller.setPointerCapture(event.pointerId);
  };
  const onPointerMove = (event: PointerEvent): void => {
    if (!pointer || pointer.id !== event.pointerId) return;
    event.preventDefault(); event.stopPropagation();
    if (Math.hypot(event.clientX - pointer.x, event.clientY - pointer.y) >= 8) pointer.moved = true;
    if (!pointer.moved) return;
    controller.style.transform = 'translateX(0)';
    controller.style.left = `${event.clientX}px`;
    controller.style.top = `${event.clientY}px`;
    updateHover(event.clientX, event.clientY);
  };
  const onPointerUp = (event: PointerEvent): void => {
    if (!pointer || pointer.id !== event.pointerId) return;
    const moved = pointer.moved;
    pointer = null;
    controller.style.transition = '1s';
    controller.classList.remove('active');
    if (moved) {
      dock.right = event.clientX > window.innerWidth / 2;
      dock.top = event.clientY;
      try { window.localStorage.setItem(POSITION_KEY, JSON.stringify(dock)); } catch { /* Origin storage may be disabled. */ }
      if (hover) {
        hover.setAttribute('data-nekori-tts-target', '1');
        host.emit('tts.startAtTaggedParagraph', {});
      }
    } else host.emit('tts.toggle', {});
    applyDock(); clearHover();
  };
  const onPointerCancel = (event: PointerEvent): void => {
    if (!pointer || pointer.id !== event.pointerId) return;
    pointer = null;
    controller.classList.remove('active');
    applyDock(); clearHover();
  };
  const cancelDrag = (): void => {
    if (!pointer) return;
    const pointerId = pointer.id;
    pointer = null;
    if (controller.hasPointerCapture(pointerId)) controller.releasePointerCapture(pointerId);
    controller.classList.remove('active');
    applyDock(); clearHover();
  };
  const onResize = (): void => applyDock();

  action.addEventListener('pointerdown', onPointerDown);
  controller.addEventListener('pointermove', onPointerMove);
  controller.addEventListener('pointerup', onPointerUp);
  controller.addEventListener('pointercancel', onPointerCancel);
  window.addEventListener('resize', onResize);
  applyDock();
  const setEnabled = (value: boolean): void => {
    enabled.val = value;
    if (!value) cancelDrag();
    controller.classList.toggle('hidden', !value || config.runtime.editing);
  };
  const setLabel = (label: string): void => {
    action.setAttribute('aria-label', label);
    action.title = label;
  };
  const refresh = (): void => {
    state.val = config.runtime.ttsState;
    setEnabled(enabled.val);
  };
  refresh();
  return {
    refresh,
    setEnabled,
    setLabel,
    dispose() {
      action.removeEventListener('pointerdown', onPointerDown);
      cancelDrag();
      controller.removeEventListener('pointermove', onPointerMove);
      controller.removeEventListener('pointerup', onPointerUp);
      controller.removeEventListener('pointercancel', onPointerCancel);
      window.removeEventListener('resize', onResize);
      clearHover(); controller.remove();
    },
  };
}

function readDock(window: Window, fallbackTop: number): DockPosition {
  try {
    const value: unknown = JSON.parse(window.localStorage.getItem(POSITION_KEY) ?? 'null');
    if (isRecord(value) && typeof value.right === 'boolean' && typeof value.top === 'number' && Number.isFinite(value.top)) {
      return { right: value.right, top: value.top };
    }
  } catch { /* Origin storage may be disabled or contain old data. */ }
  return { right: false, top: fallbackTop };
}

function pathFor(state: ReaderConfig['runtime']['ttsState']): string {
  return state === 'playing' ? ICONS.pause : state === 'paused' ? ICONS.play : ICONS.volume;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
