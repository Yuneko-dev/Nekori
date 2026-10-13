import { createRuntimeHost } from '../runtime/host.ts';
import { createReader } from '../runtime/create-reader.ts';
import { isRecord, READER_PROTOCOL_VERSION } from '../protocol/envelope.ts';
import { readReaderConfig } from '../protocol/reader-config.ts';
import type { ReaderConfig } from '../protocol/reader-config.ts';
import type { NativeReaderTransport } from '../runtime/session.ts';
import type { ReaderHost, Unsubscribe } from '../protocol/reader-api.ts';

export function startReaderDocument(
  documentRoot: Document,
  installGestures?: (host: ReaderHost) => Unsubscribe,
): ReturnType<typeof createReader> {
  const view = documentRoot.defaultView;
  if (!view) throw new Error('Reader document has no window');
  const transport = (view as ReaderWindow).__nekoriNativeTransport;
  let config: ReaderConfig;
  try {
    config = readReaderConfig(documentRoot);
  } catch (error) {
    const documentId = recoverDocumentId(documentRoot);
    if (documentId && transport) reportStartupFailure(transport, documentId);
    throw error;
  }
  const root = documentRoot.querySelector<HTMLElement>('[data-nekori-reader-root], #nekori-reader') ??
    documentRoot.body ?? documentRoot.documentElement;
  if (!transport) throw new Error('Native reader transport is unavailable');
  const host = createRuntimeHost({ root, config, transport });
  if (config.mode !== 'download' && installGestures) host.onDispose(installGestures(host));
  host.boot();
  return createReader({ root, host, config });
}

interface ReaderWindow extends Window {
  __nekoriNativeTransport?: NativeReaderTransport;
}

function recoverDocumentId(documentRoot: Document): string | null {
  const element = documentRoot.querySelector('#nekori-reader-config');
  if (!element || element.tagName !== 'SCRIPT' || element.getAttribute('type') !== 'application/json' || !element.textContent) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(element.textContent);
    return isRecord(value) && typeof value.documentId === 'string' && value.documentId.length > 0
      ? value.documentId
      : null;
  } catch {
    return null;
  }
}

function reportStartupFailure(transport: NativeReaderTransport, documentId: string): void {
  const base = { protocolVersion: READER_PROTOCOL_VERSION, documentId };
  transport.postMessage(JSON.stringify({ ...base, kind: 'boot' }));
  transport.postMessage(JSON.stringify({
    ...base,
    kind: 'event',
    method: 'reader.error',
    payload: { message: 'The reader could not start because its configuration is invalid. Close and reopen the reader.' },
  }));
}
