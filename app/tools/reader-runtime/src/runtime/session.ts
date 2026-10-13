import { isRecord, parseEnvelope, ReaderProtocolError, READER_PROTOCOL_VERSION } from '../protocol/envelope.ts';
import type {
  ReaderCancelEnvelope,
  ReaderCommandEnvelope,
  ReaderEnvelope,
  ReaderEventEnvelope,
  ReaderResultEnvelope,
} from '../protocol/envelope.ts';
import type { ReaderConfig } from '../protocol/reader-config.ts';
import type { ReaderCommandHandler } from '../protocol/reader-api.ts';
import { isReaderCommandName, isReaderEventName } from '../protocol/reader-contract.ts';

export interface NativeReaderTransport {
  postMessage(message: string): void;
  onmessage: ((event: { data: unknown }) => void) | null;
}

export interface RuntimeSession {
  readonly pendingRequestCount: number;
  readonly lastProtocolError: Error | null;
  boot(): void;
  start(initializers?: Array<() => void | Promise<void>>): Promise<void>;
  emit(name: string, payload?: unknown): void;
  request(name: string, payload?: unknown, signal?: AbortSignal): Promise<unknown>;
  onEvent(name: string, listener: (payload: unknown) => void): () => void;
  registerCommand(name: string, handler: ReaderCommandHandler): () => void;
  dispose(): void;
}

interface PendingRequest {
  resolve(value: unknown): void;
  reject(error: Error): void;
  signal?: AbortSignal;
  abortListener?: () => void;
}

