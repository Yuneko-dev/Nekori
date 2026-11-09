import type { ChapterContent } from '../types';

/**
 * Pass Plugin chapter results through. Legacy strings stay raw so the deep
 * Kotlin ChapterContent module owns marker parsing in one place.
 */
export function normalizeChapterContent(
  value: unknown,
  isNekoriPlugin?: boolean,
): ChapterContent | string {
  if (typeof value === 'string') {
    if (isNekoriPlugin === true) throw new Error('Invalid chapter response');
    return value;
  }
  if (isNekoriPlugin === false || !value || typeof value !== 'object')
    throw new Error('Invalid chapter response');
  const record = value as Record<string, unknown>;
  if (
    (record.state !== 'ready' && record.state !== 'checkpoint') ||
    typeof record.type !== 'string' ||
    !['novel', 'mixed', 'image', 'video'].includes(record.type) ||
    typeof record.html !== 'string' ||
    (record.noCache !== undefined && typeof record.noCache !== 'boolean') ||
    (record.noPrefetch !== undefined && typeof record.noPrefetch !== 'boolean') ||
    (record.checkpointMessage !== undefined && typeof record.checkpointMessage !== 'string')
  ) {
    throw new Error('Invalid chapter response');
  }
  const state = record.state as 'ready' | 'checkpoint';
  const type = record.type as ChapterContent['type'];
  const content: ChapterContent = { state, type, html: record.html };
  if (typeof record.noCache === 'boolean') content.noCache = record.noCache;
  if (typeof record.noPrefetch === 'boolean') content.noPrefetch = record.noPrefetch;
  if (typeof record.checkpointMessage === 'string') {
    (content as { checkpointMessage?: string }).checkpointMessage = record.checkpointMessage;
  }
  return content;
}
