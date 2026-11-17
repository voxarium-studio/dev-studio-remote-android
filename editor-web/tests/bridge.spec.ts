// @vitest-environment jsdom
import { Buffer } from 'node:buffer'
import { afterEach, describe, expect, it } from 'vitest'
import * as Y from 'yjs'
import { NativeBridge, createSeedOffer, decodeBase64, encodeBase64, type BridgeEvent, type Command } from '../src/bridge'
import { seedUpdate } from '../src/collab/seed'

const bridges: NativeBridge[] = []
afterEach(() => { bridges.splice(0).forEach(b => b.destroy()); document.body.replaceChildren() })
const tick = () => new Promise(resolve => setTimeout(resolve, 30))
const context = { version: 1 as const, documentGeneration: 1, connectionGeneration: 1, sid: 'session-one' }
function fixture() {
  const events: BridgeEvent[] = []
  const element = document.body.appendChild(document.createElement('div'))
  const bridge = new NativeBridge(element, e => events.push(e))
  bridges.push(bridge)
  const init: Command = { ...context, requestId: 1, command: 'init', markdown: 'Hello.\n', state: createSeedOffer('Hello.\n') }
  bridge.handle(init)
  return { bridge, events, element }
}

describe('native seam (not Remote wire)', () => {
  it('opens cleanly, responds only to explicit serialize, disposes without writes', async () => {
    const { bridge, events, element } = fixture()
    await tick()
    expect(events.map(e => e.event)).toEqual(['ready'])
    expect(element.querySelector('.ProseMirror')).not.toBeNull()
    bridge.handle({ ...context, requestId: 2, command: 'serialize' })
    expect(events.at(-1)).toMatchObject({ event: 'snapshot', requestId: 2, payload: { markdown: 'Hello.\n', generation: 0 } })
    bridge.handle({ ...context, requestId: 3, command: 'dispose' })
    expect(element.childElementCount).toBe(0)
    expect(events.map(e => e.event)).toEqual(['ready', 'snapshot', 'disposed'])
  })

  it('rejects stale contexts, duplicate requests, arbitrary execution and bad base64', async () => {
    const { bridge, events } = fixture()
    await tick()
    for (const command of [
      { ...context, documentGeneration: 0, requestId: 2, command: 'dispose' },
      { ...context, connectionGeneration: 0, requestId: 3, command: 'serialize' },
      { ...context, sid: 'retired', requestId: 4, command: 'serialize' },
      { ...context, requestId: 1, command: 'serialize' },
      { ...context, requestId: 5, command: 'format', action: 'constructor' },
      { ...context, requestId: 6, command: 'applyUpdate', update: 'not base64' },
    ]) bridge.handle(command)
    expect(events.filter(e => e.event === 'error')).toHaveLength(6)
    expect(events.some(e => e.event === 'localUpdate' || e.event === 'changed' || e.event === 'disposed')).toBe(false)
  })

  it('blocks snapshot/format after incompatible state; preserves raw recovery without echo', async () => {
    const events: BridgeEvent[] = []
    const bridge = new NativeBridge(document.body.appendChild(document.createElement('div')), e => events.push(e))
    bridges.push(bridge)
    const seed = seedUpdate('Safe.\n')
    bridge.handle({ ...context, requestId: 1, command: 'init', markdown: 'Safe.\n', state: encodeBase64(seed) })
    await tick()
    const remote = new Y.Doc()
    Y.applyUpdate(remote, seed)
    const vector = Y.encodeStateVector(remote)
    remote.getXmlFragment('default').insert(0, [new Y.XmlElement('futureBlock')])
    const update = Y.encodeStateAsUpdate(remote, vector)
    bridge.handle({ ...context, requestId: 2, command: 'applyUpdate', update: encodeBase64(update) })
    bridge.handle({ ...context, requestId: 3, command: 'serialize' })
    bridge.handle({ ...context, requestId: 4, command: 'format', action: 'bold' })
    const blocked = events.find(e => e.event === 'blocked')!
    expect(blocked.payload).toMatchObject({ recovery: { markdown: 'Safe.\n', rejectedUpdate: encodeBase64(update) } })
    expect(events.filter(e => e.event === 'error')).toHaveLength(2)
    expect(events.some(e => ['snapshot', 'localUpdate', 'changed'].includes(e.event))).toBe(false)
    remote.destroy()
  })

  it('seed offers stay isolated and base64 is canonical', () => {
    const a = createSeedOffer('One.\n'), b = createSeedOffer('One.\n')
    expect(a).not.toBe(b)
    expect(encodeBase64(decodeBase64(a))).toBe(a)
    expect(() => decodeBase64('YQ=')).toThrow()
    expect(() => decodeBase64('YR==')).toThrow()
    expect(() => decodeBase64('!!!!')).toThrow()
  })

  it('blocks code-fence metadata before the exact upstream seed codec can drop it', () => {
    expect(() => createSeedOffer('```ts keep-this-metadata\nconst x = 1\n```\n')).toThrow(/codeBlock.meta/)
    expect(() => createSeedOffer('```ts\nconst x = 1\n```\n')).not.toThrow()
  })
})

describe('base64 decoded-size and canonical encoding contract', () => {
  const maxBytes = 8 * 1024 * 1024

  for (const size of [4_000_000, 8_000_000, maxBytes]) {
    for (const pattern of ['zero', 'varied'] as const) {
      it(`round-trips every byte of ${size} ${pattern} bytes without stack overflow`, () => {
        // Zero bytes reproduce the reported failure; the second pattern exercises all 256 values.
        const input = Buffer.alloc(size)
        if (pattern === 'varied') {
          for (let i = 0; i < input.length; i++) input[i] = (i * 31 + (i >>> 8)) & 255
        }
        const encoded = encodeBase64(input)
        expect(encoded === input.toString('base64')).toBe(true)
        const decoded = decodeBase64(encoded)
        expect(decoded).toBeInstanceOf(Uint8Array)
        expect(decoded.length).toBe(size)
        expect(Buffer.from(decoded.buffer, decoded.byteOffset, decoded.byteLength).equals(input)).toBe(true)
      }, 60_000)
    }
  }

  it('rejects 8MiB+1 decoded bytes even though their encoded length equals the limit case', () => {
    const encoded = Buffer.alloc(maxBytes + 1).toString('base64')
    expect(encoded.length).toBe(4 * Math.ceil(maxBytes / 3))
    expect(() => decodeBase64(encoded)).toThrow('Invalid or oversized base64')
  })

  it.each([
    ['alphabet', '!!!!'], ['URL-safe alphabet', '____'], ['non-ASCII', '\u00e9AAA'],
    ['missing padding', 'YQ'], ['short padding', 'YQ='], ['extra padding', 'YQ==='],
    ['internal padding', 'Y=Q='], ['padding followed by data', 'YQ==AAAA'],
    ['leading whitespace', ' YQ=='], ['trailing whitespace', 'YQ==\n'],
    ['embedded space', 'Y Q='], ['embedded tab', 'Y\tQ='], ['embedded CRLF', 'Y\r\nQ=='],
    ['noncanonical two-pad bits', 'YR=='], ['noncanonical one-pad bits', 'YWJ='],
  ])('rejects %s', (_label, encoded) => {
    expect(() => decodeBase64(encoded)).toThrow('Invalid or oversized base64')
  })

  it.each(['', 'YQ==', 'YWI=', 'YWJj', '+/8='])('retains canonical small input %j', encoded => {
    expect(encodeBase64(decodeBase64(encoded))).toBe(encoded)
  })
})
