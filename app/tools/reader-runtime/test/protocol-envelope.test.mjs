import assert from 'node:assert/strict';
import test from 'node:test';
import { parseEnvelope } from '../src/protocol/envelope.ts';

const documentId = 'document-1';

test('accepts a versioned event for the active document', () => {
  const envelope = parseEnvelope({
    protocolVersion: 1,
    documentId,
    kind: 'event',
    method: 'selection.changed',
    payload: { name: 'selection.change' },
  }, documentId);

  assert.equal(envelope.kind, 'event');
  assert.equal(envelope.documentId, documentId);
});

test('rejects messages from a stale document', () => {
  assert.throws(
    () => parseEnvelope({
      protocolVersion: 1,
      documentId: 'old-document',
      kind: 'event',
    }, documentId),
    /document/i,
  );
});

test('rejects unsupported protocol versions and malformed envelopes', () => {
  assert.throws(
    () => parseEnvelope({ protocolVersion: 2, documentId, kind: 'ready' }, documentId),
    /version/i,
  );
  assert.throws(() => parseEnvelope('{broken', documentId), /JSON/i);
  assert.throws(() => parseEnvelope({ protocolVersion: 1, documentId, kind: 'eval' }, documentId));
});
