/** VS-77: local RoomEditorPort adapter (protocol §§4–9,13). No transport/storage. */
import * as Y from 'yjs'
import { Awareness, applyAwarenessUpdate, encodeAwarenessUpdate, removeAwarenessStates } from 'y-protocols/awareness'
import { EditorModel, type Snapshot } from './model'
import { createSeedOffer, decodeBase64, encodeBase64, type Context } from './bridge'
import { SchemaBlockedError } from './validation'

export type FormatAction = 'bold' | 'italic' | 'strike' | 'code' | 'bulletList' | 'orderedList' | 'taskList' | 'undo' | 'redo'
export interface RoomEvent extends Context {
  version: 1
  requestId: number | null
  event: 'prepared' | 'ready' | 'applied' | 'localUpdate' | 'changed' | 'awareness' | 'flushed' | 'disposed' | 'blocked'
  payload: unknown
}
type Envelope = Context & { version: 1; requestId: number; command: string }
const REMOTE_AWARENESS = Symbol('remote-awareness')
const BASE_KEYS = ['version', 'documentGeneration', 'connectionGeneration', 'sid', 'requestId', 'command']
const FIELDS: Record<string, string[]> = {
  prepare: ['markdown'], initialize: ['markdown', 'state', 'readOnly'], applyUpdate: ['update'],
  awareness: ['update'], peerLeft: ['clientId'], publishAwareness: [], flush: [], dispose: [],
}
const safeInteger = (n: unknown): n is number => Number.isSafeInteger(n) && (n as number) >= 0
const sameContext = (a: Context, b: Context) => a.sid === b.sid && a.documentGeneration === b.documentGeneration && a.connectionGeneration === b.connectionGeneration
function markdown(value: unknown): asserts value is string {
  if (typeof value !== 'string' || value.length > 1_000_000 || new TextEncoder().encode(value).length > 1_000_000 || /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value)) throw new Error('Invalid Markdown')
}

/** Commands/replies are serialized; callbacks always close over the immutable room context. */
export class RoomRuntime {
  private context?: Context
  private previous?: Context
  private lastRequest = -1
  private doc?: Y.Doc
  private awareness?: Awareness
  private model?: EditorModel
  private source?: string
  private stopped = false
  private frozen = false
  private readOnly = true
  private applying = false
  private tail: Promise<void> = Promise.resolve()
  private queued = 0
  private queuedBytes = 0
  private user = { name: '', color: '#6587b8' }

  constructor(private readonly element: HTMLElement, private readonly emit: (event: RoomEvent) => void) {}

  private event(context: Context, requestId: number | null, event: RoomEvent['event'], payload: unknown): void {
    this.emit({ ...context, version: 1, requestId, event, payload })
  }

  /** Returns rejection only for invalid envelopes; stale messages are inert, never block a new room. */
  handle(input: unknown): Promise<void> {
    if (!input || typeof input !== 'object' || Array.isArray(input)) return Promise.reject(new Error('Invalid envelope'))
    const c = input as Envelope & Record<string, unknown>
    if (c.version !== 1 || !safeInteger(c.requestId) || !safeInteger(c.documentGeneration) || !safeInteger(c.connectionGeneration) || typeof c.sid !== 'string' || !c.sid || c.sid.length > 256 || typeof c.command !== 'string') return Promise.reject(new Error('Invalid envelope'))
    if (Object.keys(c).length > 10 || Object.values(c).some(value => !['string', 'number', 'boolean'].includes(typeof value))) return Promise.reject(new Error('Invalid command types'))
    let bytes = 512
    for (const value of Object.values(c)) if (typeof value === 'string') bytes += value.length
    if (bytes > 12 * 1024 * 1024 || this.queuedBytes + bytes > 16 * 1024 * 1024) return Promise.reject(new Error('Command queue too large'))
    // JSON from native is immutable in practice; take our own copy before asynchronous work.
    const copy = { ...c }
    if (++this.queued > 256) { this.queued--; return Promise.reject(new Error('Command queue full')) }
    this.queuedBytes += bytes
    const result = this.tail.then(() => this.dispatch(copy)).finally(() => { this.queued--; this.queuedBytes -= bytes })
    this.tail = result.catch(() => {})
    return result
  }

