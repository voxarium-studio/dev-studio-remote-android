// @vitest-environment jsdom
/**
 * VS-72: real independent editors, not a Y.Text transport simulation.
 * Adapted from Studio frontend/src/lib/collab/editor.spec.ts (see package provenance).
 * Contract: remote-android/protocol.md §§5, 7, 11; block-editor spec §8.2.
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { DOMSerializer } from '@tiptap/pm/model'
import { afterEach, describe, expect, it } from 'vitest'
import * as Y from 'yjs'

import { EditorModel } from '../src/model'
import { roomSchema, seedUpdate } from '../src/collab/seed'
import { parseDocument, serializeDocument } from '../src/md/document'
import { proseMirrorToMdast, type PmNode } from '../src/md/prosemirror'

type Snapshot = { markdown: string; generation: number }
interface Member {
  model: EditorModel
  element: HTMLElement
  updates: Uint8Array[]
  changes: Snapshot[]
  blocked: string[]
}

const members: Member[] = []
const documents: Y.Doc[] = []
const elements: HTMLElement[] = []
const settle = (ms = 30) => new Promise<void>((resolve) => setTimeout(resolve, ms))

afterEach(() => {
  for (const member of members.splice(0)) member.model.destroy()
  for (const doc of documents.splice(0)) doc.destroy()
  for (const element of elements.splice(0)) element.remove()
})

function element(): HTMLElement {
  const target = document.body.appendChild(document.createElement('div'))
  elements.push(target)
  return target
}

function open(markdown: string, state = seedUpdate(markdown)): Member {
  const target = element()
  const updates: Uint8Array[] = []
  const changes: Snapshot[] = []
  const blocked: string[] = []
  const model = EditorModel.open({
    markdown,
    state,
    element: target,
    onUpdate: (update) => updates.push(update.slice()),
    onChange: (snapshot) => changes.push({ ...snapshot }),
    onBlocked: (reason) => blocked.push(reason),
  })
  const member = { model, element: target, updates, changes, blocked }
  members.push(member)
  return member
}

/** Relay callbacks, with no shared Y.Doc or editor state between participants. */
async function pair(markdown: string, state = seedUpdate(markdown)) {
  const alice = open(markdown, state)
  const bob = open(markdown, state)
  await settle()
  let aliceDelivered = 0
  let bobDelivered = 0
  const relay = async () => {
    // Flush until quiescent, with a limit that diagnoses an accidental remote echo loop.
    for (let round = 0; round < 10; round += 1) {
      while (aliceDelivered < alice.updates.length) {
        expect(bob.model.applyRemote(alice.updates[aliceDelivered++])).toBe(true)
      }
      while (bobDelivered < bob.updates.length) {
        expect(alice.model.applyRemote(bob.updates[bobDelivered++])).toBe(true)
      }
      await settle()
      if (aliceDelivered === alice.updates.length && bobDelivered === bob.updates.length) return
    }
    throw new Error('Remote updates did not quiesce (possible echo/repair loop)')
  }
  return { alice, bob, relay }
}

function find(member: Member, text: string): number {
  let found = -1
  member.model.editor.state.doc.descendants((node, pos) => {
    if (found < 0 && node.isText && node.text?.includes(text)) found = pos + node.text.indexOf(text)
  })
  if (found < 0) throw new Error(`Text not found: ${text}`)
  return found
}

function roomDoc(state: Uint8Array): Y.Doc {
  const doc = new Y.Doc()
  documents.push(doc)
  Y.applyUpdate(doc, state)
  return doc
}

function firstText(doc: Y.Doc): Y.XmlText {
  const walk = (node: Y.XmlFragment | Y.XmlElement): Y.XmlText | undefined => {
    for (const child of node.toArray()) {
      if (child instanceof Y.XmlText) return child
      if (child instanceof Y.XmlElement) {
        const text = walk(child)
        if (text) return text
      }
    }
  }
  const text = walk(doc.getXmlFragment('default'))
  if (!text) throw new Error('Fixture has no Y.XmlText')
  return text
}

