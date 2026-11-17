/**
 * Safe WebView-only replacements for the Studio's Vue node views (protocol §5).
 * These views never write document attributes or resolve references. DOM content is built
 * with createElement/textContent only: unknown Markdown is data, never executable HTML.
 */
import type { NodeViewRenderer } from '@tiptap/core'
import type { Node } from '@tiptap/pm/model'

/** A noneditable atom; its original attributes and marks stay in the ProseMirror model. */
export function atomView(label: (node: Node) => string): NodeViewRenderer {
  return ({ node: initial, view }) => {
    const document = view.dom.ownerDocument
    const dom = document.createElement(initial.isInline ? 'span' : 'div')
    dom.contentEditable = 'false'
    dom.setAttribute('data-node-view', initial.type.name)
    dom.textContent = label(initial)
    return {
      dom,
      update(node) {
        if (node.type !== initial.type) return false
        dom.textContent = label(node)
        return true
      },
      // Selection/mutations of the literal placeholder must never become document edits.
      ignoreMutation: () => true,
    }
  }
}

/** Directives are expanded containers. No view-only fold state is written into the room. */
export function containerView(tag: 'div', name: string): NodeViewRenderer {
  return ({ node: initial, view }) => {
    const dom = view.dom.ownerDocument.createElement(tag)
    dom.setAttribute(`data-${name}`, '')
    return {
      dom,
      contentDOM: dom,
      update: (node) => node.type === initial.type,
    }
  }
}

/** Plain editable code, with no highlighting/clipboard plugins or HTML rendering. */
export const codeBlockView: NodeViewRenderer = ({ node: initial, view }) => {
  const document = view.dom.ownerDocument
  const dom = document.createElement('pre')
  const contentDOM = document.createElement('code')
  dom.append(contentDOM)
  return {
    dom,
    contentDOM,
    update: (node) => node.type === initial.type,
  }
}
