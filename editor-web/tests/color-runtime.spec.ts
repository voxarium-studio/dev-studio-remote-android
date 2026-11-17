// @vitest-environment jsdom
/** VS-82 / INTEROP-01: Android-only fail-closed gates over real RoomRuntime/Tiptap/Yjs.
 * No Remote or native host: ACK below is only a local persistence/reopen simulation.
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import * as Y from 'yjs'
import { RoomRuntime, type RoomEvent } from '../src/room-runtime'
import { type EditorModel, type Snapshot } from '../src/model'
import { createSeedOffer, decodeBase64, encodeBase64 } from '../src/bridge'
import { seedUpdate } from '../src/collab/seed'
import { COLLAB_FIELD } from '../src/collab/carried'

const cleanup: Array<() => void> = []
afterEach(() => { cleanup.splice(0).reverse().forEach(f => f()) })
const nested = 'A [nested [emphasis]{.blue} span]{.gray}.\n'

function fixture() {
  const element = document.body.appendChild(document.createElement('div'))
  const events: RoomEvent[] = []
  const runtime = new RoomRuntime(element, event => events.push(event))
  cleanup.push(() => { runtime.dispose(); element.remove() })
  let requestId = 0
  let documentGeneration = 1
  const send = (command: string, fields: Record<string, unknown> = {}) => runtime.handle({
    version: 1, documentGeneration, connectionGeneration: 1, sid: 'interop-01',
    requestId: ++requestId, command, ...fields,
  })
  const internals = runtime as unknown as { doc: Y.Doc; model: EditorModel }
  async function open(markdown: string) {
    await send('prepare', { markdown })
    expect(events.at(-1)?.event).toBe('prepared')
    const prepared = events.at(-1)!.payload as { clientId: number; seed: string }
    await send('initialize', { markdown, state: prepared.seed, readOnly: false })
    expect(events.at(-1)?.event).toBe('ready')
    return prepared
  }
  return { element, events, runtime, send, internals, open, nextGeneration: () => { documentGeneration++ } }
}

function clone(state: Uint8Array): Y.Doc {
  const doc = new Y.Doc()
  cleanup.push(() => doc.destroy())
  Y.applyUpdate(doc, state)
  return doc
}

function firstText(doc: Y.Doc): Y.XmlText {
  const paragraph = doc.getXmlFragment(COLLAB_FIELD).get(0) as Y.XmlElement
  const text = paragraph.get(0)
  expect(text).toBeInstanceOf(Y.XmlText)
  return text as Y.XmlText
}

function blockedPayload(event: RoomEvent) {
  expect(event.event).toBe('blocked')
  expect(event.requestId).toBeNull()
  return event.payload as { snapshot: Snapshot | null; raw: string[] }
}

async function expectStopped(f: ReturnType<typeof fixture>) {
  const count = f.events.length
  await f.send('flush')
  expect(f.runtime.format('bold')).toBe(false)
  await Promise.resolve()
  expect(f.events).toHaveLength(count)
  expect(f.events.some(e => e.event === 'flushed')).toBe(false)
}

describe('INTEROP-01 RoomRuntime publication and recovery barriers', () => {
  it.each([
    nested,
    '[a ]{.red}[b]{.blue} tail\n',
    'head [ a]{.red}\n',
    '[a\t]{.red} next\n',
  ])('rejects unsafe source at guarded seed and Prepare, before Ready: %s', async markdown => {
    expect(() => createSeedOffer(markdown)).toThrow(/INTEROP-01/)
    const f = fixture()
    await f.send('prepare', { markdown })
    expect(f.events.map(e => e.event)).toEqual(['blocked'])
    expect(blockedPayload(f.events[0]).snapshot).toEqual({ markdown, generation: 0 })
    expect(f.element.childElementCount).toBe(0)
    await expectStopped(f)
  })

  it.each(['nested', 'leading space', 'trailing space'])('preflights unsafe winning foreign full state (%s), without binding or mutating reservation', async kind => {
    const f = fixture()
    await f.send('prepare', { markdown: 'a b.\n' })
    expect(f.events.at(-1)?.event).toBe('prepared')
    const working = f.internals.doc
    // Deliberately unguarded source, as if another Studio won the room seed CAS.
    const foreign = clone(seedUpdate(kind === 'nested' ? nested : 'a b.\n'))
    if (kind !== 'nested') firstText(foreign).format(kind === 'leading space' ? 1 : 0, 2, { textColor: { color: 'red' } })
    const raw = encodeBase64(Y.encodeStateAsUpdate(foreign))
    const before = f.events.length
    await f.send('initialize', { markdown: 'a b.\n', state: raw, readOnly: false })
    expect(f.events.slice(before).map(e => e.event)).toEqual(['blocked'])
    expect(blockedPayload(f.events.at(-1)!)).toMatchObject({ snapshot: { markdown: 'a b.\n', generation: 0 }, raw: expect.arrayContaining([raw]) })
    expect(f.element.childElementCount).toBe(0)
    expect(f.internals.doc).toBe(working)
    expect(working.store.clients.size).toBe(0)
    expect(working.share.size).toBe(0)
    await expectStopped(f)
  })

  it.each(['leading space', 'trailing space'])('rejects foreign color update (%s) before Applied/Changed, retaining raw and last-valid state', async kind => {
    const f = fixture()
    const prepared = await f.open('a b.\n')
    const foreign = clone(decodeBase64(prepared.seed))
    const vector = Y.encodeStateVector(foreign)
    firstText(foreign).format(kind === 'leading space' ? 1 : 0, 2, { textColor: { color: 'red' } })
    const update = Y.encodeStateAsUpdate(foreign, vector)
    const raw = encodeBase64(update)
    const valid = f.internals.model.encodeState()
    const snapshot = f.internals.model.snapshot()
    const before = f.events.length
    await f.send('applyUpdate', { update: raw })
    expect(f.events.slice(before).map(e => e.event)).toEqual(['blocked'])
    const recovery = f.internals.model.recovery()
    expect(blockedPayload(f.events.at(-1)!)).toMatchObject({ snapshot, raw: expect.arrayContaining([encodeBase64(valid), raw]) })
    expect(recovery.lastValidState).toEqual(valid)
    expect(recovery.rejectedUpdate).toEqual(update)
    expect(recovery.rejectedState).toBeDefined()
    expect(firstText(clone(recovery.rejectedState!)).toDelta()).toEqual(firstText(foreign).toDelta())
    expect(f.internals.model.encodeState()).toEqual(valid)
    expect(f.internals.model.snapshot()).toEqual(snapshot)
    f.internals.model.editor.commands.insertContentAt(1, 'Never')
    expect(f.internals.model.encodeState()).toEqual(valid)
    await expectStopped(f)
  })

  it.each(['typing', 'color selection'])('blocks dangerous local %s before LocalUpdate/Changed, retaining rejected raw instead of flushing', async action => {
    const f = fixture()
    await f.open(action === 'typing' ? '[word]{.red} tail\n' : 'a b.\n')
    const model = f.internals.model
    const valid = model.encodeState()
    const snapshot = model.snapshot()
    const updates: Uint8Array[] = []
    f.internals.doc.on('update', (update: Uint8Array) => updates.push(update.slice()))
    const before = f.events.length
    if (action === 'typing') {
      // A real Tiptap insertion inherits the active red mark at the run boundary.
      model.editor.chain().setTextSelection(5).setTextColor('red').insertContent(' ').run()
    } else {
      model.editor.chain().setTextSelection({ from: 1, to: 3 }).setTextColor('red').run()
    }
    expect(f.events.slice(before).map(e => e.event)).toEqual(['blocked'])
    expect(model.blocked).toBe(true)
    expect(model.editor.isEditable).toBe(false)
    expect(updates).toHaveLength(1)
    const recovery = model.recovery()
    expect(recovery.lastValidState).toEqual(valid)
    expect(recovery.rejectedUpdate).toEqual(updates[0])
    expect(blockedPayload(f.events.at(-1)!)).toMatchObject({ snapshot, raw: expect.arrayContaining([encodeBase64(valid), encodeBase64(updates[0])]) })
    expect(model.snapshot()).toEqual(snapshot)
    // Raw recovery actually reconstructs the rejected local state; not just a nonempty blob.
    const raw = blockedPayload(f.events.at(-1)!).raw
    const rejected = clone(decodeBase64(raw.find(bytes => bytes === encodeBase64(recovery.lastValidState))!))
    // Decode the emitted recovery pieces directly into an unguarded Y.Doc: the
    // rejected insertion must remain recoverable even though guarded open refuses it.
    Y.applyUpdate(rejected, decodeBase64(raw.find(bytes => bytes === encodeBase64(recovery.rejectedUpdate!))!))
    expect(firstText(rejected).toString()).toBe(action === 'typing' ? '<textColor color="red">word </textColor> tail' : '<textColor color="red">a </textColor>b.')
    expect(firstText(rejected).toDelta()).toEqual(firstText(f.internals.doc).toDelta())
    expect(firstText(rejected).toDelta()).toEqual(expect.arrayContaining([
      expect.objectContaining({ insert: action === 'typing' ? 'word ' : 'a ', attributes: { textColor: { color: 'red' } } }),
    ]))
    const frozen = model.encodeState()
    model.editor.commands.insertContentAt(1, 'Never')
    expect(model.encodeState()).toEqual(frozen)
    await expectStopped(f)
  })

  it.each(['DOMObserver drain', 'composition commit'])('blocks a final boundary-space mutation during flush (%s), never reporting Flushed', async phase => {
    const f = fixture()
    await f.open('[word]{.red} tail\n')
    const model = f.internals.model
    const valid = model.encodeState()
    const snapshot = model.snapshot()
    const view = model.editor.view
    const colored = view.dom.querySelector('[data-text-color="red"]')!.firstChild as Text
    expect(colored.data).toBe('word')
    const observer = (view as typeof view & { domObserver: { flush(): void } }).domObserver
    const drained = vi.spyOn(observer, 'flush') // call-through: use the real PM DOM reader
    let composing = phase === 'composition commit'
    Object.defineProperty(view, 'composing', { configurable: true, get: () => composing })
    // jsdom has no real IME. Simulate only its final DOM mutation, not the PM/Yjs
    // transaction or validation; both are exercised by the actual observer.
    const blur = vi.spyOn(view.dom as HTMLElement, 'blur').mockImplementationOnce(() => {
      if (!composing) colored.data = 'word '
    })
    try {
      const before = f.events.length
      const flushing = f.send('flush')
      if (composing) {
        await new Promise<void>(resolve => setTimeout(resolve, 5))
        expect(blur).toHaveBeenCalledOnce()
        expect(f.events).toHaveLength(before)
        // The input barrier is already up; the in-flight composition can finish.
        colored.data = 'word '
        composing = false
      }
      await flushing
      expect(drained).toHaveBeenCalled()
      expect(f.events.slice(before).map(e => e.event)).toEqual(['blocked'])
      expect(model.blocked).toBe(true)
      expect(model.snapshot()).toEqual(snapshot)
      const recovery = model.recovery()
      expect(recovery.lastValidState).toEqual(valid)
      expect(recovery.rejectedUpdate).toBeDefined()
      expect(blockedPayload(f.events.at(-1)!)).toMatchObject({ snapshot, raw: expect.arrayContaining([encodeBase64(valid), encodeBase64(recovery.rejectedUpdate!)]) })
      const recovered = clone(valid)
      Y.applyUpdate(recovered, recovery.rejectedUpdate!)
      expect(firstText(recovered).toDelta()).toEqual(expect.arrayContaining([
        { insert: 'word ', attributes: { textColor: { color: 'red' } } },
      ]))
      await expectStopped(f)
    } finally {
      blur.mockRestore()
      drained.mockRestore()
    }
  })

  it('queues an unsafe update submitted before the first render, then blocks it without Applied or write debt', async () => {
    const f = fixture()
    await f.send('prepare', { markdown: 'a b.\n' })
    const prepared = f.events.at(-1)!.payload as { seed: string }
    const foreign = clone(decodeBase64(prepared.seed))
    const vector = Y.encodeStateVector(foreign)
    firstText(foreign).format(0, 2, { textColor: { color: 'red' } })
    const update = encodeBase64(Y.encodeStateAsUpdate(foreign, vector))
    const before = f.events.length
    const initializing = f.send('initialize', { markdown: 'a b.\n', state: prepared.seed, readOnly: false })
    // Both commands arrive before dispatch/binding/first render. The real runtime
    // serializes them; do not mock its Ready promise or bypass its command queue.
    expect(f.element.childElementCount).toBe(0)
    expect(f.events).toHaveLength(before)
    const applying = f.send('applyUpdate', { update })
    await Promise.all([initializing, applying])
    expect(f.events.slice(before).map(e => e.event)).toEqual(['ready', 'blocked'])
    expect(blockedPayload(f.events.at(-1)!)).toMatchObject({ snapshot: { markdown: 'a b.\n', generation: 0 }, raw: expect.arrayContaining([update]) })
    expect(firstText(f.internals.doc).toDelta()).toEqual([{ insert: 'a b.' }])
    await expectStopped(f)
  })

  it.each([
    { source: 'Ordinary **bold**, *italic* and text.\n', text: 'Ordinary bold, italic and text.' },
    { source: '[a]{.red}[b]{.blue} [c]{.gray}\n', text: 'ab c' },
    { source: '[one two]{.red} [three four]{.blue}\n', text: 'one two three four' },
    { source: '[outer[inner]{.blue}tail]{.gray}\n', text: 'outerinnertail' },
  ])('keeps safe source editable through local ACK/flush/close/reseed/reopen cycles: $source', async ({ source, text }) => {
    const f = fixture()
    let acknowledged = source
    let expectedText = text
    for (let cycle = 0; cycle < 4; cycle++) {
      const beforeOpen = f.events.length
      await f.open(acknowledged)
      expect(f.events.slice(beforeOpen).map(e => e.event)).toEqual(['prepared', 'ready'])
      const model = f.internals.model
      expect(model.editor.isEditable).toBe(true)
      expect(model.editor.getText()).toBe(expectedText)
      // Type inside a run (not at its whitespace boundary) and change a safe color range.
      const beforeEdit = f.events.length
      model.editor.commands.insertContentAt(2, 'x')
      expectedText = expectedText.slice(0, 1) + 'x' + expectedText.slice(1)
      expect(f.events.slice(beforeEdit).map(e => e.event)).toEqual(['localUpdate', 'changed'])
      model.editor.chain().setTextSelection({ from: 1, to: 2 }).setTextColor(cycle % 2 ? 'green' : 'red').run()
      expect(model.editor.getText()).toBe(expectedText)
      expect(model.blocked).toBe(false)
      await f.send('flush')
      expect(f.events.at(-1)?.event).toBe('flushed')
      const flushed = f.events.at(-1)!.payload as Snapshot
      expect(flushed).toEqual(model.snapshot())
      // Local ACK simulation ONLY: no Remote server, CAS, network or native persistence.
      acknowledged = flushed.markdown
      expect(() => createSeedOffer(acknowledged)).not.toThrow()
      await f.send('dispose')
      expect(f.events.at(-1)?.event).toBe('disposed')
      expect(f.element.childElementCount).toBe(0)
      f.nextGeneration()
    }
    // Check the final acknowledged edit too, not merely the previous cycle's snapshot.
    await f.open(acknowledged)
    expect(f.internals.model.editor.getText()).toBe(expectedText)
    expect(f.events.some(e => e.event === 'blocked')).toBe(false)
  })
})
