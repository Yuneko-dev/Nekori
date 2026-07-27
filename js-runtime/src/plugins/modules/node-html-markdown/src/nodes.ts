import type {
  AnyNode as HtmlParser2Node,
  Comment as HtmlParser2Comment,
  Element as HtmlParser2Element,
  Text as HtmlParser2Text,
} from 'domhandler';

/* ****************************************************************************************************************** */
// region: Types
/* ****************************************************************************************************************** */

export const NodeType = {
  ELEMENT_NODE: 1,
  TEXT_NODE: 3,
  COMMENT_NODE: 8,
} as const;

export type CommentNode = (HtmlParser2Comment | Comment) & NodeBase;

/* ***************************************************** *
 * Merged Nodes - Unions of htmlparser2 and common DOM
 * ***************************************************** */

type NodeBase = { preserve?: boolean };

export type HtmlNode = (HtmlParser2Node | Node) & NodeBase;
export type ElementNode = (HtmlParser2Element | HTMLElement) &
  NodeBase & {
    getAttribute(name: string): string | null | undefined;
    textContent: string;
  };
export type TextNode = (HtmlParser2Text | Text) &
  NodeBase & {
    text: string;
    trimmedText: string;
    isWhitespace: boolean;
    wholeText?: string;
  };

// endregion

/* ****************************************************************************************************************** */
// region: TypeGuards
/* ****************************************************************************************************************** */

export const isTextNode = (node: HtmlNode): node is TextNode =>
  node.nodeType === NodeType.TEXT_NODE;
export const isCommentNode = (node: HtmlNode): node is CommentNode =>
  node.nodeType === NodeType.COMMENT_NODE;
export const isElementNode = (node: HtmlNode): node is ElementNode =>
  node.nodeType === NodeType.ELEMENT_NODE &&
  typeof (node as Partial<ElementNode>).tagName === 'string';

// endregion