  private async dispatch(c: Envelope & Record<string, unknown>): Promise<void> {
    if (c.requestId <= this.lastRequest) return
    const context: Context = { documentGeneration: c.documentGeneration, connectionGeneration: c.connectionGeneration, sid: c.sid }
    if (c.command === 'prepare') {
      if (this.context || (this.previous && (context.documentGeneration <= this.previous.documentGeneration || context.connectionGeneration < this.previous.connectionGeneration))) return
    } else if (!this.context || !sameContext(context, this.context)) return
    const fields = Object.hasOwn(FIELDS, c.command) ? FIELDS[c.command] : undefined
    try {
      if (!fields || Object.keys(c).length !== BASE_KEYS.length + fields.length || ![...BASE_KEYS, ...fields].every(key => Object.hasOwn(c, key))) throw new Error('Invalid command fields')
      this.lastRequest = c.requestId
      if (c.command === 'prepare') {
        this.context = context
        this.previous = context
        this.stopped = false
        this.frozen = false
        markdown(c.markdown)
        this.source = c.markdown
        this.doc = new Y.Doc() // stays EMPTY: seed offer uses a different throwaway document
        const seed = createSeedOffer(c.markdown)
        this.event(context, c.requestId, 'prepared', { clientId: this.doc.clientID, seed })
        return
      }
      if (c.command === 'dispose') {
        this.dispose()
        this.event(context, c.requestId, 'disposed', {})
        return
      }
      if (this.stopped) return
      if (c.command === 'initialize') {
        if (this.model || !this.doc) throw new Error('Initialize requires Prepare')
        markdown(c.markdown)
        if (c.markdown !== this.source || typeof c.readOnly !== 'boolean') throw new Error('Initialize source mismatch')
        const state = decodeBase64(c.state as string)
        const awareness = new Awareness(this.doc)
        this.awareness = awareness
        awareness.setLocalStateField('user', this.user)
        this.readOnly = c.readOnly
        const model = EditorModel.open({
          element: this.element, markdown: c.markdown, state, preparedDocument: this.doc,
          awareness, readOnly: c.readOnly,
          onUpdate: update => { if (!this.stopped) this.event(context, null, 'localUpdate', { update: encodeBase64(update) }) },
          onChange: snapshot => { if (!this.stopped && !this.applying) this.event(context, null, 'changed', snapshot) },
          onBlocked: () => this.block(context),
        })
        this.model = model
        // Native buffers remote updates until Ready. This snapshot is the CLEAN baseline.
        await model.ready
        if (!this.stopped) {
          this.event(context, c.requestId, 'ready', model.snapshot())
          awareness.on('update', this.awarenessChanged)
        }
        return
      }
      const model = this.model
      const awareness = this.awareness
      if (!model || !awareness) throw new Error('Editor not initialized')
      switch (c.command) {
        case 'applyUpdate': {
          const update = decodeBase64(c.update as string)
          this.applying = true
          try {
            if (model.applyRemote(update) && !this.stopped) this.event(context, c.requestId, 'applied', model.snapshot())
          } finally { this.applying = false }
          break
        }
        case 'awareness': applyAwarenessUpdate(awareness, decodeBase64(c.update as string), REMOTE_AWARENESS); break
        case 'peerLeft':
          if (!safeInteger(c.clientId) || c.clientId === awareness.clientID) throw new Error('Invalid peer')
          removeAwarenessStates(awareness, [c.clientId], REMOTE_AWARENESS)
          break
        case 'publishAwareness': this.publishAwareness(); break
        case 'flush': {
          this.frozen = true // format barrier starts before waiting for the actual IME
          const snapshot = await model.flush()
          if (!this.stopped) this.event(context, c.requestId, 'flushed', snapshot)
          break
        }
      }
    } catch (error) {
      // Preserve the incumbent and rejected bytes; never replace or repair an incompatible tree.
      this.block(context, error)
    }
  }

