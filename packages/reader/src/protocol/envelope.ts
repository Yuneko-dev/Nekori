export const READER_PROTOCOL_VERSION = 1 as const;

export type ReaderMessageKind = 'boot' | 'ready' | 'command' | 'result' | 'event' | 'cancel';

export interface ReaderErrorPayload {
  code: string;
  message: string;
}

interface BaseEnvelope {
  protocolVersion: typeof READER_PROTOCOL_VERSION;
  documentId: string;
}

export interface ReaderLifecycleEnvelope extends BaseEnvelope {
  kind: 'boot' | 'ready';
  payload?: unknown;
}

export interface ReaderCommandEnvelope extends BaseEnvelope {
  kind: 'command';
  requestId: string;
  method: string;
  payload?: unknown;
}

export interface ReaderResultEnvelope extends BaseEnvelope {
  kind: 'result';
  requestId: string;
  payload?: unknown;
  error?: ReaderErrorPayload;
}

export interface ReaderEventEnvelope extends BaseEnvelope {
  kind: 'event';
  method: string;
  payload?: unknown;
}

export interface ReaderCancelEnvelope extends BaseEnvelope {
  kind: 'cancel';
  requestId: string;
}

export type ReaderEnvelope = ReaderLifecycleEnvelope | ReaderCommandEnvelope | ReaderResultEnvelope |
  ReaderEventEnvelope | ReaderCancelEnvelope;

export class ReaderProtocolError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ReaderProtocolError';
  }
}

export function parseEnvelope(value: unknown, expectedDocumentId: string): ReaderEnvelope {
  const message = typeof value === 'string' ? parseJson(value) : value;
  if (!isRecord(message)) throw new ReaderProtocolError('Envelope must be an object');
  if (message.protocolVersion !== READER_PROTOCOL_VERSION) {
    throw new ReaderProtocolError(`Unsupported protocol version: ${String(message.protocolVersion)}`);
  }
  if (message.documentId !== expectedDocumentId) {
    throw new ReaderProtocolError('Envelope document id does not match the active document');
  }
  const kind = message.kind;
  if (kind === 'boot' || kind === 'ready') return message as unknown as ReaderLifecycleEnvelope;
  if (kind === 'command') {
    requireString(message.requestId, 'requestId');
    requireString(message.method, 'method');
    return message as unknown as ReaderCommandEnvelope;
  }
  if (kind === 'result') {
    requireString(message.requestId, 'requestId');
    if (message.error !== undefined && !isErrorPayload(message.error)) {
      throw new ReaderProtocolError('Result error must include string code and message fields');
    }
    return message as unknown as ReaderResultEnvelope;
  }
  if (kind === 'event') {
    requireString(message.method, 'method');
    return message as unknown as ReaderEventEnvelope;
  }
  if (kind === 'cancel') {
    requireString(message.requestId, 'requestId');
    return message as unknown as ReaderCancelEnvelope;
  }
  throw new ReaderProtocolError(`Unsupported envelope kind: ${String(kind)}`);
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseJson(value: string): unknown {
  try {
    return JSON.parse(value) as unknown;
  } catch {
    throw new ReaderProtocolError('Envelope contains invalid JSON');
  }
}

function requireString(value: unknown, field: string): asserts value is string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new ReaderProtocolError(`Envelope ${field} must be a non-empty string`);
  }
}

function isErrorPayload(value: unknown): value is ReaderErrorPayload {
  return isRecord(value) && typeof value.code === 'string' && typeof value.message === 'string';
}
