// Persisted WebView data key: keep stable so existing fractional resume positions survive upgrades.
export const VIDEO_FRACTION_KEY = "__tsundoku_video_fraction";

export type FractionMap = Record<string, number>;
export interface StringStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export function readFractions(storage: StringStorage): FractionMap {
  try {
    const parsed: unknown = JSON.parse(storage.getItem(VIDEO_FRACTION_KEY) || "{}");
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? parsed as FractionMap
      : {};
  } catch {
    return {};
  }
}

export function writeFraction(storage: StringStorage, path: string, fraction: number): void {
  if (!path) return;
  try {
    const fractions = readFractions(storage);
    if (fraction > 0) fractions[path] = Math.round(fraction * 1e4) / 1e4;
    else delete fractions[path];
    storage.setItem(VIDEO_FRACTION_KEY, JSON.stringify(fractions));
  } catch {
    // Native whole-percent progress remains usable if web storage is unavailable.
  }
}

export function combineResumeProgress(progress: number, path: string, fractions: FractionMap): number {
  if (!(progress > 0 && progress < 100)) return progress;
  const fraction = fractions[path];
  return progress + (fraction !== undefined && fraction > 0 && fraction < 1 ? fraction : 0);
}

export function formatClock(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  return [Math.floor(total / 3600), Math.floor(total / 60) % 60, total % 60]
    .map((part) => String(part).padStart(2, "0"))
    .join(":");
}