  private awarenessChanged = (_change: unknown, origin: unknown): void => {
    if (origin !== REMOTE_AWARENESS) this.publishAwareness()
  }

  private publishAwareness(): void {
    if (!this.context || !this.awareness || this.stopped) return
    this.event(this.context, null, 'awareness', { update: encodeBase64(encodeAwarenessUpdate(this.awareness, [this.awareness.clientID])) })
  }

  configureUser(user: { name: string; color: string }): void {
    if (typeof user.name !== 'string' || user.name.length > 80 || !/^#[a-fA-F0-9]{6}$/.test(user.color)) return
    this.user = { name: user.name, color: user.color }
    this.awareness?.setLocalStateField('user', this.user)
  }

  /** Separate from request IDs. Native must fence the current context before calling. */
  format(action: unknown): boolean {
    const model = this.model
    if (!model || this.stopped || this.frozen || this.readOnly || typeof action !== 'string') return false
    const c = model.editor.commands
    const actions: Record<FormatAction, () => boolean> = {
      bold: () => c.toggleBold(), italic: () => c.toggleItalic(), strike: () => c.toggleStrike(),
      code: () => c.toggleCode(), bulletList: () => c.toggleBulletList(), orderedList: () => c.toggleOrderedList(),
      taskList: () => c.toggleTaskList(), undo: () => c.undo(), redo: () => c.redo(),
    }
    if (!Object.hasOwn(actions, action)) return false
    model.editor.commands.focus()
    return actions[action as FormatAction]()
  }

  private block(context: Context, error?: unknown): void {
    if (this.stopped) return
    this.stopped = true
    this.frozen = true
    this.model?.editor.setEditable(false, false)
    const raw: Uint8Array[] = []
    let snapshot: Snapshot | null = null
    if (this.model) {
      const r = this.model.recovery()
      snapshot = { markdown: r.markdown, generation: r.generation }
      raw.push(r.lastValidState)
      if (r.rejectedState) raw.push(r.rejectedState)
      if (r.rejectedUpdate) raw.push(r.rejectedUpdate)
    } else {
      // VS-82: Prepare may refuse before any seed/binding exists. Preserve the
      // ORIGINAL source as recovery only, never as Ready/Changed/Flushed debt.
      if (this.source !== undefined) snapshot = { markdown: this.source, generation: 0 }
      if (error instanceof SchemaBlockedError) {
        raw.push(error.rawState)
        if (error.inputState) raw.push(error.inputState)
        if (error.inputUpdate) raw.push(error.inputUpdate)
      }
    }
    // A merged diagnostic state can exceed one wire item even when both input pieces
    // fit. Preserve the individually bounded original/last-valid and rejected pieces.
    this.event(context, null, 'blocked', { snapshot, raw: raw.filter(bytes => bytes.byteLength <= 8 * 1024 * 1024).map(encodeBase64) })
  }

  /** No flush/save here. Coordinator owns the Flush → recovery/ACK → Dispose barrier. */
  dispose(): void {
    this.stopped = true
    this.awareness?.off('update', this.awarenessChanged)
    // y-protocols registers its destroy handler on the owning doc. Destroying it explicitly
    // as well would run it twice; doc.destroy below stops its timer/listeners exactly once.
    if (this.model) this.model.destroy() // binding/editor, then working Y.Doc
    else this.doc?.destroy()
    this.awareness = undefined
    this.model = undefined
    this.doc = undefined
    this.source = undefined
    this.context = undefined
    this.element.replaceChildren()
  }
}
