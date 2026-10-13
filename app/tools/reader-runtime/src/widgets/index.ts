import { installScopedAnchors } from '../document/anchors.ts';
import { registerEditingCommands } from '../interactions/editing.ts';
import { registerSelectionCommands } from '../interactions/selection.ts';
import { registerVerticalScrollCommands } from '../interactions/vertical-scroll.ts';
import { registerSnippetCommand } from './snippets.ts';
import { registerNextChapterCommand } from './next-chapter.ts';
import { installLocalVideoControl } from './local-video.ts';
import { createBionicReading } from '../tts/bionic.ts';
import { createTtsController } from '../tts/controller.ts';
import { registerTtsDomCommands } from '../tts/highlight.ts';
import { createImageModal } from './image-modal.ts';
import { observeImageLoading } from './image-loading.ts';
import { createInlineFeedback } from './feedback.ts';
import { registerSummaryCommands } from '../summary/index.ts';
import { registerStyleCommand } from '../styles/reader.ts';
import type { ReaderConfig } from '../protocol/reader-config.ts';
import type { ReaderHost, ReaderLayout, Unsubscribe } from '../protocol/reader-api.ts';
export { installGestureOwnership } from '../interactions/gestures.ts';

export interface CreateWidgetsOptions {
  root: HTMLElement;
  host: ReaderHost;
  config: ReaderConfig;
  layout: ReaderLayout | null;
}

export interface WidgetsController {
  refresh(): void;
  dispose(): void;
}

export function createWidgets({ root, host, config, layout }: CreateWidgetsOptions): WidgetsController {
  const closeLabel = config.ui?.labels.imageClose ?? config.strings.imageClose ?? 'Close image';
  const imageModal = createImageModal(root, closeLabel);
  const stopImages = observeImageLoading(root);
  const tts = createTtsController(root, host, config);
  let bionicEnabled = config.ui?.bionicEnabled ?? false;
  const bionic = createBionicReading(root, bionicEnabled, () => config.runtime.editing);
  const feedback = createInlineFeedback(root, host, layout);
  const style = registerStyleCommand(host);
  let removeAnchors: Unsubscribe | null = null;
  const commands: Unsubscribe[] = [
    installLocalVideoControl(host),
    ...registerTtsDomCommands(host, layout),
    ...registerSelectionCommands(host),
    ...registerEditingCommands(host),
    ...registerVerticalScrollCommands(host),
    ...registerSummaryCommands(host, layout),
    registerSnippetCommand(host),
    registerNextChapterCommand(host),
    host.onCommand('ui.configure', (value) => configureUi(value)),
    host.onCommand('ui.setTtsEnabled', (value) => tts.setEnabled(readEnabled(value))),
    host.onCommand('ui.setBionic', (value) => {
      bionicEnabled = readEnabled(value);
      bionic.setEnabled(bionicEnabled);
    }),
    host.onCommand('ui.refresh', () => refresh()),
    host.onCommand('feedback.show', (value) => feedback.show(value)),
    host.onCommand('feedback.clear', () => feedback.clear()),
    host.onCommand('anchors.install', () => {
      removeAnchors ??= installScopedAnchors(root, () => layout);
    }),
  ];

  const configureUi = (value: unknown): void => {
    if (!isRecord(value) || typeof value.bionicEnabled !== 'boolean' || typeof value.ttsEnabled !== 'boolean' ||
        !isRecord(value.labels) || typeof value.labels.tts !== 'string' || typeof value.labels.imageClose !== 'string') {
      throw new TypeError('Invalid reader UI configuration');
    }
    bionicEnabled = value.bionicEnabled;
    tts.setEnabled(value.ttsEnabled);
    tts.setLabel(value.labels.tts);
    imageModal.setCloseLabel(value.labels.imageClose);
    bionic.setEnabled(bionicEnabled);
  };
  const refresh = (): void => {
    tts.refresh();
    bionic.setEnabled(bionicEnabled);
  };
  const unsubscribeState = host.events.subscribe('document.stateChanged', ({ runtime }) => {
    Object.assign(config.runtime, runtime);
    refresh();
  });
  refresh();
  return {
    refresh,
    dispose() {
      unsubscribeState();
      commands.forEach((unsubscribe) => unsubscribe());
      removeAnchors?.();
      removeAnchors = null;
      style.dispose();
      feedback.dispose();
      bionic.dispose();
      tts.dispose();
      imageModal.dispose();
      stopImages();
    },
  };
}

function readEnabled(value: unknown): boolean {
  if (!isRecord(value) || typeof value.enabled !== 'boolean') throw new TypeError('Expected enabled boolean');
  return value.enabled;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
