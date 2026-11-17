/** Versioned, transport-free JSON seam for a future origin-checked native host. */
import { EditorModel, type Recovery } from './model'
import { seedUpdate } from './collab/seed'
import { SchemaBlockedError, preflight, validateSourceMarkdown } from './validation'

export const BRIDGE_VERSION = 1 as const
export interface Context {
  documentGeneration: number
  connectionGeneration: number
  sid: string
}
export type Command = Context & { version: 1; requestId: number } & (
  | { command: 'init'; markdown: string; state: string }
  | { command: 'applyUpdate'; update: string }
  | { command: 'serialize' }
  | { command: 'format'; action: 'bold' | 'italic' | 'strike' | 'code' | 'bulletList' | 'orderedList' | 'taskList' | 'undo' | 'redo' }
  | { command: 'dispose' }
)
export interface BridgeEvent extends Context {
  version: 1
  requestId: number | null
  event: 'ready' | 'localUpdate' | 'changed' | 'snapshot' | 'applied' | 'formatted' | 'disposed' | 'error' | 'blocked'
  payload: unknown
}

export function encodeBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192))
  return btoa(binary)
}
export function decodeBase64(text: string): Uint8Array {
  const maxBytes = 8 * 1024 * 1024
  if (typeof text !== 'string' || text.length > 4 * Math.ceil(maxBytes / 3) || text.length % 4) {
    throw new Error('Invalid or oversized base64')
  }
  const padding = text.endsWith('==') ? 2 : text.endsWith('=') ? 1 : 0
  const dataLength = text.length - padding
  if (text.length / 4 * 3 - padding > maxBytes) throw new Error('Invalid or oversized base64')
  // Bounded linear scan: repeated regex groups can exhaust V8's stack on valid updates.
  // Only the final one or two characters may be padding; even whitespace is rejected.
  for (let i = 0; i < dataLength; i++) {
    const c = text.charCodeAt(i)
    if (!((c >= 65 && c <= 90) || (c >= 97 && c <= 122) || (c >= 48 && c <= 57) || c === 43 || c === 47)) {
      throw new Error('Invalid or oversized base64')
    }
  }
  // Canonical encodings have zero unused bits in the final sextet (RFC 4648).
  if (padding) {
    const last = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'.indexOf(text[dataLength - 1])
    if (last < 0 || (last & (padding === 2 ? 15 : 3)) !== 0) throw new Error('Invalid or oversized base64')
  }
  const binary = atob(text)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

/** A seed OFFER only. Native chooses the CAS winner; never apply both candidates. */
export function createSeedOffer(markdown: string): string {
  validateSourceMarkdown(markdown)
  const update = seedUpdate(markdown)
  const checked = preflight(update)
  checked.destroy()
  return encodeBase64(update)
}

function recoveryJSON(r: Recovery): unknown {
  return { ...r, lastValidState: encodeBase64(r.lastValidState), rejectedState: r.rejectedState && encodeBase64(r.rejectedState), rejectedUpdate: r.rejectedUpdate && encodeBase64(r.rejectedUpdate) }
}

/** Does NOT attach window globals, message listeners, Java interfaces or network handlers. */
export class NativeBridge {
  private model?: EditorModel
  private context?: Context
  private lastRequestId = -1
  constructor(private readonly element: HTMLElement, private readonly emit: (event: BridgeEvent) => void) {}

  private event(context: Context, requestId: number | null, event: BridgeEvent['event'], payload: unknown): void {
    this.emit({ ...context, version: BRIDGE_VERSION, requestId, event, payload })
  }

  /** Runtime validation is mandatory: TypeScript types do not validate native messages. */
  handle(input: unknown): void {
    if (!input || typeof input !== 'object') throw new Error('Invalid bridge command')
    const c = input as Command
    if (c.version !== BRIDGE_VERSION || !Number.isSafeInteger(c.requestId) || c.requestId < 0 ||
      !Number.isSafeInteger(c.documentGeneration) || c.documentGeneration < 0 ||
      !Number.isSafeInteger(c.connectionGeneration) || c.connectionGeneration < 0 ||
      typeof c.sid !== 'string' || !c.sid || c.sid.length > 256) throw new Error('Invalid bridge envelope')
    const context: Context = { documentGeneration: c.documentGeneration, connectionGeneration: c.connectionGeneration, sid: c.sid }
    try {
      if (c.requestId <= this.lastRequestId) throw new Error('Stale or duplicate requestId')
      const current = this.context
      if (c.command === 'init') {
        if (current && (c.documentGeneration <= current.documentGeneration || c.connectionGeneration < current.connectionGeneration)) throw new Error('Stale init generation')
        if (typeof c.markdown !== 'string' || new TextEncoder().encode(c.markdown).length > 1_000_000) throw new Error('Invalid Markdown')
        const state = decodeBase64(c.state)
        // Validate before retiring the incumbent, so bad init cannot discard recovery.
        const next = EditorModel.open({
          markdown: c.markdown, state, element: document.createElement('div'),
          onUpdate: update => this.event(context, null, 'localUpdate', { update: encodeBase64(update) }),
          onChange: snapshot => this.event(context, null, 'changed', snapshot),
          onBlocked: reason => this.event(context, null, 'blocked', { reason, recovery: this.model && recoveryJSON(this.model.recovery()) }),
        })
        this.model?.destroy()
        this.model = next
        this.context = context
        this.lastRequestId = c.requestId
        this.element.replaceChildren(next.editor.options.element as HTMLElement)
        void next.ready.then(() => {
          if (this.model === next && !next.blocked) this.event(context, c.requestId, 'ready', { generation: next.snapshot().generation })
        })
        return
      }
      if (!current || Object.keys(context).some(key => context[key as keyof Context] !== current[key as keyof Context])) throw new Error('Stale bridge context')
      this.lastRequestId = c.requestId
      const model = this.model
      if (!model) throw new Error('No editor')
      if (c.command === 'dispose') {
        model.destroy(); this.model = undefined; this.element.replaceChildren()
        this.event(context, c.requestId, 'disposed', null); return
      }
      if (model.blocked) throw new Error('Schema blocked; recover/dispose and rejoin, do not save')
      if (c.command === 'applyUpdate') {
        this.event(context, c.requestId, 'applied', { accepted: model.applyRemote(decodeBase64(c.update)) })
      } else if (c.command === 'serialize') {
        this.event(context, c.requestId, 'snapshot', model.snapshot())
      } else if (c.command === 'format') {
        const commands = model.editor.commands
        const actions = {
          bold: () => commands.toggleBold(), italic: () => commands.toggleItalic(),
          strike: () => commands.toggleStrike(), code: () => commands.toggleCode(),
          bulletList: () => commands.toggleBulletList(), orderedList: () => commands.toggleOrderedList(),
          taskList: () => commands.toggleTaskList(), undo: () => commands.undo(), redo: () => commands.redo(),
        }
        if (!Object.hasOwn(actions, c.action)) throw new Error('Unknown format action')
        this.event(context, c.requestId, 'formatted', { applied: actions[c.action]() })
      } else throw new Error('Unknown bridge command')
    } catch (error) {
      this.event(context, c.requestId, 'error', {
        message: error instanceof Error ? error.message : String(error),
        ...(error instanceof SchemaBlockedError && {
          blocked: true, rawState: encodeBase64(error.rawState),
          inputState: error.inputState && encodeBase64(error.inputState),
          inputUpdate: error.inputUpdate && encodeBase64(error.inputUpdate),
        }),
      })
    }
  }

  destroy(): void { this.model?.destroy(); this.model = undefined; this.element.replaceChildren() }
}