function mutate(state: Uint8Array, change: (doc: Y.Doc) => void) {
  const doc = roomDoc(state)
  const vector = Y.encodeStateVector(doc)
  doc.transact(() => change(doc))
  return { doc, delta: Y.encodeStateAsUpdate(doc, vector), state: Y.encodeStateAsUpdate(doc) }
}

const DOCUMENT = [
  '# Dash ability', '',
  'Owned by **[[marina]]**, see *the plan* first.', '',
  '- [ ] write the spec', '- [x] agree the name', '- a note, not a task', '',
  '| Stat | Value |', '| :--- | ---: |', '| Range | 4 |', '',
].join('\n')

describe('EditorModel: opening and disposal never write', () => {
  it.each([
    ['table final', DOCUMENT],
    ['plain list final', '- first\n- second\n'],
    ['task list final', '- [x] done\n- plain item\n'],
    ['empty file', ''],
    ['noncanonical source', '---\r\ncustom:  untouched\r\n---\r\n#   Heading\r\n\r\n* item'],
  ])('%s: ten open/dispose cycles emit no updates or changes', async (_name, source) => {
    const state = seedUpdate(source)
    for (let cycle = 0; cycle < 10; cycle += 1) {
      const member = open(source, state)
      const baseline = member.model.snapshot()
      await settle()
      // The snapshot may normalize noncanonical Markdown. Merely observing that
      // normalized view must never become an onChange/save trigger (protocol §7).
      expect(member.model.snapshot()).toEqual(baseline)
      expect(baseline.generation).toBe(0)
      if (_name !== 'noncanonical source') expect(baseline.markdown).toBe(source)
      expect(member.model.blocked).toBe(false)
      expect(member.updates).toHaveLength(0)
      expect(member.changes).toHaveLength(0)
      member.model.destroy()
      members.splice(members.indexOf(member), 1)
      await settle()
      expect(member.updates).toHaveLength(0)
      expect(member.changes).toHaveLength(0)
      expect(member.blocked).toHaveLength(0)
    }
  })

  it('selection and focus do not become document edits', async () => {
    const member = open('Somewhere in here.\n')
    await settle()
    member.model.editor.commands.setTextSelection(3)
    member.model.editor.view.focus()
    member.model.editor.view.dom.blur()
    await settle()
    expect(member.model.snapshot()).toEqual({ markdown: 'Somewhere in here.\n', generation: 0 })
    expect(member.updates).toHaveLength(0)
    expect(member.changes).toHaveLength(0)
  })
})

