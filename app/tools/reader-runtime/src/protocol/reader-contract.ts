import type { ReaderCommandName, ReaderEventName } from './reader-api.ts';

export const READER_COMMANDS = [
  'document.configure', 'document.patchState', 'chapter.append', 'chapter.remove', 'chapter.reveal', 'chapter.resetTracking',
  'edit.enter', 'edit.exit', 'edit.discard',
  'chapter.setLoadingNext', 'chapter.setNoMore', 'scroll.restoreVertical', 'scroll.byViewport',
  'scroll.seekVertical', 'scroll.autoStart', 'scroll.autoStop', 'scroll.autoStatus', 'layout.configure',
  'layout.move', 'layout.seekUnit', 'layout.seekPercent', 'layout.reflow', 'layout.prepareTurn',
  'layout.finishTurn', 'tts.highlight', 'tts.clearHighlight', 'tts.extractText',
  'tts.firstVisibleParagraph', 'tts.taggedParagraph', 'selection.read', 'selection.readAnchor',
  'selection.clear', 'selection.blur', 'style.apply', 'ui.configure', 'ui.setTtsEnabled',
  'ui.setBionic', 'ui.refresh', 'ui.configureNextChapter', 'scrollTracking.configure', 'anchors.install',
  'summary.focus', 'summary.render', 'summary.remove', 'feedback.show', 'feedback.clear', 'snippet.run',
] as const satisfies readonly ReaderCommandName[];

export const READER_EVENTS = [
  'layout.positionChanged', 'scroll.progressPersist', 'scroll.progressPreview', 'chapter.visible',
  'navigation.loadNext', 'gesture.claim', 'gesture.ready', 'reader.error',
  'reader.saveVideoProgress', 'reader.refetch', 'navigation.next', 'edit.changed', 'edit.save', 'summary.action',
  'scroll.restoreComplete', 'chapter.appended', 'tts.toggle', 'tts.startAtParagraph', 'tts.startAtTaggedParagraph',
  'chapter.markShort', 'chapter.retry', 'feedback.retry', 'clipboard.copy', 'media.playLocalVideo',
  'navigation.openNext', 'navigation.previous', 'tts.start', 'tts.pause', 'tts.resume', 'tts.stop',
  'scroll.seekPercent', 'media.download.ready', 'media.download.progress', 'media.download.activity',
  'media.download.complete', 'media.download.error', 'reader.log',
] as const satisfies readonly ReaderEventName[];

export function isReaderCommandName(value: string): value is ReaderCommandName {
  return (READER_COMMANDS as readonly string[]).includes(value);
}

export function isReaderEventName(value: string): value is ReaderEventName {
  return (READER_EVENTS as readonly string[]).includes(value);
}
