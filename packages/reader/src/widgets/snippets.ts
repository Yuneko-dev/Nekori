import type { ReaderHost, Unsubscribe } from '../protocol/reader-api.ts';

interface Snippet { name?: string; code: string }

/** Execute source-owned customJS bytes as a document script, separate from native commands. */
export function executeSourceScript(code: string, document: Document): void {
  if (code.length === 0) return;
  const script = document.createElement('script');
  script.textContent = code;
  (document.head ?? document.documentElement).append(script);
}

export function registerSnippetCommand(host: ReaderHost): Unsubscribe {
  return host.onCommand('snippet.run', (value) => {
    for (const snippet of readSnippets(value)) executeSnippet(host.root.ownerDocument, snippet);
  });
}

function readSnippets(value: unknown): Snippet[] {
  if (isRecord(value) && typeof value.code === 'string') {
    return [{ code: value.code, ...(typeof value.name === 'string' ? { name: value.name } : {}) }];
  }
  if (isRecord(value) && Array.isArray(value.snippets)) {
    return value.snippets.map((snippet) => {
      if (!isRecord(snippet) || typeof snippet.code !== 'string' ||
          (snippet.name !== undefined && typeof snippet.name !== 'string')) {
        throw new TypeError('Invalid user snippet');
      }
      return { code: snippet.code, ...(typeof snippet.name === 'string' ? { name: snippet.name } : {}) };
    });
  }
  throw new TypeError('Expected snippet code or snippet list');
}

function executeSnippet(document: Document, snippet: Snippet): void {
  const name = safeSourceName(snippet.name ?? 'snippet');
  const script = document.createElement('script');
  script.textContent = `${snippet.code}\n//# sourceURL=nekori-snippet-${name}.js`;
  const parent = document.head ?? document.documentElement;
  parent.append(script);
  parent.removeChild(script);
}

function safeSourceName(value: string): string {
  return value.replace(/[\r\n]/g, '').replace(/[^\w.-]/g, '-').slice(0, 80) || 'snippet';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
