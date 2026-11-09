import { type Options as HtmlParser2Options, parseDocument } from 'htmlparser2';

import {
  ElementNode,
  HtmlNode,
  isElementNode,
  isTextNode,
  TextNode,
} from './nodes';
import { NodeHtmlMarkdownOptions } from './options';

/* ****************************************************************************************************************** */
// region: String Utils
/* ****************************************************************************************************************** */

export const trimNewLines = (s: string) => s.replace(/^\n+|\n+$/g, '');
export const surround = (source: string, surroundStr: string) =>
  `${surroundStr}${source}${surroundStr}`;
export const isWhiteSpaceOnly = (s: string) => !/\S/.test(s);

/**
 * Split string, preserving specific newline used for each line
 */
export function splitSpecial(s: string) {
  const lines: { text: string; newLineChar: '\r' | '\n' | '\r\n' | '' }[] = [];
  const strLen = s.length;

  for (let i = 0, startPos = 0; i < strLen; ++i) {
    const char = s.charAt(i);
    let newLineChar: (typeof lines)[number]['newLineChar'] = '';

    if (char === '\r') newLineChar = s.charAt(i + 1) === '\n' ? '\r\n' : char;
    else if (char === '\n') newLineChar = char;

    const endPos = newLineChar ? i : i === strLen - 1 ? i + 1 : undefined;

    if (endPos === undefined) continue;

    lines.push({
      text: s.slice(startPos, endPos),
      newLineChar,
    });

    startPos = endPos + newLineChar.length;
    if (newLineChar.length > 1) ++i;
  }

  return lines;
}

/**
 * Surround tag content with delimiter (moving any leading/trailing space to outside the tag
 */
export function tagSurround(content: string, surroundStr: string) {
  // If un-escaped surroundStr already occurs, remove all instances
  // See: https://github.com/crosstype/node-html-markdown/issues/18
  const nestedSurroundStrIndex = content.indexOf(surroundStr);
  if (nestedSurroundStrIndex >= 0) {
    content = content.replace(
      new RegExp(`([^\\\\])\\${surroundStr.split('').join('\\')}`, 'gm'),
      '$1',
    );
  }

  const lines = splitSpecial(content);
  let res = '';

  for (const { text, newLineChar } of lines) {
    let i: number = 0;
    let startPos: number | undefined;
    let endPos: number | undefined;

    while (i >= 0 && i < text.length) {
      if (/[\S]/.test(text[i])) {
        if (startPos === undefined) {
          startPos = i;
          i = text.length;
        } else {
          endPos = i;
          i = NaN;
        }
      }

      if (startPos === undefined) ++i;
      else --i;
    }

    // If whole string is non-breaking whitespace, don't surround it
    if (startPos === undefined) {
      res += text + newLineChar;
      continue;
    }

    if (endPos === undefined) endPos = text.length - 1;

    const leadingSpace = startPos > 0 ? text[startPos - 1] : '';
    const trailingSpace = endPos < text.length - 1 ? text[endPos + 1] : '';

    const slicedText = text.slice(startPos, endPos + 1);

    res +=
      leadingSpace +
      surroundStr +
      slicedText +
      surroundStr +
      trailingSpace +
      newLineChar;
  }

  return res;
}

export const getTrailingWhitespaceInfo = (
  s: string,
): { whitespace: number; newLines: number } => {
  const res = { whitespace: 0, newLines: 0 };
  const minI = Math.max(s.length - 10, 0);
  for (let i = s.length - 1; i >= minI; --i) {
    const token = s.slice(i, i + 1);
    if (!/\s/.test(token)) break;
    ++res.whitespace;
    if (['\r', '\n'].includes(token)) ++res.newLines;
  }
  return res;
};

/**
 * If value is truthy, returns `value` (or `v` if no `value` provided), otherwise, returns an empty string
 * @param v - Var to check for truthiness
 * @param value - Value to return if true
 */
export const truthyStr = (v: any, value?: string): string =>
  v ? (value !== undefined ? value : String(v)) : '';

// endregion

/* ****************************************************************************************************************** */
// region: Parser
/* ****************************************************************************************************************** */

/**
 * Note: Do not change - values are tuned for performance
 */
export const htmlParser2Config: HtmlParser2Options = {
  decodeEntities: true,
  lowerCaseAttributeNames: true,
  lowerCaseTags: true,
  recognizeSelfClosing: true,
};

