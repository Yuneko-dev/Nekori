((key) => {
  const backend = window[Symbol.for('nekori.devtools.backend')];
  let port;
  let closed = false;
  const pending = [];
  const send = wire => {
    if (closed) return;
    if (port) port.postMessage(JSON.stringify({kind: 'cdp', data: wire}));
    else if (pending.length < 1000) pending.push(wire);
  };
  backend.setOnMessage(send);
  const receivePort = event => {
    // Android postWebMessage has a null source. The fresh nonce binds the transferred port.
    if ((event.source !== window && event.source !== null) || event.data !== key ||
        event.ports.length !== 1 || closed || port) return;
    port = event.ports[0];
    window.removeEventListener('message', receivePort);
    port.onmessage = message => {
      if (closed || typeof message.data !== 'string') return;
      const request = JSON.parse(message.data);
      backend.sendRawMessage(message.data);
      if (request.method === 'Page.enable') {
        send(JSON.stringify({method: 'Page.frameNavigated', params: {frame: {
          id: '1', loaderId: key, url: location.href, securityOrigin: location.origin,
          mimeType: 'text/html',
        }}}));
      }
    };
    for (const wire of pending.splice(0)) send(wire);
  };
  const close = () => {
    if (closed) return;
    if (port) port.postMessage(JSON.stringify({kind: 'closed'}));
    closed = true;
    // shortcut: Chobitsu 1.8.6 has no teardown API; its page instrumentation lasts until reload.
    backend.setOnMessage(() => {});
    port?.close();
    pending.length = 0;
    window.removeEventListener('message', receivePort);
    window.removeEventListener('pagehide', close);
    delete window[key];
  };
  window.addEventListener('message', receivePort);
  window.addEventListener('pagehide', close);
  window[key] = {close};
  return location.origin;
})
