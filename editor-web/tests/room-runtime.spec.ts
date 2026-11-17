// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import * as Y from 'yjs'
import { Awareness, applyAwarenessUpdate, encodeAwarenessUpdate } from 'y-protocols/awareness'
import { RoomRuntime, type RoomEvent } from '../src/room-runtime'
import { EditorModel } from '../src/model'
import { decodeBase64, encodeBase64, createSeedOffer } from '../src/bridge'
import { COLLAB_FIELD } from '../src/collab/carried'

const cleanup: Array<() => void> = []
afterEach(() => { cleanup.splice(0).reverse().forEach(f => f()) })
const wait = (ms = 30) => new Promise<void>(resolve => setTimeout(resolve, ms))
const context = { version: 1, documentGeneration: 1, connectionGeneration: 1, sid: 'room-1' }
function fixture() {
  const element = document.body.appendChild(document.createElement('div'))
  const events: RoomEvent[] = []
  const runtime = new RoomRuntime(element, event => events.push(event))
  cleanup.push(() => { runtime.dispose(); element.remove() })
  let id = 0
  const send = (command: string, fields: Record<string, unknown> = {}) => runtime.handle({ ...context, requestId: ++id, command, ...fields })
  const internals = runtime as unknown as { doc: Y.Doc; model: EditorModel; awareness: Awareness }
  async function open(markdown = 'Text.\n', readOnly = false) {
    await send('prepare', { markdown })
    const prepared = events.find(e => e.event === 'prepared')!.payload as { clientId: number; seed: string }
    await send('initialize', { markdown, state: prepared.seed, readOnly })
    return prepared
  }
  return { element, events, runtime, send, internals, open }
}

