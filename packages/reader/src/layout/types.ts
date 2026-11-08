import type { Direction, Spread } from './math.ts';

export interface LayoutConfig {
  readonly enabled: boolean;
  readonly spread: Spread;
  readonly direction: Direction;
  readonly vertical: boolean;
  readonly infinite: boolean;
  readonly threshold: number;
  readonly chapterId: string;
}

export interface LayoutPosition {
  readonly chapterId: string;
  readonly unitIndex: number;
  readonly unitCount: number;
  readonly firstPage: number;
  readonly lastPage: number;
  readonly totalPages: number;
  readonly progress: number;
}

export interface LayoutHost {
  emit(name: string, payload: unknown): void;
}

export interface LayoutController {
  readonly enabled: boolean;
  readonly position: LayoutPosition | null;
  configure(config: LayoutConfig): void;
  moveBy(delta: number, behavior?: ScrollBehavior): boolean;
  seekUnit(unitIndex: number, behavior?: ScrollBehavior): boolean;
  seekPercent(percent: number): boolean;
  revealElement(element: Element): boolean;
  reflow(preserveViewport?: boolean): boolean;
  prepareSilentTurn(preserveViewport?: boolean): boolean;
  finishSilentTurn(reflow?: boolean, commit?: boolean): boolean;
  dispose(): void;
}

export interface ScrollTrackingConfig {
  readonly infiniteScrollEnabled: boolean;
  readonly loadThreshold: number;
  readonly doneThreshold: number;
}
