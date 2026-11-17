// @vitest-environment jsdom
/** VS-82 / INTEROP-01: reference-compatible codec stays unchanged; publication must fail closed. */
import { describe, expect, it } from 'vitest'
import * as Y from 'yjs'
import { yXmlFragmentToProsemirrorJSON } from '@tiptap/y-tiptap'
import { createSeedOffer, decodeBase64 } from '../src/bridge'
import { preflight, validateSourceMarkdown } from '../src/validation'
import { seedUpdate, roomSchema } from '../src/collab/seed'
import { parseDocument, serializeDocument } from '../src/md/document'
import { mdastToProseMirror, proseMirrorToMdast } from '../src/md/prosemirror'

const source = 'A [nested [emphasis]{.blue} span]{.gray}.\n'
function roundTrip(markdown: string): string {
  const parsed = parseDocument(markdown)
  const doc = new Y.Doc()
  try {
    Y.applyUpdate(doc, seedUpdate(markdown))
    const pm = roomSchema().nodeFromJSON(yXmlFragmentToProsemirrorJSON(doc.getXmlFragment('default')))
    return serializeDocument(proseMirrorToMdast(pm.toJSON()), parsed.envelope)
  } finally { doc.destroy() }
}
function visible(markdown: string): string {
  return roomSchema().nodeFromJSON(mdastToProseMirror(parseDocument(markdown).root)).textContent
}

describe('INTEROP-01 no-loss publication boundary', () => {
  it('reproduces reference reader loss: ACKed flattened colors lose two visible spaces on reopen', () => {
    const saved = roundTrip(source)
    expect(saved).toBe('A [nested ]{.gray}[emphasis]{.blue}[ span]{.gray}.\n')
    expect(visible(source)).toBe('A nested emphasis span.')
    expect(visible(saved)).toBe('A nestedemphasisspan.')
    // More saves cannot restore missing text. This is data loss, not normalization.
    let again = saved
    for (let cycle = 0; cycle < 4; cycle++) again = roundTrip(again)
    expect(visible(again)).not.toBe(visible(source))
  })

  it('blocks the original nested source before offering a damaging seed', () => {
    expect(() => createSeedOffer(source)).toThrow(/INTEROP-01/)
  })

  it.each([
    source,
    '[a ]{.red}[b]{.blue} tail\n',
    'head [ a]{.red}\n',
    '[a\t]{.red} next\n',
    'head [   ]{.red} tail\n',
    '[a  ]{.red}[ b ]{.blue}[  c]{.gray}\n',
    '[&#x20;boundary&#x20;]{.red} tail\n',
    'A [a \r]{.red}B.\n',
    'A [\r a]{.red}B.\n',
    '[внешний [😀 漢字]{.blue} хвост]{.gray}\n',
    '[a [b [c]{.red} d]{.blue} e]{.gray}\n',
  ])('blocks dangerous raw source before seed publication: %s', markdown => {
    expect(() => createSeedOffer(markdown)).toThrow(/INTEROP-01/)
  })

  it.each([
    source,
    '[внешний [😀 漢字]{.blue} хвост]{.gray}\n',
    '[a [b [c]{.red} d]{.blue} e]{.gray}\n',
  ])('blocks unsafe foreign full state independently of initial source: %s', markdown => {
    // Raw seedUpdate is deliberately unguarded, as a foreign Studio seed would be.
    expect(() => preflight(seedUpdate(markdown))).toThrow(/INTEROP-01/)
  })

  it.each([
    'Ordinary **bold**, *italic*, [[план]] and 😀 漢字.\n',
    '[a]{.red}[b]{.blue} [в]{.gray}\n',
    '[one two]{.red} [😀 漢字]{.blue}\n',
    '[е\u0301 😀\u00a0漢字]{.red} [\u2003wide\u2003]{.blue}\n',
    '[outer[inner]{.blue}tail]{.gray}\n',
    '`[ code ]{.red}`\n',
    '~~~md\n[ code ]{.red}\n~~~\n',
    '~~~md\n[ code ]{.red}\n~~~\n\n[safe]{.blue}\n',
    '---\nlabel: "[ data ]{.red}"\n---\n\nText.\n',
    '---\nlabel: "[ data ]{.red}"\n---\n\n[safe]{.blue}\n',
    '\\[ literal ]{.red} and [safe]{.blue}\n',
    '[link](https://example.invalid)\n',
    'A [a \r\n]{.red}B.\r\n',
  ])('allows safe documents over repeated materialize/reseed/reopen cycles: %s', markdown => {
    let current = markdown
    const text = visible(current)
    for (let cycle = 0; cycle < 5; cycle++) {
      validateSourceMarkdown(current)
      const checked = preflight(decodeBase64(createSeedOffer(current)))
      checked.destroy()
      current = roundTrip(current)
      expect(visible(current)).toBe(text)
    }
  })
})
