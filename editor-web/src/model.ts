import { Editor, Extension } from '@tiptap/core'
import { Plugin } from '@tiptap/pm/state'
import { Collaboration } from '@tiptap/extension-collaboration'
import { yCursorPlugin } from '@tiptap/y-tiptap'
import type { Awareness } from 'y-protocols/awareness'
import * as Y from 'yjs'
import { createExtensions } from './editor/extensions'
import { COLLAB_FIELD } from './collab/carried'
import { parseDocument, serializeDocument, type DocumentEnvelope } from './md/document'
import { proseMirrorToMdast } from './md/prosemirror'
import { preflight, SchemaBlockedError, validateDocument, validateSourceMarkdown } from './validation'

const REMOTE = Symbol('native-remote')
export interface Snapshot { markdown: string; generation: number }
export interface OpenOptions {
  markdown: string
  /** The winning seed/full state, never a merge of seed candidates. */
  state: Uint8Array
  element: HTMLElement
  /** Ownership transfers on success only. Must be EMPTY; preserves Prepare's clientID. */
  preparedDocument?: Y.Doc
  awareness?: Awareness
  readOnly?: boolean
  onUpdate?: (update: Uint8Array) => void
  onChange?: (snapshot: Snapshot) => void
  onBlocked?: (reason: string) => void
}
export interface Recovery {
  markdown: string
  generation: number
  lastValidState: Uint8Array
  rejectedState?: Uint8Array
  rejectedUpdate?: Uint8Array
  reason?: string
}

/** Local editor model only: no transport, persistence, credentials or native globals. */
export class EditorModel {
  readonly editor: Editor
  readonly ready: Promise<void>
  private readonly doc: Y.Doc
  private readonly envelope: DocumentEnvelope
  private current: Snapshot
  private lastValidState: Uint8Array
  private active = false
  private disposed = false
  private frozen = false
  private flushing = false
  private applyingRemote = false
  private failure?: Recovery
  private readonly options: OpenOptions

  static open(options: OpenOptions): EditorModel { return new EditorModel(options) }

  private constructor(options: OpenOptions) {
    this.options = options
    if (options.awareness && options.awareness.doc !== options.preparedDocument) throw new Error('Awareness must own the prepared document')
    validateSourceMarkdown(options.markdown)
    this.envelope = parseDocument(options.markdown).envelope
    // No binding exists until the complete incoming tree has passed preflight.
    const checked = preflight(options.state)
    let baseline: string
    try { baseline = this.materializeDocument(checked) }
    catch (error) {
      const raw = Y.encodeStateAsUpdate(checked)
      checked.destroy()
      throw new SchemaBlockedError(error instanceof Error ? error.message : String(error), raw, options.state.slice())
    }
    if (options.preparedDocument) {
      const prepared = options.preparedDocument
      if (prepared.isDestroyed || prepared.store.clients.size || prepared.share.size || checked.store.clients.has(prepared.clientID)) {
        checked.destroy()
        throw new Error('Prepared document must be empty with a distinct reserved clientID')
      }
      checked.destroy()
      Y.applyUpdate(prepared, options.state, REMOTE)
      this.doc = prepared
    } else this.doc = checked
    this.current = { markdown: baseline, generation: 0 }
    this.lastValidState = Y.encodeStateAsUpdate(this.doc)
    let rendered!: () => void
    this.ready = new Promise<void>(resolve => { rendered = resolve })
    this.editor = new Editor({
      element: options.element,
      editable: !options.readOnly,
      extensions: [
        ...createExtensions(),
        Extension.create({
          name: 'compatibilityWriteGate',
          addProseMirrorPlugins: () => [new Plugin({ filterTransaction: tr =>
            !this.failure && !this.disposed && (!this.active || !tr.docChanged || this.applyingRemote || (!this.frozen && !options.readOnly)) })],
        }),
        ...(options.awareness ? [Extension.create({
          name: 'roomAwareness',
          addProseMirrorPlugins: () => [yCursorPlugin(options.awareness!, {
            selectionBuilder: () => ({ class: 'room-selection' }),
            cursorBuilder: user => {
              const cursor = document.createElement('span')
              cursor.className = 'room-caret'
              const label = document.createElement('span')
              label.textContent = typeof user?.name === 'string' ? user.name.slice(0, 80) : ''
              cursor.append(label)
              return cursor
            },
          })],
        })] : []),
        Collaboration.configure({ document: this.doc, field: COLLAB_FIELD, onFirstRender: () => rendered() }),
      ],
      // content deliberately absent: the room, not initial Markdown, owns the editor.
      editorProps: {
        handleDOMEvents: {
          // Even harmless links must not navigate a future privileged WebView.
          click: (_view, event) => {
            if ((event.target as Element | null)?.closest?.('a')) { event.preventDefault(); return true }
            return false
          },
        },
      },
    })
    this.doc.on('update', this.changed)
    this.active = true
  }

  get blocked(): boolean { return Boolean(this.failure) }