describe('VS-77 RoomEditorPort local runtime', () => {
  it('reserves an EMPTY actual working clientId; winning seed is separate and open writes nothing', async () => {
    const f = fixture()
    await f.send('prepare', { markdown: 'Text.\n' })
    const p = f.events[0].payload as { clientId: number; seed: string }
    const working = f.internals.doc
    expect(working.clientID).toBe(p.clientId)
    expect(working.store.clients.size).toBe(0)
    expect(working.share.size).toBe(0)
    expect(f.element.childElementCount).toBe(0)
    const seedDoc = new Y.Doc(); cleanup.push(() => seedDoc.destroy())
    Y.applyUpdate(seedDoc, decodeBase64(p.seed))
    expect([...seedDoc.store.clients.keys()]).not.toContain(p.clientId)
    await f.send('initialize', { markdown: 'Text.\n', state: p.seed, readOnly: false })
    expect(f.internals.doc).toBe(working)
    expect(f.internals.doc.clientID).toBe(p.clientId)
    await wait()
    expect(f.events.filter(e => ['localUpdate', 'changed'].includes(e.event))).toEqual([])
    expect(f.events.find(e => e.event === 'ready')?.payload).toEqual({ markdown: 'Text.\n', generation: 0 })
    await f.send('flush'); await f.send('dispose')
    expect(f.events.map(e => e.event)).toEqual(['prepared', 'ready', 'flushed', 'disposed'])
  })

  it('ten clean opens including noncanonical/list/table/empty produce no write callbacks', async () => {
    for (const markdown of ['', '- last\n', '| A |\n| --- |\n| B |\n', 'Text', '# Title\r\n\r\nBody.\r\n', '', 'Text.\n', '- [ ] task\n', '---\nx: 1\n---\n\nbody\n', '![alt](https://never.invalid/a.png)\n']) {
      const f = fixture(); await f.open(markdown); await f.send('flush'); await f.send('dispose')
      expect(f.events.filter(e => ['changed', 'localUpdate', 'blocked'].includes(e.event))).toEqual([])
    }
  })

  it('accepts only winner state, not its own independent offer', async () => {
    const f = fixture()
    await f.send('prepare', { markdown: 'Text.\n' })
    await f.send('initialize', { markdown: 'Text.\n', state: createSeedOffer('Winner.\n'), readOnly: false })
    expect(f.internals.model.snapshot().markdown).toBe('Winner.\n')
    expect(f.events.some(e => e.event === 'localUpdate')).toBe(false)
  })

  it('rejects a prepared doc clientID collision before binding or modifying the reservation', () => {
    const state = decodeBase64(createSeedOffer('Text.\n'))
    const checked = new Y.Doc(); const prepared = new Y.Doc()
    cleanup.push(() => { checked.destroy(); prepared.destroy() })
    Y.applyUpdate(checked, state)
    prepared.clientID = [...checked.store.clients.keys()][0]
    const element = document.createElement('div')
    expect(() => EditorModel.open({ markdown: 'Text.\n', state, element, preparedDocument: prepared })).toThrow(/reserved clientID/)
    expect(prepared.store.clients.size).toBe(0)
    expect(element.childElementCount).toBe(0)
  })

  it('delivers localUpdate BEFORE changed, remote only Applied with snapshot, no echo', async () => {
    const f = fixture(); const p = await f.open()
    f.internals.model.editor.commands.insertContentAt(1, 'Local ')
    expect(f.events.slice(-2).map(e => e.event)).toEqual(['localUpdate', 'changed'])
    const element = document.createElement('div')
    const updates: Uint8Array[] = []
    const remote = EditorModel.open({ element, markdown: 'Text.\n', state: decodeBase64(p.seed), onUpdate: u => updates.push(u) })
    cleanup.push(() => remote.destroy())
    await remote.ready
    remote.editor.commands.insertContentAt(6, ' remote')
    const before = f.events.length
    await f.send('applyUpdate', { update: encodeBase64(updates[0]) })
    expect(f.events.slice(before).map(e => e.event)).toEqual(['applied'])
    expect((f.events.at(-1)!.payload as { markdown: string }).markdown).toContain('remote')
    const generation = f.internals.model.snapshot().generation
    await f.send('applyUpdate', { update: encodeBase64(updates[0]) })
    expect(f.internals.model.snapshot().generation).toBe(generation)
  })

  it('stale contexts, duplicate IDs, new Prepare before Dispose are inert', async () => {
    const f = fixture(); await f.open()
    const before = f.events.length
    await f.runtime.handle({ ...context, sid: 'old', requestId: 999, command: 'dispose' })
    await f.runtime.handle({ ...context, requestId: 1, command: 'dispose' })
    await f.runtime.handle({ ...context, documentGeneration: 2, requestId: 999, command: 'prepare', markdown: 'Other.\n' })
    expect(f.events.length).toBe(before)
    await f.send('flush') // stale high requestId must not consume the current sequence
    expect(f.events.at(-1)?.event).toBe('flushed')
    await f.send('dispose')
    await f.runtime.handle({ ...context, requestId: 20, command: 'prepare', markdown: 'Old.\n' })
    expect(f.events.at(-1)?.event).toBe('disposed')
    await f.runtime.handle({ ...context, documentGeneration: 2, requestId: 21, command: 'prepare', markdown: 'New.\n' })
    expect(f.events.at(-1)?.event).toBe('prepared')
  })

  it('preflights unknown state before binding; retains rejected raw and blocks snapshots', async () => {
    const f = fixture()
    await f.send('prepare', { markdown: 'Text.\n' })
    const bad = new Y.Doc(); cleanup.push(() => bad.destroy())
    bad.getXmlFragment(COLLAB_FIELD).insert(0, [new Y.XmlElement('futureNode')])
    const raw = encodeBase64(Y.encodeStateAsUpdate(bad))
    await f.send('initialize', { markdown: 'Text.\n', state: raw, readOnly: false })
    expect(f.element.childElementCount).toBe(0)
    const blocked = f.events.at(-1)!
    expect(blocked.event).toBe('blocked'); expect(blocked.requestId).toBeNull()
    expect(blocked.payload).toMatchObject({ snapshot: { markdown: 'Text.\n', generation: 0 }, raw: expect.arrayContaining([raw]) })
    await f.send('flush')
    expect(f.events.at(-1)).toBe(blocked)
    expect(f.runtime.format('bold')).toBe(false)
    await f.send('dispose'); expect(f.events.at(-1)?.event).toBe('disposed')
  })

  it('remote unknown schema does not alter live XML; retains valid snapshot plus rejected bytes', async () => {
    const f = fixture(); const p = await f.open()
    const bad = new Y.Doc(); cleanup.push(() => bad.destroy())
    Y.applyUpdate(bad, decodeBase64(p.seed))
    const vector = Y.encodeStateVector(bad)
    bad.getXmlFragment(COLLAB_FIELD).insert(1, [new Y.XmlElement('futureNode')])
    const raw = encodeBase64(Y.encodeStateAsUpdate(bad, vector))
    const valid = f.internals.model.encodeState()
    await f.send('applyUpdate', { update: raw })
    expect(f.events.at(-1)?.event).toBe('blocked')
    expect(f.events.at(-1)?.payload).toMatchObject({ snapshot: { markdown: 'Text.\n', generation: 0 }, raw: expect.arrayContaining([raw]) })
    expect(f.internals.model.encodeState()).toEqual(valid)
    f.internals.model.editor.commands.insertContentAt(1, 'Never')
    expect(f.internals.model.encodeState()).toEqual(valid)
  })

  it('uses genuine y-protocol awareness, no doc changes/remote echo, peerLeft and publish', async () => {
    const f = fixture(); const p = await f.open()
    const peerDoc = new Y.Doc(); const peer = new Awareness(peerDoc)
    cleanup.push(() => { peer.destroy(); peerDoc.destroy() })
    peer.setLocalState({ user: { name: '<img src=x>', color: '#123456' } })
    const count = f.events.length
    await f.send('awareness', { update: encodeBase64(encodeAwarenessUpdate(peer, [peer.clientID])) })
    expect(f.internals.awareness.getStates().has(peer.clientID)).toBe(true)
    expect(f.events.length).toBe(count)
    await f.send('publishAwareness')
    const published = f.events.at(-1)!
    expect(published.event).toBe('awareness'); expect(published.requestId).toBeNull()
    applyAwarenessUpdate(peer, decodeBase64((published.payload as { update: string }).update), 'wire')
    expect(peer.getStates().has(p.clientId)).toBe(true)
    await f.send('peerLeft', { clientId: peer.clientID })
    expect(f.internals.awareness.getStates().has(peer.clientID)).toBe(false)
    expect(f.internals.model.snapshot()).toEqual({ markdown: 'Text.\n', generation: 0 })
    expect(f.events.filter(e => ['localUpdate', 'changed'].includes(e.event))).toEqual([])
  })

  it('flush drains composition callbacks before snapshot, freezes formatting and later input', async () => {
    const f = fixture(); await f.open()
    const view = f.internals.model.editor.view
    let composing = true
    Object.defineProperty(view, 'composing', { configurable: true, get: () => composing })
    const flushed = f.send('flush')
    await wait(5)
    expect(f.runtime.format('bold')).toBe(false)
    f.internals.model.editor.commands.insertContentAt(1, 'IME ')
    composing = false
    await flushed
    expect(f.events.slice(-3).map(e => e.event)).toEqual(['localUpdate', 'changed', 'flushed'])
    expect(f.events.at(-1)?.payload).toEqual({ markdown: 'IME Text.\n', generation: 1 })
    f.internals.model.editor.commands.insertContentAt(1, 'late')
    expect(f.internals.model.snapshot().markdown).toBe('IME Text.\n')
    expect(f.runtime.format('undo')).toBe(false)
    await wait()
    expect(f.events.at(-1)?.event).toBe('flushed')
  })

  it('does not claim flushed if actual IME never completes', async () => {
    const f = fixture(); await f.open()
    Object.defineProperty(f.internals.model.editor.view, 'composing', { configurable: true, value: true })
    await f.send('flush')
    expect(f.events.at(-1)?.event).toBe('blocked')
    expect(f.events.some(e => e.event === 'flushed')).toBe(false)
  })

  it('readOnly forbids local changes but accepts remote state', async () => {
    const f = fixture(); const p = await f.open('Text.\n', true)
    expect(f.internals.model.editor.getText()).toBe('Text.')
    f.internals.model.editor.commands.insertContentAt(1, 'No')
    expect(f.internals.model.snapshot().markdown).toBe('Text.\n')
    expect(f.runtime.format('bold')).toBe(false)
    await f.send('flush')
    expect(f.events.at(-1)?.event).toBe('flushed')
    const remote = new Y.Doc(); cleanup.push(() => remote.destroy())
    Y.applyUpdate(remote, decodeBase64(p.seed))
    const vector = Y.encodeStateVector(remote)
    const paragraph = remote.getXmlFragment(COLLAB_FIELD).get(0) as Y.XmlElement
    ;(paragraph.get(0) as Y.XmlText).insert(0, 'Remote ')
    const before = f.events.length
    await f.send('applyUpdate', { update: encodeBase64(Y.encodeStateAsUpdate(remote, vector)) })
    expect(f.events.slice(before).map(e => e.event)).toEqual(['applied'])
    expect(f.internals.model.editor.getText()).toBe('Remote Text.')
  })

  it('destroys editor binding before document and stops awareness timers', async () => {
    const f = fixture(); await f.open()
    const editor = vi.spyOn(f.internals.model.editor, 'destroy')
    const doc = vi.spyOn(f.internals.doc, 'destroy')
    const awareness = vi.spyOn(f.internals.awareness, 'destroy')
    await f.send('dispose')
    expect(editor).toHaveBeenCalledOnce(); expect(doc).toHaveBeenCalledOnce(); expect(awareness).toHaveBeenCalledOnce()
    expect(editor.mock.invocationCallOrder[0]).toBeLessThan(doc.mock.invocationCallOrder[0])
    expect(f.events.at(-1)).toMatchObject({ event: 'disposed', payload: {} })
  })

  it.each([null, {}, { ...context, command: 'prepare', requestId: NaN }, { ...context, command: 'prepare', requestId: -1 }, { ...context, command: 'prepare', requestId: 1, sid: '' }])('rejects malformed envelope without allocation: %j', async input => {
    const f = fixture(); await expect(f.runtime.handle(input)).rejects.toThrow()
    expect(f.events).toEqual([]); expect(f.internals.doc).toBeUndefined()
  })

  it.each([
    { command: 'initialize', markdown: 'Text.\n', state: '!!!!', readOnly: false },
    { command: 'applyUpdate', update: 'YQ==' },
    { command: 'peerLeft', clientId: -1 },
    { command: 'format', action: 'eval' },
    { command: 'flush', extra: true },
  ])('fails closed for invalid current-context command: %j', async fields => {
    const f = fixture(); await f.open()
    await f.send(fields.command, fields)
    expect(f.events.at(-1)?.event).toBe('blocked')
    expect(f.runtime.format('__proto__')).toBe(false)
  })

  it('bounds source bytes and queued native message memory before binding', async () => {
    const f = fixture()
    await f.send('prepare', { markdown: 'я'.repeat(500_001) })
    expect(f.events.at(-1)?.event).toBe('blocked')
    expect(f.internals.doc).toBeUndefined()
    expect(f.element.childElementCount).toBe(0)
    await expect(f.runtime.handle({ ...context, requestId: 100, command: 'applyUpdate', update: 'A'.repeat(12 * 1024 * 1024) })).rejects.toThrow(/large/)
  })

  it('blocks an oversized merged Markdown BEFORE changing the live binding', async () => {
    const f = fixture(); const p = await f.open()
    const remote = new Y.Doc(); cleanup.push(() => remote.destroy())
    Y.applyUpdate(remote, decodeBase64(p.seed))
    const vector = Y.encodeStateVector(remote)
    const paragraph = remote.getXmlFragment(COLLAB_FIELD).get(0) as Y.XmlElement
    ;(paragraph.get(0) as Y.XmlText).insert(0, 'X'.repeat(1_000_001))
    const before = f.internals.model.encodeState()
    await f.send('applyUpdate', { update: encodeBase64(Y.encodeStateAsUpdate(remote, vector)) })
    expect(f.events.at(-1)?.event).toBe('blocked')
    expect(f.internals.model.encodeState()).toEqual(before)
    expect(f.internals.model.snapshot()).toEqual({ markdown: 'Text.\n', generation: 0 })
  })
})