describe('two real EditorModels', () => {
  it.each(['alice', 'bob'] as const)('%s can type a trailing space, then the peer types a word and undoes it', async (sender) => {
    const { alice, bob, relay } = await pair('Hello.\n')
    const first = sender === 'alice' ? alice : bob
    const second = sender === 'alice' ? bob : alice
    const assertLive = () => {
      for (const member of [alice, bob]) {
        expect(member.model.blocked).toBe(false)
        expect(member.model.editor.isEditable).toBe(true)
        expect(member.blocked).toHaveLength(0)
      }
      expect(alice.model.editor.getJSON()).toEqual(bob.model.editor.getJSON())
    }
    const typeAtEnd = (member: Member, text: string) => member.model.editor.view.dispatch(
      member.model.editor.state.tr.insertText(text, member.model.editor.state.doc.content.size - 1),
    )
    typeAtEnd(first, ' ')
    await relay()
    assertLive()
    // Markdown may trim it; the live CRDT and both PM views must retain the space.
    expect(alice.model.editor.state.doc.textContent).toBe('Hello. ')
    expect(first.updates.length).toBeGreaterThan(0)
    expect(second.updates).toHaveLength(0)
    typeAtEnd(second, 'world')
    await relay()
    assertLive()
    expect(alice.model.snapshot().markdown).toBe('Hello. world\n')
    expect(bob.model.snapshot().markdown).toBe('Hello. world\n')
    second.model.editor.commands.undo()
    await relay()
    assertLive()
    expect(alice.model.editor.state.doc.textContent).toBe('Hello. ')
    expect(bob.model.editor.state.doc.textContent).toBe('Hello. ')
  })

  it.each(['alice', 'bob'] as const)('%s can press Enter into an empty paragraph, then the peer types and undoes a word', async (sender) => {
    const { alice, bob, relay } = await pair('Hello.\n')
    const first = sender === 'alice' ? alice : bob
    const second = sender === 'alice' ? bob : alice
    first.model.editor.commands.setTextSelection(first.model.editor.state.doc.content.size - 1)
    expect(first.model.editor.commands.splitBlock()).toBe(true)
    await relay()
    for (const member of [alice, bob]) {
      expect(member.model.blocked).toBe(false)
      expect(member.model.editor.state.doc.childCount).toBe(2)
      expect(member.model.editor.state.doc.lastChild?.type.name).toBe('paragraph')
      expect(member.model.editor.state.doc.lastChild?.content.size).toBe(0)
    }
    second.model.editor.view.dispatch(second.model.editor.state.tr.insertText(
      'Next', second.model.editor.state.doc.content.size - 1,
    ))
    await relay()
    for (const member of [alice, bob]) {
      expect(member.model.blocked).toBe(false)
      expect(member.model.snapshot().markdown).toBe('Hello.\n\nNext\n')
    }
    second.model.editor.commands.undo()
    await relay()
    for (const member of [alice, bob]) {
      expect(member.model.blocked).toBe(false)
      expect(member.model.editor.isEditable).toBe(true)
      expect(member.blocked).toHaveLength(0)
      expect(member.model.editor.state.doc.childCount).toBe(2)
      expect(member.model.editor.state.doc.lastChild?.textContent).toBe('')
      expect(member.model.snapshot().markdown).toBe('Hello.\n')
    }
    expect(alice.model.editor.getJSON()).toEqual(bob.model.editor.getJSON())
  })

  it('converge without echo and preserve carried marks, nulls, and envelope', async () => {
    const source = `---\nowner:  Marina\n---\n${DOCUMENT}`
    const { alice, bob, relay } = await pair(source)
    expect(alice.updates).toHaveLength(0)
    expect(bob.updates).toHaveLength(0)
    alice.model.editor.commands.insertContentAt(find(alice, 'plan'), 'real ')
    bob.model.editor.commands.insertContentAt(find(bob, 'write the spec'), 'first ')
    await relay()
    const expected = source.replace('*the plan*', '*the real plan*').replace('write the spec', 'first write the spec')
    expect(alice.model.snapshot().markdown).toBe(expected)
    expect(bob.model.snapshot().markdown).toBe(expected)
    expect(alice.updates.length).toBeGreaterThan(0)
    expect(bob.updates.length).toBeGreaterThan(0)
    const counts = [alice.updates.length, bob.updates.length]
    await relay()
    expect([alice.updates.length, bob.updates.length]).toEqual(counts)
    for (const member of [alice, bob]) {
      expect(member.changes.length).toBeGreaterThan(0)
      expect(member.changes.at(-1)).toEqual(member.model.snapshot())
      const plainItems: unknown[] = []
      member.model.editor.state.doc.descendants((node) => {
        if (node.type.name === 'taskItem' && node.textContent === 'a note, not a task') plainItems.push(node.attrs.checked)
      })
      expect(plainItems).toEqual([null])
    }
  })

  it('carries newly added and removed marks on a wiki link between editors', async () => {
    const source = 'Ask [[marina]] today.\n'
    const { alice, bob, relay } = await pair(source)
    alice.model.editor.chain().setTextSelection({ from: 1, to: alice.model.editor.state.doc.content.size - 1 }).toggleBold().run()
    await relay()
    expect(alice.model.snapshot().markdown).toBe('**Ask [[marina]] today.**\n')
    expect(bob.model.snapshot().markdown).toBe('**Ask [[marina]] today.**\n')
    bob.model.editor.chain().setTextSelection({ from: 1, to: bob.model.editor.state.doc.content.size - 1 }).toggleBold().run()
    await relay()
    expect(alice.model.snapshot().markdown).toBe(source)
    expect(bob.model.snapshot().markdown).toBe(source)
  })

  it('keeps links carried by image atoms while editing adjacent text', async () => {
    const source = 'Before **[[plan]]** and [![badge](badge.png)](https://example.test).\n'
    const { alice, bob, relay } = await pair(source)
    alice.model.editor.commands.insertContentAt(find(alice, 'Before'), 'After ')
    await relay()
    const expected = `After ${source}`
    expect(alice.model.snapshot().markdown).toBe(expected)
    expect(bob.model.snapshot().markdown).toBe(expected)
    const imageMarks: string[][] = []
    bob.model.editor.state.doc.descendants((node) => {
      if (node.type.name === 'image') imageMarks.push(node.marks.map(mark => mark.type.name))
    })
    expect(imageMarks).toEqual([['link']])
  })

  it('undoes only local edits, never the peer edit or the initial seed', async () => {
    const { alice, bob, relay } = await pair('One.\n\nTwo.\n')
    alice.model.editor.commands.insertContentAt(find(alice, 'One'), 'A ')
    await relay()
    await settle(550) // Separate the collaboration undo capture windows.
    bob.model.editor.commands.insertContentAt(find(bob, 'Two'), 'B ')
    await relay()
    expect(alice.model.snapshot().markdown).toBe('A One.\n\nB Two.\n')
    alice.model.editor.commands.undo()
    await relay()
    expect(alice.model.snapshot().markdown).toBe('One.\n\nB Two.\n')
    expect(bob.model.snapshot().markdown).toBe('One.\n\nB Two.\n')
    alice.model.editor.commands.undo()
    await relay()
    expect(bob.model.snapshot().markdown).toBe('One.\n\nB Two.\n')
    bob.model.editor.commands.undo()
    await relay()
    expect(alice.model.snapshot().markdown).toBe('One.\n\nTwo.\n')
    expect(bob.model.snapshot().markdown).toBe('One.\n\nTwo.\n')
  })

  it('keeps both concurrent insertions at the same position', async () => {
    const { alice, bob, relay } = await pair('Base.\n')
    alice.model.editor.commands.insertContentAt(1, 'A ')
    bob.model.editor.commands.insertContentAt(1, 'B ')
    await relay()
    expect(alice.model.snapshot().markdown).toBe(bob.model.snapshot().markdown)
    expect(alice.model.snapshot().markdown).toMatch(/^(A B |B A )Base\.\n$/)
  })
})