  private materialize(): string {
    return this.materializeDocument(this.doc)
  }

  private materializeDocument(doc: Y.Doc): string {
    const markdown = serializeDocument(proseMirrorToMdast(validateDocument(doc)), this.envelope)
    if (markdown.length > 1_000_000 || new TextEncoder().encode(markdown).length > 1_000_000) throw new Error('Markdown exceeds 1 MB')
    return markdown
  }

  private changed = (update: Uint8Array, origin: unknown): void => {
    if (!this.active || this.blocked || this.disposed) return
    let markdown: string
    try { markdown = this.materialize() }
    catch (error) { this.freeze(error, update); return }
    const state = Y.encodeStateAsUpdate(this.doc)
    if (state.byteLength > 8 * 1024 * 1024 || update.byteLength > 8 * 1024 * 1024) {
      this.freeze(new Error('Room state exceeds 8 MiB'), update)
      return
    }
    this.lastValidState = state
    if (markdown !== this.current.markdown) {
      this.current = { markdown, generation: this.current.generation + 1 }
      // Commit locally first: a synchronous host/relay callback can reenter with a
      // newer remote transaction. Never overwrite that generation on our return.
      if (origin !== REMOTE) this.options.onUpdate?.(update.slice())
      if (!this.blocked && !this.disposed) this.options.onChange?.(this.snapshot())
    } else if (origin !== REMOTE) {
      this.options.onUpdate?.(update.slice())
    }
  }

  /** Preflight the whole resulting state on a detached document, never repair live XML. */
  applyRemote(update: Uint8Array): boolean {
    if (this.disposed || this.blocked) return false
    try {
      const candidate = preflight(Y.encodeStateAsUpdate(this.doc), update)
      try { this.materializeDocument(candidate) }
      finally { candidate.destroy() }
      this.applyingRemote = true
      try { Y.applyUpdate(this.doc, update, REMOTE) }
      finally { this.applyingRemote = false }
      return !this.blocked
    } catch (error) { this.freeze(error, update); return false }
  }

  private freeze(error: unknown, update: Uint8Array): void {
    if (this.failure) return
    const reason = error instanceof Error ? error.message : String(error)
    this.failure = {
      ...this.current,
      lastValidState: this.lastValidState.slice(),
      rejectedState: error instanceof SchemaBlockedError ? error.rawState.slice() : undefined,
      rejectedUpdate: update.slice(), reason,
    }
    this.editor.setEditable(false, false)
    // Block programmatic transactions too; setEditable alone only disables DOM input.
    this.options.onBlocked?.(reason)
  }

  /** Pure snapshot: never a put/save trigger, including for noncanonical source Markdown. */
  snapshot(): Snapshot { return { ...this.current } }
  /** Flush is an input barrier, not persistence. Never claim success with a live IME. */
  async flush(): Promise<Snapshot> {
    if (this.disposed || this.blocked) throw new Error('Editor unavailable')
    const view = this.editor.view
    if (!this.flushing) {
      this.flushing = true
      const inputBarrier = (event: Event) => {
        // Accept only the real IME's final composition mutation while waiting; all new
        // typing, paste, drop and checkbox/pointer actions are frozen immediately.
        if (event.type === 'beforeinput' && view.composing && /composition/i.test((event as InputEvent).inputType)) return
        event.preventDefault()
        event.stopImmediatePropagation()
      }
      for (const type of ['beforeinput', 'paste', 'drop', 'keydown', 'pointerdown', 'mousedown', 'click']) {
        view.dom.addEventListener(type, inputBarrier, true)
      }
    }
    // Blur asks the browser/IME to commit. Compositionend and its final DOM mutation can
    // arrive asynchronously; a synthetic compositionend would lose actual IME text.
    ;(view.dom as HTMLElement).blur()
    const deadline = Date.now() + 1500
    while (view.composing) {
      if (Date.now() >= deadline || this.disposed || this.blocked) throw new Error('IME did not finish')
      await new Promise<void>(resolve => setTimeout(resolve, 20))
    }
    // PM's observer is synchronous here; consume mutations before closing the write gate.
    ;(view as typeof view & { domObserver: { flush(): void } }).domObserver.flush()
    this.frozen = true
    this.editor.setEditable(false, false)
    await Promise.resolve()
    if (this.disposed || this.blocked) throw new Error('Editor unavailable')
    return this.snapshot()
  }
  encodeState(): Uint8Array { return Y.encodeStateAsUpdate(this.doc) }
  recovery(): Recovery {
    const r = this.failure ?? { ...this.current, lastValidState: this.encodeState() }
    return { ...r, lastValidState: r.lastValidState.slice(), rejectedState: r.rejectedState?.slice(), rejectedUpdate: r.rejectedUpdate?.slice() }
  }
  destroy(): void {
    if (this.disposed) return
    this.active = false
    this.disposed = true
    this.doc.off('update', this.changed)
    this.editor.destroy() // binding goes away before its Y.Doc
    this.doc.destroy()
  }
}
