import { createLayout, createScrollTracking } from '../../src/layout/index.ts';
import { installScopedAnchors } from '../../src/document/anchors.ts';
import { createRuntimeHost } from '../../src/runtime/host.ts';
import { createWidgets } from '../../src/widgets/index.ts';
import { installGestureOwnership } from '../../src/interactions/gestures.ts';
import type { ReaderConfig } from '../../src/protocol/reader-config.ts';
import type { ReaderHost, ReaderLayout } from '../../src/protocol/reader-api.ts';
import type { NativeReaderTransport } from '../../src/runtime/session.ts';

interface AcceptanceHarness {
  readonly host: ReaderHost;
  readonly layout: ReaderLayout | null;
  readonly tracking: ReturnType<typeof createScrollTracking> | null;
  readonly messages: unknown[];
  readonly ready: Promise<void>;
  deliver(message: unknown): void;
  installAnchors(): () => void;
  dispose(): void;
}

declare global {
  interface Window {
    makeReaderAcceptanceHarness(root: HTMLElement, config: ReaderConfig): AcceptanceHarness;
  }
}

window.makeReaderAcceptanceHarness = (root, config) => {
  const messages: unknown[] = [];
  const transport: NativeReaderTransport = {
    onmessage: null,
    postMessage(message) { messages.push(JSON.parse(message) as unknown); },
  };
  const host = createRuntimeHost({ root, config, transport });
  const ready = host.start();
  const stopGestures = installGestureOwnership(host);
  const layout = config.layout ? createLayout({ root, host }) : null;
  if (layout && config.layout) layout.configure(config.layout);
  const tracking = config.scrollTracking ? createScrollTracking({ root, host }) : null;
  if (tracking && config.scrollTracking) tracking.configure(config.scrollTracking);
  const widgets = createWidgets({ root, host, config, layout });
  return {
    host,
    layout,
    tracking,
    messages,
    ready,
    deliver(message) { transport.onmessage?.({ data: message }); },
    installAnchors() { return installScopedAnchors(root, () => layout); },
    dispose() { stopGestures(); widgets.dispose(); tracking?.dispose(); layout?.dispose(); host.dispose(); },
  };
};

export type { AcceptanceHarness };
