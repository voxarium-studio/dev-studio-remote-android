/**
 * The editor's schema, assembled (block-editor spec §3).
 *
 * Tiptap ships most of §3.1 already; what is configured here is where its defaults and
 * Markdown disagree. Each override below is one of those disagreements, and removing one
 * changes what lands on disk — this is a schema, not a preferences file.
 */

import type { Attributes, Mark } from '@tiptap/core'
import CodeBlock from '@tiptap/extension-code-block'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { Image } from '@tiptap/extension-image'
import { Table, TableCell, TableHeader, TableRow } from '@tiptap/extension-table'
import StarterKit from '@tiptap/starter-kit'

import { CarriedState } from '../collab/carried'
import { isImageWidth } from '../md/image-width'
import {
  Callout,
  DirectiveTitle,
  Toggle,
  UnsupportedBlock,
  UnsupportedInline,
  WikiLink,
} from './nodes'
import { TextColor } from './text-color'
import { atomView, codeBlockView } from './views'

/**
 * The WebView has no navigation surface. Replace only Link's DOM renderer, in its existing
 * StarterKit slot (same priority/rank/attributes); links remain ordinary schema marks and
 * the codec still writes their original href. Do not move Link to the end of the list.
 */
const SafeStarterKit = StarterKit.extend({
  addExtensions() {
    return (this.parent?.() ?? []).map((extension) =>
      extension.name === 'link'
        ? (extension as Mark).extend({
            parseHTML() {
              return [...(this.parent?.() ?? []), { tag: 'span[data-editor-link]' }]
            },
            renderHTML({ HTMLAttributes }: { HTMLAttributes: Record<string, unknown> }) {
              // href/target/rel on a span are inert; keep them for internal HTML clipboard
              // round-trips without creating an anchor or any navigation handler.
              return ['span', { ...HTMLAttributes, 'data-editor-link': '' }, 0]
            },
          })
        : extension,
    )
  },
})

/**
 * GFM aligns a *column*, not a cell, so the alignment lives on the table and there is one
 * entry per column. §4.5 edits it; the schema only has to hold it.
 */
const MarkdownTable = Table.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      align: { default: [] as (string | null)[], rendered: false },
    }
  },
})

/**
 * A picture, drawn as a literal non-loading placeholder (protocol §5).
 *
 * Inline, because an image sits inside a paragraph in Markdown, and an atom — the reference
 * belongs to the file and is edited by editing the file, not by putting a caret in it. The
 * node view is what makes it *appear*: `src` is relative to the document's folder, which the
 * browser would resolve against the SPA's own URL and not find.
 *
 * **The reference is rendered as `data-src`, never as `src`**, and that is not cosmetic.
 * ProseMirror builds the node's `toDOM` spec beside the node view, so an `<img src>` here is
 * created and fetched even though nothing ever shows it — measured in the browser, one request
 * per picture: the relative ones landed on the SPA's catch-all and came back as `index.html`,
 * and a `![](https://…)` reference reached the *host*, which is the one thing §3.4 forbids.
 * An `<img>` with no `src` loads nothing, and `parseHTML` reads the attribute back from either
 * spelling, so an image survives being copied and pasted inside the editor.
 *
 * What the file holds is untouched by any of this: the serializer reads `attrs.src`, so a
 * resolved `/api/…` URL can never reach the Markdown.
 */
const DocImage = Image.extend({
  addAttributes() {
    const parent: Attributes = this.parent?.() ?? {}
    return {
      ...parent,
      src: {
        ...(parent.src ?? {}),
        parseHTML: (element: HTMLElement) =>
          element.getAttribute('data-src') ?? element.getAttribute('src'),
        renderHTML: (attributes: Record<string, unknown>) =>
          attributes.src ? { 'data-src': attributes.src } : {},
      },
      // Whole pixels or nothing, whatever the element said: the slider does arithmetic on it
      // and the file holds only the widths `image-width.ts` reads back (§3.4).
      width: {
        ...(parent.width ?? {}),
        default: null,
        parseHTML: (element: HTMLElement) => {
          const value = Number(element.getAttribute('data-width') ?? element.getAttribute('width'))
          return isImageWidth(value) ? value : null
        },
        renderHTML: (attributes: Record<string, unknown>) =>
          isImageWidth(attributes.width) ? { 'data-width': String(attributes.width) } : {},
      },
    }
  },

  parseHTML() {
    // The parent's `img[src]` still matches a picture pasted in from a browser.
    return [...(this.parent?.() ?? []), { tag: 'img[data-src]' }]
  },

  addNodeView() {
    return atomView((node) => `![${node.attrs.alt ?? ''}](${node.attrs.src ?? ''})`)
  },
})

/**
 * `checked` gains a third value: `null`, for a plain item inside a task list.
 *
 * GFM allows the mix, and forcing such an item to `false` would put an empty checkbox in
 * the file next to a line that never had one. With no `data-checked` attribute the CSS
 * hides the checkbox, so it reads as the plain item it is.
 */
const MarkdownTaskItem = TaskItem.extend({
  addAttributes() {
    return {
      checked: {
        default: false,
        keepOnSplit: false,
        parseHTML: (element) => {
          const value = element.getAttribute('data-checked')
          // `'null'` is what Tiptap's own node view writes for a null attribute — it
          // stringifies rather than omitting — so pasted content has to read it back.
          if (value === null || value === 'null') return null
          return value === '' || value === 'true'
        },
        renderHTML: (attributes) =>
          attributes.checked === null ? {} : { 'data-checked': String(attributes.checked) },
      },
    }
  },
})

/** The document's code stays a normal Tiptap code block with a plain DOM content view. */
const PlainCodeBlock = CodeBlock.extend({
  addNodeView() {
    return codeBlockView
  },
})

/**
 * Room-only extensions (remote-android protocol §5 / dev spec §16.4).
 *
 * CarriedState is always present; history belongs to Collaboration and no trailing node is
 * inserted on open. The owner adds Collaboration configured with field COLLAB_FIELD after
 * validating the complete incoming Y.Doc. No provider, transport or room state is created
 * here. Desktop menus, stores, caret/presentation plugins and image loading are omitted.
 */
export function createExtensions() {
  return [
    CarriedState,
    SafeStarterKit.configure({
      underline: false,
      heading: { levels: [1, 2, 3, 4, 5, 6] },
      link: {
        openOnClick: false,
        autolink: false,
        linkOnPaste: true,
      },
      undoRedo: false,
      trailingNode: false,
      codeBlock: false,
    }),
    PlainCodeBlock.configure({ enableTabIndentation: true, tabSize: 2 }),
    TextColor,
    TaskList,
    MarkdownTaskItem.configure({ nested: true }),
    MarkdownTable.configure({ resizable: false }),
    TableRow,
    TableHeader,
    TableCell,
    DocImage.configure({ inline: true, allowBase64: true }),
    WikiLink,
    DirectiveTitle,
    Callout,
    Toggle,
    UnsupportedBlock,
    UnsupportedInline,
  ]
}