export function createRuntimeSession(
  config: Pick<ReaderConfig, 'documentId'>,
  transport: NativeReaderTransport,
): RuntimeSession {
  let requestCounter = 0;
  let disposed = false;
  let started = false;
  let bootSent = false;
  let readySent = false;
  let lastError: Error | null = null;
  const queuedEvents: string[] = [];
  const pending = new Map<string, PendingRequest>();
  const activeCommands = new Map<string, AbortController>();
  const commands = new Map<string, ReaderCommandHandler>();
  const listeners = new Map<string, Set<(payload: unknown) => void>>();

  const send = (envelope: ReaderEnvelope): void => {
    if (disposed) throw new Error('Reader document has been disposed');
    transport.postMessage(JSON.stringify(envelope));
  };

  const flushQueuedEvents = (): void => {
    while (!disposed && queuedEvents.length > 0) {
      const event = queuedEvents.shift();
      if (event !== undefined) transport.postMessage(event);
    }
    if (disposed) queuedEvents.length = 0;
  };

  const rejectPending = (id: string, error: Error): void => {
    const item = pending.get(id);
    if (!item) return;
    pending.delete(id);
    if (item.signal && item.abortListener) item.signal.removeEventListener('abort', item.abortListener);
    item.reject(error);
  };

  const sendResult = (id: string, value: unknown, error?: { code: string; message: string }): void => {
    const result: ReaderResultEnvelope = error
      ? { protocolVersion: READER_PROTOCOL_VERSION, documentId: config.documentId, kind: 'result', requestId: id, error }
      : { protocolVersion: READER_PROTOCOL_VERSION, documentId: config.documentId, kind: 'result', requestId: id, payload: value };
    send(result);
  };

  const dispatchCommand = async (envelope: ReaderCommandEnvelope): Promise<void> => {
    const handler = commands.get(envelope.method);
    if (!handler) {
      sendResult(envelope.requestId, undefined, { code: 'unsupported_command', message: `Unsupported command: ${envelope.method}` });
      return;
    }
    const controller = new AbortController();
    activeCommands.set(envelope.requestId, controller);
    try {
      const result = await handler(envelope.payload, controller.signal);
      if (!controller.signal.aborted) sendResult(envelope.requestId, result);
    } catch (error) {
      if (!controller.signal.aborted) sendResult(envelope.requestId, undefined, toErrorPayload(error));
    } finally {
      activeCommands.delete(envelope.requestId);
    }
  };

  const receive = (data: unknown): void => {
    let envelope: ReaderEnvelope;
    try {
      envelope = parseEnvelope(transportData(data), config.documentId);
    } catch (error) {
      lastError = error instanceof Error ? error : new ReaderProtocolError('Invalid native message');
      return;
    }
    if (envelope.kind === 'boot') return;
    if (envelope.kind === 'result') {
      resolveResult(envelope);
    } else if (envelope.kind === 'event') {
      dispatchEvent(envelope);
    } else if (envelope.kind === 'command') {
      void dispatchCommand(envelope);
    } else if (envelope.kind === 'cancel') {
      cancelCommand(envelope);
    }
  };

  const resolveResult = (envelope: ReaderResultEnvelope): void => {
    const item = pending.get(envelope.requestId);
    if (!item) return;
    pending.delete(envelope.requestId);
    if (item.signal && item.abortListener) item.signal.removeEventListener('abort', item.abortListener);
    if (envelope.error) {
      const error = new Error(envelope.error.message);
      error.name = envelope.error.code;
      item.reject(error);
    } else {
      item.resolve(envelope.payload);
    }
  };

  const dispatchEvent = (envelope: ReaderEventEnvelope): void => {
    for (const listener of listeners.get(envelope.method) ?? []) listener(envelope.payload);
  };

  const cancelCommand = (envelope: ReaderCancelEnvelope): void => {
    activeCommands.get(envelope.requestId)?.abort();
  };

  transport.onmessage = (event) => receive(event.data);

  return {
    get pendingRequestCount() { return pending.size; },
    get lastProtocolError() { return lastError; },
    boot() {
      if (disposed) throw new Error('Reader document has been disposed');
      if (bootSent) return;
      bootSent = true;
      send({ protocolVersion: READER_PROTOCOL_VERSION, documentId: config.documentId, kind: 'boot' });
    },
    async start(initializers = []) {
      if (disposed) throw new Error('Reader document has been disposed');
      if (started) return;
      started = true;
      this.boot();
      await Promise.all(initializers.map((initialize) => initialize()));
      if (disposed) throw abortError();
      if (!disposed) {
        send({ protocolVersion: READER_PROTOCOL_VERSION, documentId: config.documentId, kind: 'ready' });
        readySent = true;
        flushQueuedEvents();
      }
    },
    emit(name, payload) {
      if (disposed) throw new Error('Reader document has been disposed');
      if (!isReaderEventName(name)) throw new TypeError(`Unsupported reader event: ${name}`);
      const envelope: ReaderEventEnvelope = {
        protocolVersion: READER_PROTOCOL_VERSION,
        documentId: config.documentId,
        kind: 'event',
        method: name,
        payload,
      };
      if (!readySent && !(bootSent && name === 'reader.error')) queuedEvents.push(JSON.stringify(envelope));
      else send(envelope);
    },
    request(name, payload, signal) {
      if (disposed) return Promise.reject(new Error('Reader document has been disposed'));
      if (signal?.aborted) return Promise.reject(abortError());
      const requestId = `${config.documentId}:${++requestCounter}`;
      return new Promise((resolve, reject) => {
        const item: PendingRequest = { resolve, reject };
        if (signal) {
          item.signal = signal;
          item.abortListener = () => {
            if (!pending.has(requestId)) return;
            rejectPending(requestId, abortError());
            send({ protocolVersion: READER_PROTOCOL_VERSION, documentId: config.documentId, kind: 'cancel', requestId });
          };
          signal.addEventListener('abort', item.abortListener, { once: true });
        }
        pending.set(requestId, item);
        send({ protocolVersion: READER_PROTOCOL_VERSION, documentId: config.documentId, kind: 'command', requestId, method: name, payload });
      });
    },
    onEvent(name, listener) {
      const group = listeners.get(name) ?? new Set();
      group.add(listener);
      listeners.set(name, group);
      return () => {
        group.delete(listener);
        if (group.size === 0) listeners.delete(name);
      };
    },
    registerCommand(name, handler) {
      if (!isReaderCommandName(name)) throw new TypeError(`Unsupported reader command: ${name}`);
      if (commands.has(name)) throw new Error(`Reader command already registered: ${name}`);
      commands.set(name, handler);
      return () => { if (commands.get(name) === handler) commands.delete(name); };
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      transport.onmessage = null;
      for (const id of pending.keys()) rejectPending(id, new Error('Reader document has been disposed'));
      for (const controller of activeCommands.values()) controller.abort();
      activeCommands.clear();
      listeners.clear();
      commands.clear();
      queuedEvents.length = 0;
    },
  };
}

function transportData(data: unknown): unknown {
  if (isRecord(data) && 'data' in data) return data.data;
  return data;
}

function toErrorPayload(error: unknown): { code: string; message: string } {
  if (error instanceof Error) return { code: error.name || 'command_failed', message: error.message };
  return { code: 'command_failed', message: String(error) };
}

function abortError(): Error {
  const error = new Error('The operation was aborted');
  error.name = 'AbortError';
  return error;
}