function getNodeTextContent(node: HtmlNode): string {
  if (isTextNode(node)) return node.text;

  return getChildNodes(node)
    .map(child => getNodeTextContent(child as HtmlNode))
    .join('');
}

function adaptHtmlParser2Tree(node: HtmlNode): HtmlNode {
  if (isTextNode(node)) {
    const textNode = node as TextNode & { data?: string };
    const getText = () => textNode.data ?? textNode.nodeValue ?? '';

    if (!('text' in textNode)) {
      Object.defineProperty(textNode, 'text', { get: getText });
    }
    if (!('wholeText' in textNode)) {
      Object.defineProperty(textNode, 'wholeText', { get: getText });
    }
    if (!('trimmedText' in textNode)) {
      Object.defineProperty(textNode, 'trimmedText', {
        get: () => trimNewLines(getText()),
      });
    }
    if (!('isWhitespace' in textNode)) {
      Object.defineProperty(textNode, 'isWhitespace', {
        get: () => !/\S/.test(getText()),
      });
    }
  }

  if (isElementNode(node)) {
    const elementNode = node as ElementNode & {
      attribs?: Record<string, string>;
    };

    if (!('getAttribute' in elementNode)) {
      Object.defineProperty(elementNode, 'getAttribute', {
        value(name: string) {
          return this.attribs?.[name] ?? this.attribs?.[name.toLowerCase()];
        },
      });
    }

    if (!('textContent' in elementNode)) {
      Object.defineProperty(elementNode, 'textContent', {
        get() {
          return getNodeTextContent(this);
        },
      });
    }
  }

  for (const child of getChildNodes(node)) {
    adaptHtmlParser2Tree(child as HtmlNode);
  }

  return node;
}

function tryParseWithNativeDom(html: string): ElementNode | undefined {
  try {
    if (
      !(
        window?.DOMParser &&
        new window.DOMParser().parseFromString('', 'text/html')
      )
    ) {
      return void 0;
    }
  } catch {
    return void 0;
  }

  /* Get a document */
  let doc: Document;
  try {
    doc = document.implementation.createHTMLDocument('').open();
  } catch (e) {
    const { ActiveXObject } = <any>window;
    if (ActiveXObject) {
      const doc = ActiveXObject('htmlfile');
      doc.designMode = 'on'; // disable on-page scripts
      return doc.open();
    }
    throw e;
  }

  // Prepare document, ensuring we have a wrapper node
  doc.write('<node-html-markdown>' + html + '</node-html-markdown>');
  doc.close();

  return doc.documentElement;
}

/**
 * Parse string to an HTML node tree.
 */
export function parseHTML(
  html: string,
  options: NodeHtmlMarkdownOptions,
): HtmlNode {
  /* If specified, try to parse with native engine, fallback to htmlparser2 */
  perfStart('parse');
  let el: ElementNode | undefined;
  if (options.preferNativeParser) {
    try {
      el = tryParseWithNativeDom(html);
    } catch (e) {
      console.warn('Native DOM parser encountered an error during parse', e);
    }
  }

  const parsed =
    el ??
    adaptHtmlParser2Tree(
      parseDocument(html, htmlParser2Config) as unknown as HtmlNode,
    );
  perfStop('parse');

  return parsed;
}

// endregion

/* ****************************************************************************************************************** */
// region: General
/* ****************************************************************************************************************** */

export function getChildNodes<T extends HtmlNode | Node>(node: T): T[];
export function getChildNodes(node: HtmlNode | Node): (Node | HtmlNode)[] {
  const { childNodes } = node as {
    childNodes?: (Node | HtmlNode)[] | NodeListOf<ChildNode>;
  };
  if (!childNodes) return [];
  if (!isNodeList(childNodes)) return childNodes;

  const res: ChildNode[] = [];
  childNodes.forEach(n => res.push(n));

  return res;

  function isNodeList(v: any): v is NodeListOf<ChildNode> {
    return v != null && typeof v[Symbol.iterator] === 'function';
  }
}

export function perfStart(label: string) {
  if (process.env.LOG_PERF) console.time(label);
}

export function perfStop(label: string) {
  if (process.env.LOG_PERF) console.timeEnd(label);
}

// endregion
