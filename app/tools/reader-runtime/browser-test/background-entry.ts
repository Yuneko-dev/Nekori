import { READER_PROTOCOL_VERSION } from '../src/protocol/envelope.ts';
import { createReader } from '../src/runtime/create-reader.ts';
import { createRuntimeHost } from '../src/runtime/host.ts';

void (async () => {
  const config = JSON.parse(document.querySelector('#nekori-reader-config')!.textContent!);
  const messages: unknown[] = [];
  const transport = {
    onmessage: null as ((event: { data: unknown }) => void) | null,
    postMessage(message: string) { messages.push(JSON.parse(message)); },
  };
  const root = document.querySelector<HTMLElement>('#nekori-reader')!;
  const host = createRuntimeHost({ root, config, transport });
  const api = createReader({ root, host, config });
  await api.ready;
  let requestId = 0;
  Object.assign(window, {
    applyReaderStyle(payload: unknown) {
      transport.onmessage?.({ data: JSON.stringify({
        protocolVersion: READER_PROTOCOL_VERSION,
        documentId: config.documentId,
        kind: 'command',
        requestId: String(++requestId),
        method: 'style.apply',
        payload,
      }) });
    },
    readerMessages: messages,
    disposeReader() { api.dispose(); },
  });
})();