describe('fixture corpus through two real editors', () => {
  const fixtures = ['colors.md', 'dash-ability.md', 'directives.md', 'lists-and-tables.md', 'unsupported.md', 'wikilinks.md']

  it.each(fixtures)('%s preserves edits or fails closed for the known unsafe color fixture', async (name) => {
    // In Vitest's jsdom environment import.meta.url is browser-facing on Windows.
    const source = readFileSync(resolve('tests', 'fixtures', name), 'utf8')
    // VS-82: retain the unchanged colors fixture as a NEGATIVE control. Flattening
    // is allowed; losing visible spaces on the next Markdown seed is not.
    if (name === 'colors.md') {
      expect(() => open(source)).toThrow(/INTEROP-01/)
      return
    }
    const expected = source
    const { alice, bob, relay } = await pair(source)
    const renderedMarkdown = (member: Member) => serializeDocument(
      proseMirrorToMdast(member.model.editor.getJSON() as PmNode), parseDocument(source).envelope,
    )
    for (const member of [alice, bob]) {
      expect(member.model.snapshot()).toEqual({ markdown: expected, generation: 0 })
      expect(renderedMarkdown(member)).toBe(expected)
      expect(member.updates).toHaveLength(0)
      expect(member.changes).toHaveLength(0)
    }
    const heading = source.match(/^#+ (.+)$/m)?.[1]
    if (!heading) throw new Error(`Fixture has no heading: ${name}`)
    alice.model.editor.commands.insertContentAt(find(alice, heading), 'VS72 ')
    await relay()
    bob.model.editor.commands.insertContentAt(find(bob, 'VS72 '), 'Peer ')
    await relay()
    const edited = expected.replace(/^(#+ )/m, '$1Peer VS72 ')
    for (const member of [alice, bob]) {
      expect(member.model.blocked).toBe(false)
      expect(member.model.snapshot().markdown).toBe(edited)
      expect(renderedMarkdown(member)).toBe(edited)
      expect(edited).not.toMatch(/[\uE000-\uE003]/)
    }
    // Last-leave destroys the room: reopen from persisted Markdown, NOT old XML.
    const reopened = open(edited)
    await settle()
    expect(reopened.model.snapshot()).toEqual({ markdown: edited, generation: 0 })
    expect(reopened.updates).toHaveLength(0)
    expect(reopened.changes).toHaveLength(0)
  })
})

describe('safe DOM presentation', () => {
  const dangerousElements = 'img[src], iframe, script, object, embed, a[href], [onerror], [onload]'

  it('keeps image URLs and unsupported HTML as data, including the schema DOM serializer', async () => {
    const source = [
      '# Safe presentation', '',
      '![remote](https://example.invalid/private.png "do not fetch")', '',
      '[unsafe link](javascript:alert%281%29)', '',
      '<script>window.__vs72Executed = true</script>', '',
      '<iframe src="https://example.invalid/frame"></iframe>', '',
      '<img src="https://example.invalid/raw.png" onerror="window.__vs72Executed = true">', '',
    ].join('\n')
    const member = open(source)
    await settle()
    expect(member.element.querySelector(dangerousElements)).toBeNull()
    expect(member.element.querySelector('[data-node-view="image"]')).not.toBeNull()
    expect(member.element.querySelector('[data-node-view="unsupportedBlock"]')).not.toBeNull()
    const detached = document.createElement('div')
    detached.append(DOMSerializer.fromSchema(roomSchema()).serializeFragment(member.model.editor.state.doc.content))
    expect(detached.querySelector(dangerousElements)).toBeNull()
    expect(member.model.snapshot().markdown).toBe(source.replace('javascript:', 'javascript\\:'))
    expect(member.updates).toHaveLength(0)
    expect(member.changes).toHaveLength(0)
    expect((window as Window & { __vs72Executed?: boolean }).__vs72Executed).toBeUndefined()
  })

  it('does not materialize executable HTML from a rich clipboard paste', async () => {
    const member = open('Safe.\n')
    await settle()
    member.model.editor.commands.setTextSelection(1)
    member.model.editor.view.pasteHTML([
      '<p>Pasted text <marquee>unknown HTML</marquee>',
      '<img src="https://example.invalid/pasted.png" onerror="window.__vs72Executed = true">',
      '<script>window.__vs72Executed = true</script>',
      '<iframe src="https://example.invalid/pasted-frame"></iframe></p>',
    ].join(''), new Event('paste') as ClipboardEvent)
    await settle()
    expect(member.element.querySelector(dangerousElements)).toBeNull()
    expect(member.element.querySelector('marquee')).toBeNull()
    expect(member.model.blocked).toBe(false)
    expect(member.model.snapshot().markdown).toContain('Pasted text unknown HTML')
    expect(member.model.snapshot().markdown).toContain('https://example.invalid/pasted.png')
    expect(member.model.snapshot().markdown).not.toContain('<script>')
    expect(member.model.snapshot().markdown).not.toContain('<iframe')
    expect((window as Window & { __vs72Executed?: boolean }).__vs72Executed).toBeUndefined()
  })
})

describe('seed offers and initial-render races', () => {
  it.each([0, 1])('opens only the CAS winner (offer %i), never merges independently built seeds', async (winner) => {
    const source = '# Single heading\n\nOne paragraph.\n'
    const offers = [seedUpdate(source), seedUpdate(source)]
    // Remote seed is first-wins CAS, NOT Y.mergeUpdates(offers). The rejected offer
    // never reaches either live editor; both open the returned winner state.
    expect(offers[0]).not.toEqual(offers[1])
    const { alice, bob, relay } = await pair(source, offers[winner])
    for (const member of [alice, bob]) {
      expect(member.model.editor.state.doc.childCount).toBe(2)
      expect(member.model.editor.getText()).toBe('Single heading\n\nOne paragraph.')
      expect(member.updates).toHaveLength(0)
      expect(member.changes).toHaveLength(0)
    }
    alice.model.editor.commands.insertContentAt(find(alice, 'One paragraph'), 'Only ')
    await relay()
    expect(alice.model.snapshot().markdown).toBe('# Single heading\n\nOnly One paragraph.\n')
    expect(bob.model.snapshot().markdown).toBe(alice.model.snapshot().markdown)
  })

  it('does not reset sequential remote changes received before the first-render tick', async () => {
    const source = 'Original.\n'
    const seed = seedUpdate(source)
    const remote = roomDoc(seed)
    const vector0 = Y.encodeStateVector(remote)
    firstText(remote).insert(0, 'First ')
    const update1 = Y.encodeStateAsUpdate(remote, vector0)
    const vector1 = Y.encodeStateVector(remote)
    firstText(remote).insert(0, 'Second ')
    const update2 = Y.encodeStateAsUpdate(remote, vector1)
    const member = open(source, seed)
    // No await: the binding's first-render callback has not run yet.
    expect(member.model.applyRemote(update1)).toBe(true)
    expect(member.model.applyRemote(update2)).toBe(true)
    const generation = member.model.snapshot().generation
    expect(generation).toBeGreaterThan(0)
    await settle(80)
    expect(member.model.snapshot()).toEqual({ markdown: 'Second First Original.\n', generation })
    expect(member.changes.at(-1)).toEqual(member.model.snapshot())
    expect(member.updates).toHaveLength(0)
    // Duplicate delivery is idempotent, including generation and callbacks.
    const changes = member.changes.length
    expect(member.model.applyRemote(update1)).toBe(true)
    await settle()
    expect(member.model.snapshot().generation).toBe(generation)
    expect(member.changes).toHaveLength(changes)
  })
})

const schemaViolations: [string, (doc: Y.Doc) => void][] = [
  ['unknown node type', (doc) => doc.getXmlFragment('default').insert(0, [new Y.XmlElement('futureBlock')])],
  ['unknown node attribute', (doc) => {
    const paragraph = doc.getXmlFragment('default').get(0) as Y.XmlElement
    paragraph.setAttribute('futureAttribute', 'must not disappear')
  }],
  ['unknown text mark', (doc) => firstText(doc).format(0, 1, { futureMark: {} })],
  ['invalid textColor palette value', (doc) => firstText(doc).format(0, 1, { textColor: { color: 'ultraviolet' } })],
  ['inconsistent wikiLink semantic attributes', (doc) => {
    const paragraph = doc.getXmlFragment('default').get(0) as Y.XmlElement
    const carrier = new Y.XmlElement<{ value: string; target: string }>('wikiLink')
    carrier.setAttribute('value', 'visible-document')
    carrier.setAttribute('target', 'different-navigation-target')
    paragraph.insert(0, [carrier as unknown as Y.XmlElement])
  }],
  ['invalid taskItem checked string', (doc) => {
    const taskDoc = roomDoc(seedUpdate('- [x] Task\n'))
    const taskList = taskDoc.getXmlFragment('default').get(0) as Y.XmlElement
    const item = taskList.get(0) as Y.XmlElement
    item.setAttribute('checked', 'false')
    const root = doc.getXmlFragment('default')
    root.delete(0, root.length)
    root.insert(0, [taskList.clone()])
  }],
  ['invalid toggle open string', (doc) => {
    const toggleDoc = roomDoc(seedUpdate(':::toggle[Details]\nBody.\n:::\n'))
    const toggle = toggleDoc.getXmlFragment('default').get(0) as Y.XmlElement
    toggle.setAttribute('open', 'false')
    const root = doc.getXmlFragment('default')
    root.delete(0, root.length)
    root.insert(0, [toggle.clone()])
  }],
  ['unknown carried mark', (doc) => {
    const paragraph = doc.getXmlFragment('default').get(0) as Y.XmlElement
    const carrier = new Y.XmlElement<{ target: string; carried: { marks: { type: string }[] } }>('wikiLink')
    carrier.setAttribute('target', 'plan')
    carrier.setAttribute('carried', { marks: [{ type: 'futureMark' }] })
    paragraph.insert(0, [carrier as unknown as Y.XmlElement])
  }],
  ['unknown attribute on a known carried mark', (doc) => {
    const paragraph = doc.getXmlFragment('default').get(0) as Y.XmlElement
    const carrier = new Y.XmlElement<{ value: string; target: string; carried: object }>('wikiLink')
    carrier.setAttribute('value', 'plan')
    carrier.setAttribute('target', 'plan')
    carrier.setAttribute('carried', { marks: [{ type: 'bold', attrs: { futureAttribute: 'preserve me' } }] })
    paragraph.insert(0, [carrier as unknown as Y.XmlElement])
  }],
  ['invalid heading level on a known node', (doc) => {
    const root = doc.getXmlFragment('default')
    const heading = new Y.XmlElement<{ level: number }>('heading')
    heading.setAttribute('level', 99)
    heading.insert(0, [new Y.XmlText('Invalid heading')])
    root.delete(0, root.length)
    root.insert(0, [heading as unknown as Y.XmlElement])
  }],
  ['schema-valid table cell attrs that cannot round-trip through Markdown', (doc) => {
    // colspan is a real PM schema attr, but this Markdown codec cannot preserve it.
    const tableSeed = roomDoc(seedUpdate('| Cell |\n| --- |\n| Value |\n'))
    const table = tableSeed.getXmlFragment('default').get(0) as Y.XmlElement
    const row = table.get(1) as Y.XmlElement
    const cell = row.get(0) as Y.XmlElement<{ colspan: number }>
    cell.setAttribute('colspan', 2)
    const root = doc.getXmlFragment('default')
    root.delete(0, root.length)
    root.insert(0, [table.clone()])
  }],
]

describe('schema preflight rejects instead of repairing the shared document', () => {
  it.each(schemaViolations)('rejects %s before binding an initial state', (_name, corrupt) => {
    const source = 'Safe.\n'
    const invalid = mutate(seedUpdate(source), corrupt)
    const original = Y.encodeStateAsUpdate(invalid.doc)
    const target = element()
    const updates: Uint8Array[] = []
    const changes: Snapshot[] = []
    expect(() => EditorModel.open({ markdown: source, state: invalid.state, element: target,
      onUpdate: (update) => updates.push(update), onChange: (snapshot) => changes.push(snapshot),
    })).toThrow()
    expect(target.querySelector('.ProseMirror')).toBeNull()
    expect(updates).toHaveLength(0)
    expect(changes).toHaveLength(0)
    expect(Y.encodeStateAsUpdate(invalid.doc)).toEqual(original)
  })

  it.each(schemaViolations)('freezes an existing editor on remote %s, without applying or repairing it', async (_name, corrupt) => {
    const source = 'Safe.\n'
    const seed = seedUpdate(source)
    const member = open(source, seed)
    await settle()
    const baseline = member.model.snapshot()
    const baselineJson = member.model.editor.getJSON()
    const invalid = mutate(seed, corrupt)
    const original = Y.encodeStateAsUpdate(invalid.doc)
    expect(member.model.applyRemote(invalid.delta)).toBe(false)
    await settle()
    expect(member.model.blocked).toBe(true)
    expect(member.model.editor.isEditable).toBe(false)
    expect(member.blocked.length).toBeGreaterThan(0)
    expect(member.model.snapshot()).toEqual(baseline)
    expect(member.model.editor.getJSON()).toEqual(baselineJson)
    expect(member.updates).toHaveLength(0)
    expect(member.changes).toHaveLength(0)
    expect(Y.encodeStateAsUpdate(invalid.doc)).toEqual(original)
    // Read-only is enforced at the model boundary, not just by a disabled toolbar.
    member.model.editor.commands.insertContentAt(1, 'Must not be written. ')
    await settle()
    expect(member.model.editor.getJSON()).toEqual(baselineJson)
    expect(member.model.snapshot()).toEqual(baseline)
    expect(member.updates).toHaveLength(0)
    expect(member.changes).toHaveLength(0)
    expect(member.model.applyRemote(invalid.delta)).toBe(false)
  })
})
