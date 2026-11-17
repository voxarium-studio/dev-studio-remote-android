/**
 * VS-72 codec parity corpus, adapted from the Studio suites below.
 * Only imports/fixture paths and suite isolation changed; assertions are preserved.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { maskColors, TEXT_COLORS } from '../src/md/color'
import { normalise, parseDocument, serializeDocument, type DocumentEnvelope } from '../src/md/document'
import { MAX_IMAGE_WIDTH } from '../src/md/image-width'
import { mdastToProseMirror, proseMirrorToMdast, type PmNode } from '../src/md/prosemirror'
import type { WikiLink } from '../src/md/wikilink'

/**
 * Vendored from Voxarium Studio: frontend/src/lib/md/round-trip.spec.ts
 * Source SHA256: fd6f538051eb4ec5dc614c7cf326bcf5c776e00712432adc20cc799d42749f49
 * VS-72: source preserved; see remote-android/protocol.md section 5.
 */
describe('Studio round-trip.spec.ts', () => {
/**
 * §8 of the block-editor spec, checked on a corpus rather than argued about.
 *
 * The fixtures next door are written in the dialect `conventions.ts` fixes, so the strong
 * assertion is available: reading and writing a canonical document changes nothing at all.
 * Everything the editor is allowed to reformat is exercised separately, from messy input,
 * where only §8.2 — stable from the second save on — has to hold.
 *
 * Point `VOXARIUM_STUDIO_DOC_CORPUS` at a directory of real `.md` files to run the stability and
 * no-loss checks over them too; `.scratch/demo-project/.voxarium_studio/docs` is the one to hand.
 */




const FIXTURES = fileURLToPath(new URL('./fixtures/', import.meta.url))

function fixture(name: string): string {
  return readFileSync(join(FIXTURES, name), 'utf8')
}

const FIXTURE_NAMES = readdirSync(FIXTURES).filter((name) => name.endsWith('.md'))

function markdownFilesIn(directory: string): string[] {
  const found: string[] = []
  for (const entry of readdirSync(directory)) {
    const path = join(directory, entry)
    if (statSync(path).isDirectory()) found.push(...markdownFilesIn(path))
    else if (/\.(md|markdown|txt)$/i.test(entry)) found.push(path)
  }
  return found
}

/** Every visible character, so "nothing was lost" can be asserted without caring where. */
function letters(source: string): string {
  return source.replace(/\s+/g, '')
}

describe('the corpus', () => {
  it('has fixtures to check', () => {
    expect(FIXTURE_NAMES.length).toBeGreaterThan(0)
  })

  it.each(FIXTURE_NAMES)('%s is already in the dialect, so a save changes nothing', (name) => {
    const source = fixture(name)
    expect(normalise(source)).toBe(source)
  })

  it.each(FIXTURE_NAMES)('%s is stable from the second save on (§8.2)', (name) => {
    const once = normalise(fixture(name))
    expect(normalise(once)).toBe(once)
  })

  it.each(FIXTURE_NAMES)('%s never leaks a masking sentinel to disk', (name) => {
    expect(normalise(fixture(name))).not.toMatch(/[\uE000-\uE003]/)
  })
})

describe('reformatting messy input', () => {
  // Each of these is written the way a person or an agent might, not the way the editor
  // writes. The first save is allowed to move them; the second is not (§8.2).
  const MESSY = {
    'setext headings': 'Title\n=====\n\nSection\n-------\n\nBody.\n',
    'star bullets': '* one\n* two\n* three\n',
    'plus bullets': '+ one\n+ two\n',
    'underscore emphasis': '_italic_ and __bold__ text.\n',
    'indented code': '    const x = 1\n    const y = 2\n',
    'padded table': '| Stat     | Value |\n| -------- | ----- |\n| Distance | 6 m   |\n',
    'tilde fence': '~~~js\nconst x = 1\n~~~\n',
    'star rule': '***\n',
    'lazy continuation': '> a quote\ncontinued lazily\n',
    'trailing spaces': 'A line with trailing spaces.   \n\nAnd another.\n',
    'many blank lines': '# Title\n\n\n\n\nBody after four blank lines.\n',
  }

  it.each(Object.entries(MESSY))('%s is stable after the first save', (_name, source) => {
    const once = normalise(source)
    expect(normalise(once)).toBe(once)
  })

  it.each(Object.entries(MESSY))('%s keeps every character', (_name, source) => {
    // Markers may change; the words may not disappear.
    const markers = /[*_~=#>|+\-`]/g
    expect(letters(normalise(source)).replace(markers, '')).toBe(
      letters(source).replace(markers, ''),
    )
  })
})

describe('the envelope (§7)', () => {
  it('keeps CRLF line endings', () => {
    const source = '# Title\r\n\r\nBody.\r\n'
    expect(normalise(source)).toBe(source)
  })

  it('keeps LF line endings', () => {
    const source = '# Title\n\nBody.\n'
    expect(normalise(source)).toBe(source)
  })

  it('keeps a missing final newline', () => {
    expect(normalise('# Title')).toBe('# Title')
  })

  it('keeps a present final newline', () => {
    expect(normalise('# Title\n')).toBe('# Title\n')
  })

  it('leaves an empty document empty', () => {
    expect(normalise('')).toBe('')
  })

  it('holds frontmatter byte for byte, comments and odd spacing included', () => {
    const frontmatter = '---\ntitle:   Held\n# a comment\nlist: [a, "b"]\n---\n'
    const source = `${frontmatter}\n# Body\n`
    const { envelope } = parseDocument(source)
    // The blank line after the closing fence belongs to the envelope too: it is not in the
    // tree, so anything that does not carry it here deletes it on the first save.
    expect(envelope.frontmatter).toBe(`${frontmatter}\n`)
    expect(normalise(source)).toBe(source)
  })

  it('carries a frontmatter-only document', () => {
    const source = '---\na: 1\n---\n'
    expect(parseDocument(source).envelope.frontmatter).toBe(source)
    expect(normalise(source)).toBe(source)
  })

  it('does not mistake a leading thematic break for frontmatter', () => {
    // No closing fence anywhere, so there is nothing that could be frontmatter.
    const { envelope } = parseDocument('---\n\n# Title\n')
    expect(envelope.frontmatter).toBe('')
  })

  it('does not reformat the YAML it carries', () => {
    const source = '---\na:    1\n---\n\ntext\n'
    expect(normalise(source)).toContain('a:    1')
  })
})

describe('wiki links (§5)', () => {
  const FORMS = [
    '[[test-plan]]',
    '[[qa/test-plan|the QA plan]]',
    '[[a document with spaces]]',
    '[[Mixed Case Name]]',
    '[[dash|]]',
  ]

  it.each(FORMS)('%s survives byte for byte', (link) => {
    expect(normalise(`Text ${link} more.\n`)).toBe(`Text ${link} more.\n`)
  })

  it.each(FORMS)('%s survives inside emphasis', (link) => {
    expect(normalise(`Text **${link}** more.\n`)).toBe(`Text **${link}** more.\n`)
  })

  it('parses target and label', () => {
    const { root } = parseDocument('See [[qa/test-plan|the QA plan]].\n')
    const paragraph = root.children[0]
    if (paragraph.type !== 'paragraph') throw new Error('expected a paragraph')
    const link = paragraph.children.find((child) => child.type === 'wikiLink') as WikiLink
    expect(link.target).toBe('qa/test-plan')
    expect(link.label).toBe('the QA plan')
    expect(link.value).toBe('qa/test-plan|the QA plan')
  })

  it('leaves the label null when there is none', () => {
    const { root } = parseDocument('See [[dash]].\n')
    const paragraph = root.children[0]
    if (paragraph.type !== 'paragraph') throw new Error('expected a paragraph')
    const link = paragraph.children.find((child) => child.type === 'wikiLink') as WikiLink
    expect(link.target).toBe('dash')
    expect(link.label).toBeNull()
  })

  it('is not a link inside a code span', () => {
    const source = 'Write `[[dash]]` to link.\n'
    const { root } = parseDocument(source)
    const paragraph = root.children[0]
    if (paragraph.type !== 'paragraph') throw new Error('expected a paragraph')
    expect(paragraph.children.some((child) => child.type === 'wikiLink')).toBe(false)
    expect(normalise(source)).toBe(source)
  })

  it('is not a link inside a fenced code block', () => {
    const source = '```\n[[dash]]\n```\n'
    const { root } = parseDocument(source)
    const block = root.children[0]
    if (block.type !== 'code') throw new Error('expected a code block')
    expect(block.value).toBe('[[dash]]')
    expect(normalise(source)).toBe(source)
  })

  it('survives a link definition that collides with the target', () => {
    // `[[dash]]` next to `[dash]: …` is what would make a text-level pass misread the
    // brackets as a shortcut reference wrapped in literal ones.
    const source = 'See [[dash]] and [dash].\n\n[dash]: https://example.invalid/d\n'
    expect(normalise(source)).toBe(source)
    const { root } = parseDocument(source)
    const paragraph = root.children[0]
    if (paragraph.type !== 'paragraph') throw new Error('expected a paragraph')
    expect(paragraph.children.some((child) => child.type === 'wikiLink')).toBe(true)
  })

  it('does not rewrite the target to its resolved form', () => {
    // The whole point of §5: resolution is a view concern, the file keeps what was typed.
    expect(normalise('[[test-plan]]\n')).toBe('[[test-plan]]\n')
    expect(normalise('[[Test Plan]]\n')).toBe('[[Test Plan]]\n')
  })
})

describe('the fixed dialect (§8.1)', () => {
  it('does not wrap long paragraphs', () => {
    const sentence = `${'word '.repeat(90).trim()}.`
    const output = normalise(`${sentence}\n`)
    expect(output.split('\n').filter((line) => line.length > 0)).toHaveLength(1)
  })

  it('writes bullets as -', () => {
    expect(normalise('* one\n* two\n')).toBe('- one\n- two\n')
  })

  it('writes strong as ** and emphasis as *', () => {
    expect(normalise('__bold__ and _italic_.\n')).toBe('**bold** and *italic*.\n')
  })

  it('fences code rather than indenting it, and keeps the language', () => {
    expect(normalise('    const x = 1\n')).toBe('```\nconst x = 1\n```\n')
    expect(normalise('~~~ts\nconst x = 1\n~~~\n')).toBe('```ts\nconst x = 1\n```\n')
  })

  it('writes thematic breaks as ---', () => {
    expect(normalise('***\n')).toBe('---\n')
  })

  it('writes headings as ATX', () => {
    expect(normalise('Title\n=====\n')).toBe('# Title\n')
  })

  it('writes tables one space per cell, without padding to the widest', () => {
    const source = '| Stat     | Value |\n| -------- | ----- |\n| Distance | 6 m   |\n'
    expect(normalise(source)).toBe('| Stat | Value |\n| --- | --- |\n| Distance | 6 m |\n')
  })

  it('keeps table column alignment', () => {
    const source = '| a | b | c |\n| :--- | :---: | ---: |\n| 1 | 2 | 3 |\n'
    expect(normalise(source)).toBe(source)
  })

  it('writes a soft break as a backslash (§14.1)', () => {
    const source = 'first\\\nsecond\n'
    expect(normalise(source)).toBe(source)
    // Two trailing spaces mean the same thing and are normalised to the visible form.
    expect(normalise('first  \nsecond\n')).toBe(source)
  })
})

describe('constructs outside §3.1 (§8.3)', () => {
  const KEEP = {
    image: '![Dash frames](../assets/dash.png "Frame sheet")\n',
    'inline html': 'text with <kbd>Ctrl</kbd> in it\n',
    'html block': '<details>\n  <summary>By hand</summary>\n</details>\n',
    'link definition': 'Use [the spec][spec].\n\n[spec]: https://example.invalid/s "The spec"\n',
    footnote: 'A claim[^n]\n\n[^n]: The evidence.\n',
    autolink: '<https://example.invalid/raw>\n',
    'escaped markers': '\\*not emphasis\\* and \\[not a link]\n',
  }

  it.each(Object.entries(KEEP))('%s comes back unchanged', (_name, source) => {
    expect(normalise(source)).toBe(source)
  })

  it('drops only the redundant half of an escaped bracket pair', () => {
    // `\]` carries nothing once `\[` is escaped — no link can start — so the serializer
    // writes the shorter form. The escape that does the work is kept, which is what §8.3
    // is actually about; the second save then changes nothing.
    const once = normalise('\\[not a link\\]\n')
    expect(once).toBe('\\[not a link]\n')
    expect(normalise(once)).toBe(once)
  })
})

describe('directives (§3.3)', () => {
  it('keeps a callout with a heading', () => {
    const source = ':::warning[Blocked]\nBody.\n:::\n'
    expect(normalise(source)).toBe(source)
  })

  it('keeps a callout without a heading', () => {
    const source = ':::note\nBody.\n:::\n'
    expect(normalise(source)).toBe(source)
  })

  it('keeps a toggle with its open attribute', () => {
    const source = ':::toggle[Details]{open}\nBody.\n:::\n'
    expect(normalise(source)).toBe(source)
  })

  it('exposes the name, label and attributes to the editor', () => {
    const { root } = parseDocument(':::toggle[Details]{open}\nBody.\n:::\n')
    const directive = root.children[0]
    if (directive.type !== 'containerDirective') throw new Error('expected a directive')
    expect(directive.name).toBe('toggle')
    expect(directive.attributes?.open).toBe('')
    const label = directive.children[0]
    if (label.type !== 'paragraph') throw new Error('expected a label paragraph')
    expect(label.data?.directiveLabel).toBe(true)
  })
})

describe('an external corpus', () => {
  const directory = process.env.VOXARIUM_STUDIO_DOC_CORPUS
  const files = directory ? markdownFilesIn(directory) : []

  it('was pointed at some documents, or was not asked for', () => {
    expect(directory === undefined || files.length > 0).toBe(true)
  })

  if (files.length) {
    it.each(files)('%s is stable from the second save on', (path) => {
      const once = normalise(readFileSync(path, 'utf8'))
      expect(normalise(once)).toBe(once)
    })

    it.each(files)('%s keeps every wiki link', (path) => {
      const source = readFileSync(path, 'utf8')
      const before = source.match(/\[\[[^[\]]*\]\]/g) ?? []
      const after = normalise(source).match(/\[\[[^[\]]*\]\]/g) ?? []
      expect(after).toEqual(before)
    })
  }
})

describe('serializeDocument', () => {
  it('round-trips a parsed document through its own envelope', () => {
    const source = '---\r\na: 1\r\n---\r\n\r\n# Title\r\n'
    const { root, envelope } = parseDocument(source)
    expect(envelope.eol).toBe('\r\n')
    expect(serializeDocument(root, envelope)).toBe(source)
  })

  it('re-serialises an edited tree into the original envelope', () => {
    const { root, envelope } = parseDocument('---\na: 1\n---\n\n# Title\r\n\r\nBody.')
    root.children.push({ type: 'thematicBreak' })
    // Frontmatter, CRLF and the missing final newline all survive the edit.
    expect(serializeDocument(root, envelope)).toBe('---\r\na: 1\r\n---\r\n\r\n# Title\r\n\r\nBody.\r\n\r\n---')
  })
})

})

/**
 * Vendored from Voxarium Studio: frontend/src/lib/md/prosemirror.spec.ts
 * Source SHA256: 93f18aa0b22198c81daf1bd322e3b391a768a2297242c7b585f7933e4a6ae39e
 * VS-72: source preserved; see remote-android/protocol.md section 5.
 */
describe('Studio prosemirror.spec.ts', () => {
/**
 * The bridge must not cost anything §8 already bought.
 *
 * Every check here is the round trip *through* ProseMirror compared against the round trip
 * without it: if a document survives `parse → serialize` but not `parse → pm → mdast →
 * serialize`, the loss is in the mapping, and this is where it shows up rather than in a
 * browser three PRs later.
 */




const FIXTURES = fileURLToPath(new URL('./fixtures/', import.meta.url))
const FIXTURE_NAMES = readdirSync(FIXTURES).filter((name) => name.endsWith('.md'))

/** What the editor would save after opening a document and touching nothing. */
function throughEditor(source: string): string {
  const { root, envelope } = parseDocument(source)
  const document = mdastToProseMirror(root)
  return serializeDocument(proseMirrorToMdast(document), envelope)
}

function docOf(source: string): PmNode {
  return mdastToProseMirror(parseDocument(source).root)
}

function firstBlock(source: string): PmNode {
  return docOf(source).content?.[0] as PmNode
}

describe('the corpus survives the bridge', () => {
  it.each(FIXTURE_NAMES)('%s is unchanged by a trip through ProseMirror', (name) => {
    const source = readFileSync(join(FIXTURES, name), 'utf8')
    expect(throughEditor(source)).toBe(normalise(source))
  })

  it.each(FIXTURE_NAMES)('%s is stable when the trip is taken twice', (name) => {
    const once = throughEditor(readFileSync(join(FIXTURES, name), 'utf8'))
    expect(throughEditor(once)).toBe(once)
  })
})

describe('blocks', () => {
  const BLOCKS: Record<string, [markdown: string, type: string]> = {
    paragraph: ['Text.\n', 'paragraph'],
    heading: ['## Rules\n', 'heading'],
    'bulleted list': ['- one\n', 'bulletList'],
    'numbered list': ['1. one\n', 'orderedList'],
    'task list': ['- [ ] one\n', 'taskList'],
    quote: ['> quoted\n', 'blockquote'],
    code: ['```ts\nconst x = 1\n```\n', 'codeBlock'],
    table: ['| a |\n| --- |\n| 1 |\n', 'table'],
    divider: ['---\n', 'horizontalRule'],
    callout: [':::note[Title]\nBody.\n:::\n', 'callout'],
    toggle: [':::toggle[Title]{open}\nBody.\n:::\n', 'toggle'],
    'html block': ['<div>raw</div>\n', 'unsupportedBlock'],
    'footnote definition': ['[^a]: note\n', 'unsupportedBlock'],
    'link definition': ['[a]: https://example.invalid/a\n', 'unsupportedBlock'],
    'unknown directive': [':::mystery\nBody.\n:::\n', 'unsupportedBlock'],
  }

  it.each(Object.entries(BLOCKS))('%s becomes the right node', (_name, [markdown, type]) => {
    expect(firstBlock(markdown).type).toBe(type)
  })

  it.each(Object.entries(BLOCKS))('%s comes back as it was', (_name, [markdown]) => {
    expect(throughEditor(markdown)).toBe(markdown)
  })

  it('keeps the heading level', () => {
    expect(firstBlock('#### Deep\n').attrs?.level).toBe(4)
    expect(throughEditor('#### Deep\n')).toBe('#### Deep\n')
  })

  it('keeps the code language', () => {
    expect(firstBlock('```python\nx = 1\n```\n').attrs?.language).toBe('python')
  })

  it('keeps the start of a numbered list', () => {
    expect(firstBlock('5. five\n6. six\n').attrs?.start).toBe(5)
    expect(throughEditor('5. five\n6. six\n')).toBe('5. five\n6. six\n')
  })

  it('keeps a checked task and an unchecked one apart', () => {
    const items = firstBlock('- [x] done\n- [ ] todo\n').content ?? []
    expect(items.map((item) => item.attrs?.checked)).toEqual([true, false])
    expect(throughEditor('- [x] done\n- [ ] todo\n')).toBe('- [x] done\n- [ ] todo\n')
  })

  it('keeps a plain item inside a task list plain', () => {
    // GFM allows the mix, and turning the third item into an unticked box would be an edit
    // the user never made.
    const source = '- [x] done\n- [ ] todo\n- just an item\n'
    const items = firstBlock(source).content ?? []
    expect(items.map((item) => item.attrs?.checked)).toEqual([true, false, null])
    expect(throughEditor(source)).toBe(source)
  })

  it('keeps table alignment on the table', () => {
    const source = '| a | b | c |\n| :--- | :---: | ---: |\n| 1 | 2 | 3 |\n'
    expect(firstBlock(source).attrs?.align).toEqual(['left', 'center', 'right'])
    expect(throughEditor(source)).toBe(source)
  })

  it('marks the first table row as headers', () => {
    const rows = firstBlock('| a |\n| --- |\n| 1 |\n').content ?? []
    expect(rows[0].content?.[0].type).toBe('tableHeader')
    expect(rows[1].content?.[0].type).toBe('tableCell')
  })

  it('keeps the callout kind', () => {
    expect(firstBlock(':::danger[Stop]\nBody.\n:::\n').attrs?.kind).toBe('danger')
  })

  it('keeps whether a toggle is open', () => {
    expect(firstBlock(':::toggle[A]{open}\nB.\n:::\n').attrs?.open).toBe(true)
    expect(firstBlock(':::toggle[A]\nB.\n:::\n').attrs?.open).toBe(false)
  })

  it('gives a directive its title as an editable node', () => {
    const callout = firstBlock(':::note[The **title**]\nBody.\n:::\n')
    const title = callout.content?.[0]
    expect(title?.type).toBe('directiveTitle')
    // The label carries inline markup, so it cannot be a plain string attribute.
    expect(title?.content?.[1].marks?.[0].type).toBe('bold')
  })

  it('gives an untitled directive an empty title node, and writes no label back', () => {
    // Always first, titled or not — a collapsed toggle hides "everything after the title",
    // which needs the title to be somewhere fixed.
    const title = firstBlock(':::note\nBody.\n:::\n').content?.[0]
    expect(title?.type).toBe('directiveTitle')
    expect(title?.content).toBeUndefined()
    expect(throughEditor(':::note\nBody.\n:::\n')).toBe(':::note\nBody.\n:::\n')
  })

  it('nests directives', () => {
    const source = '::::note[Outer]\n:::tip[Inner]\nBody.\n:::\n::::\n'
    const outer = firstBlock(source)
    expect(outer.content?.[1].type).toBe('callout')
    expect(throughEditor(source)).toBe(source)
  })

  it('drops an empty trailing paragraph rather than writing a blank line', () => {
    const document: PmNode = {
      type: 'doc',
      content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Body.' }] }, { type: 'paragraph' }],
    }
    const { envelope } = parseDocument('Body.\n')
    expect(serializeDocument(proseMirrorToMdast(document), envelope)).toBe('Body.\n')
  })

  it('turns an empty document into one empty paragraph', () => {
    expect(docOf('')).toEqual({ type: 'doc', content: [{ type: 'paragraph' }] })
  })
})

describe('inline markup', () => {
  const INLINE = [
    '**bold**',
    '*italic*',
    '~~struck~~',
    '`code`',
    '[a link](https://example.invalid/a)',
    '[[wiki-link]]',
    '[[wiki|labelled]]',
    '**bold with *italic* inside**',
    '*[[a link inside emphasis]]*',
    '[**bold** inside a link](https://example.invalid/a)',
    '`code` next to **bold**',
    '![an image](https://example.invalid/i.png)',
    'text with <kbd>html</kbd> in it',
    'a footnote[^a]',
  ]

  // Against `normalise`, not against the source: the bridge has to cost nothing *beyond*
  // what §8 already allows the first save to do. `[^a]` with no definition is prose, and
  // prose containing a bracket gets escaped — by the serializer, not by this mapping.
  it.each(INLINE)('%s costs nothing extra on the way through', (markdown) => {
    const source = `Before ${markdown} after.\n`
    expect(throughEditor(source)).toBe(normalise(source))
  })

  it('carries a real footnote reference and its definition', () => {
    const source = 'A claim[^a]\n\n[^a]: The evidence.\n'
    expect(throughEditor(source)).toBe(source)
  })

  it('flattens nesting into marks and rebuilds it', () => {
    const paragraph = firstBlock('***both***\n')
    const text = paragraph.content?.[0]
    expect(text?.marks?.map((mark) => mark.type).sort()).toEqual(['bold', 'italic'])
  })

  it('keeps a link target and title on the mark', () => {
    const paragraph = firstBlock('[a](https://example.invalid/a "T")\n')
    const mark = paragraph.content?.[0].marks?.find((entry) => entry.type === 'link')
    expect(mark?.attrs).toEqual({ href: 'https://example.invalid/a', title: 'T' })
  })

  it('carries a wiki link whole, with its raw text', () => {
    const paragraph = firstBlock('See [[qa/plan|the plan]].\n')
    const link = paragraph.content?.find((child) => child.type === 'wikiLink')
    expect(link?.attrs).toEqual({ value: 'qa/plan|the plan', target: 'qa/plan', label: 'the plan' })
  })

  it('does not merge two adjacent links into one', () => {
    const source = '[a](https://example.invalid/a)[b](https://example.invalid/b)\n'
    expect(throughEditor(source)).toBe(source)
  })

  it('keeps a soft break', () => {
    expect(throughEditor('first\\\nsecond\n')).toBe('first\\\nsecond\n')
  })

  it('drops whitespace left hanging off the end of a block', () => {
    // A space at the end of a paragraph means nothing in Markdown, and writing it out means
    // writing `&#x20;` — an entity in the file, for a space somebody left while thinking.
    const document: PmNode = {
      type: 'doc',
      content: [
        { type: 'paragraph', content: [{ type: 'text', text: 'text with a space ' }] },
        { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: 'heading  ' }] },
      ],
    }
    const { envelope } = parseDocument('')
    expect(serializeDocument(proseMirrorToMdast(document), { ...envelope, finalNewline: true })).toBe(
      'text with a space\n\n## heading\n',
    )
  })

  it('leaves a space that is not at the end alone', () => {
    expect(throughEditor('one **two** three\n')).toBe('one **two** three\n')
  })
})

describe('the §8.3 rule', () => {
  it('carries an unmodelled block through as its own mdast node', () => {
    const block = firstBlock('<figure>\n  <img src="a.png">\n</figure>\n')
    expect(block.type).toBe('unsupportedBlock')
    expect((block.attrs?.mdast as { type: string }).type).toBe('html')
  })

  it('never shares a node with the tree it came from', () => {
    // The editor may hand these attrs around; mutating one must not reach back into the
    // parsed document.
    const { root } = parseDocument('<div>raw</div>\n')
    const block = mdastToProseMirror(root).content?.[0]
    expect(block?.attrs?.mdast).not.toBe(root.children[0])
    expect(block?.attrs?.mdast).toEqual(root.children[0])
  })
})

})

/**
 * Vendored from Voxarium Studio: frontend/src/lib/md/color.spec.ts
 * Source SHA256: 6ccbc6b4808937f4081ce7dee194d6267cf52cc2c6a94242f30875ff22b16e64
 * VS-72: source preserved; see remote-android/protocol.md section 5.
 */
describe('Studio color.spec.ts', () => {
/**
 * `[text]{.green}` as a property of the file (block-editor spec §3.2, §8).
 *
 * Two questions, and the second is the one that can lose someone's work: does the syntax
 * mean what it says, and does everything that merely *looks* like it come back untouched.
 * The negative cases below outnumber the positive ones on purpose.
 */



/** The inline nodes of the document's first paragraph. */
function inline(source: string) {
  const first = parseDocument(source).root.children[0]
  return first?.type === 'paragraph' ? first.children : []
}

function firstSpan(source: string) {
  return inline(source).find((node) => node.type === 'colorSpan')
}

/** What the editor would save after opening the document and touching nothing. */
function roundTrip(source: string): string {
  const { root, envelope } = parseDocument(source)
  return serializeDocument(proseMirrorToMdast(mdastToProseMirror(root)), envelope)
}

/** The private-use blocks both masks draw their sentinels from. */
const SENTINEL = /[\uE000-\uE01F]/

describe('a span is recognised', () => {
  it('becomes a colour span with the word the file used', () => {
    const span = firstSpan('Build [ok]{.green}.\n')
    expect(span).toMatchObject({ type: 'colorSpan', color: 'green' })
  })

  it.each(TEXT_COLORS)('accepts %s', (color) => {
    expect(firstSpan(`A [word]{.${color}} here.\n`)).toMatchObject({ color })
  })

  it('keeps `grey` spelled as it was found', () => {
    expect(normalise('A [word]{.grey} here.\n')).toBe('A [word]{.grey} here.\n')
    expect(normalise('A [word]{.gray} here.\n')).toBe('A [word]{.gray} here.\n')
  })

  it('parses the markup inside it', () => {
    const span = firstSpan('[**bold** and `code`]{.red}\n')
    expect(span?.children.map((child) => child.type)).toEqual(['strong', 'text', 'inlineCode'])
  })

  it('holds a wiki link', () => {
    const span = firstSpan('[see [[test-plan]] first]{.orange}\n')
    expect(span?.children.some((child) => child.type === 'wikiLink')).toBe(true)
  })

  it('holds a link, brackets and all', () => {
    const span = firstSpan('[read [the plan](plan.md) now]{.blue}\n')
    expect(span?.children.some((child) => child.type === 'link')).toBe(true)
  })

  it('nests', () => {
    const outer = firstSpan('[outer [inner]{.red} tail]{.gray}\n')
    expect(outer).toMatchObject({ color: 'gray' })
    expect(outer?.children.find((child) => child.type === 'colorSpan')).toMatchObject({
      color: 'red',
    })
  })

  it('is not stolen by a link definition of the same name', () => {
    // The whole reason for masking before the parser runs: without it `[ok]` is a shortcut
    // link reference here and the `{.green}` is left dangling as prose.
    const source = 'A [ok]{.green} word.\n\n[ok]: https://example.invalid/a\n'
    expect(firstSpan(source)).toMatchObject({ color: 'green' })
  })
})

describe('what only looks like a span', () => {
  const PROSE: Record<string, string> = {
    'an unknown colour': 'A \\[word]{.brand} here.\n',
    'an escaped bracket': 'A \\[word]{.green} here.\n',
    'a gap before the attribute': 'A \\[word] {.green} here.\n',
    'an empty span': 'A \\[]{.green} here.\n',
    'an attribute on its own': 'A {.green} here.\n',
    'an attribute with more in it': 'A \\[word]{.green #id} here.\n',
    'a bracket with no attribute': 'A \\[word] here.\n',
    'an unclosed bracket': 'A \\[word{.green} here.\n',
    'a link with an attribute after it': 'A [word](plan.md){.green} here.\n',
  }

  it.each(Object.entries(PROSE))('%s stays prose', (_name, source) => {
    expect(firstSpan(source)).toBeUndefined()
    expect(normalise(source)).toBe(source)
  })

  it('is not a span across two lines', () => {
    // A paragraph is written as one line (§8.1), so a bracket that reaches the end of its
    // line has not found its partner — and refusing to look further is what keeps an
    // unmatched `[` from scanning a whole document.
    expect(maskColors('[start\nend]{.green}').spans).toHaveLength(0)
  })

  it('does not read a code span', () => {
    expect(maskColors('`[ok]{.green}`').spans).toHaveLength(0)
  })

  it('closes after a code span rather than inside one', () => {
    // CommonMark resolves backticks before brackets, so the `]` in the code span is a
    // character; a scanner that stopped there would end the colour mid-sentence.
    const span = firstSpan('[a `]` b]{.green}\n')
    expect(span?.color).toBe('green')
    expect(normalise('[a `]` b]{.green}\n')).toBe('[a `]` b]{.green}\n')
  })

  it('leaves a fenced code block alone', () => {
    const source = '```md\n[not a span]{.green}\n```\n'
    expect(normalise(source)).toBe(source)
    expect(parseDocument(source).root.children[0]).toMatchObject({
      type: 'code',
      value: '[not a span]{.green}',
    })
  })

  it('leaves the text of an inline code span alone', () => {
    const source = 'Write `[ok]{.green}` for that.\n'
    expect(normalise(source)).toBe(source)
    expect(inline(source).some((node) => node.type === 'colorSpan')).toBe(false)
  })
})

describe('the file survives', () => {
  const CASES: Record<string, string> = {
    plain: 'Build [ok]{.green}.\n',
    'with markup': '[**bold** and *italic*]{.red}\n',
    'with a bracket inside': '[a \\[b\\] c]{.purple}\n',
    'with a wiki link': '[see [[test-plan]]]{.orange}\n',
    'with a link': '[read [the plan](plan.md)]{.blue}\n',
    nested: '[outer [inner]{.red} tail]{.gray}\n',
    'in a heading': '## A [warning]{.yellow}\n',
    'in a list': '- [done]{.green}\n',
    'in a quote': '> [careful]{.red}\n',
    'in a table': '| a |\n| --- |\n| [ok]{.green} |\n',
    'in a callout': ':::warning[Title]\n[on hold]{.red}\n:::\n',
    'twice on one line': '[a]{.red} and [b]{.blue}\n',
    'the whole paragraph': '[everything]{.cyan}\n',
  }

  it.each(Object.entries(CASES))('%s is written back unchanged', (_name, source) => {
    expect(normalise(source)).toBe(source)
  })

  it.each(Object.entries(CASES))('%s survives the trip through ProseMirror', (_name, source) => {
    expect(roundTrip(source)).toBe(source)
  })

  it.each(Object.entries(CASES))('%s leaks no masking sentinel', (_name, source) => {
    expect(SENTINEL.test(normalise(source))).toBe(false)
  })

  it('is stable from the second save on', () => {
    const messy = 'A [ok]{.green} and [[wiki]] and *em*.\n'
    const once = normalise(messy)
    expect(normalise(once)).toBe(once)
  })
})

describe('the ProseMirror mark', () => {
  function marksOf(source: string): PmNode[] {
    const document = mdastToProseMirror(parseDocument(source).root)
    return document.content?.[0]?.content ?? []
  }

  it('is carried on the text, not around it', () => {
    expect(marksOf('[ok]{.green}\n')[0]).toMatchObject({
      type: 'text',
      text: 'ok',
      marks: [{ type: 'textColor', attrs: { color: 'green' } }],
    })
  })

  it('combines with the other marks', () => {
    const [node] = marksOf('[**ok**]{.green}\n')
    expect(node.marks?.map((mark) => mark.type)).toEqual(['textColor', 'bold'])
  })

  it('reaches a wiki link, which is an atom', () => {
    const nodes = marksOf('[[[test-plan]]]{.green}\n')
    const link = nodes.find((node) => node.type === 'wikiLink')
    expect(link?.marks).toEqual([{ type: 'textColor', attrs: { color: 'green' } }])
  })

  it('does not merge two colours into one span', () => {
    expect(roundTrip('[a]{.red}[b]{.blue}\n')).toBe('[a]{.red}[b]{.blue}\n')
  })

  it('is dropped rather than written as an empty class', () => {
    // Nothing in the UI can produce this; the serializer still has to answer for it,
    // because `[x]{.}` is a span no parser would read back.
    const document: PmNode = {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [{ type: 'text', text: 'x', marks: [{ type: 'textColor', attrs: {} }] }],
        },
      ],
    }
    const envelope = { frontmatter: '', eol: '\n' as const, finalNewline: true }
    expect(serializeDocument(proseMirrorToMdast(document), envelope)).toBe('x\n')
  })
})

})

/**
 * Vendored from Voxarium Studio: frontend/src/lib/md/image-width.spec.ts
 * Source SHA256: 2037fc09810c93d6be2ba61beaa65ee8977b4ca1662b0814bd1ea6fd28a14769
 * VS-72: source preserved; see remote-android/protocol.md section 5.
 */
describe('Studio image-width.spec.ts', () => {
/**
 * `![shot](shot.png){width=300}` as a property of the file (block-editor spec §3.4, §8).
 *
 * The negative cases outnumber the positive ones on purpose, as they do for the colour spans:
 * a width that is not read is a picture at its own size, but braces that stop coming back
 * as they were written are an edit nobody made.
 */



const ENVELOPE: DocumentEnvelope = { frontmatter: '', eol: '\n', finalNewline: true }

/** The inline nodes of the document's first paragraph. */
function inline(source: string) {
  const first = parseDocument(source).root.children[0]
  return first?.type === 'paragraph' ? first.children : []
}

/** Whether any node in the parsed document ended up with a width. */
function hasWidth(source: string): boolean {
  return JSON.stringify(parseDocument(source).root).includes('"width"')
}

/** What the editor would save after opening the document and touching nothing. */
function roundTrip(source: string): string {
  const { root, envelope } = parseDocument(source)
  return serializeDocument(proseMirrorToMdast(mdastToProseMirror(root)), envelope)
}

/** A one-picture document as the editor holds it, with the width the slider left. */
function picture(width: unknown, after?: string): PmNode {
  const content: PmNode[] = [{ type: 'image', attrs: { src: 'a.png', alt: 'a', title: null, width } }]
  if (after) content.push({ type: 'text', text: after })
  return { type: 'doc', content: [{ type: 'paragraph', content }] }
}

describe('a width is read', () => {
  it('moves `{width=…}` into the picture it follows', () => {
    const nodes = inline('![shot](shot.png){width=300}\n')
    expect(nodes).toHaveLength(1)
    expect(nodes[0]).toMatchObject({ type: 'image', url: 'shot.png', data: { width: 300 } })
  })

  it('leaves the text after it where it was', () => {
    const nodes = inline('Look: ![a](a.png){width=120} and more.\n')
    expect(nodes.map((node) => node.type)).toEqual(['text', 'image', 'text'])
    expect(nodes[2]).toMatchObject({ type: 'text', value: ' and more.' })
  })

  it.each([
    ['alone on its line', '![shot](shot.png){width=300}\n'],
    ['with a title', '![shot](shot.png "Frame sheet"){width=640}\n'],
    ['mid-sentence', 'Before ![a](a.png){width=48} after.\n'],
    ['two in a row', '![a](a.png){width=1}![b](b.png){width=10000}\n'],
    ['inside a link', '[![a](a.png){width=90}](big.png)\n'],
    ['inside emphasis', '**![a](a.png){width=90}**\n'],
    ['inside a colour span', '[see ![a](a.png){width=90}]{.green}\n'],
    ['inside a list item', '- ![a](a.png){width=200}\n'],
  ])('and written back byte for byte: %s', (_case, source) => {
    expect(hasWidth(source)).toBe(true)
    expect(normalise(source)).toBe(source)
    expect(roundTrip(source)).toBe(source)
  })
})

describe('everything that only looks like one stays text', () => {
  it.each([
    ['a space before it', '![a](a.png) {width=300}\n'],
    ['an escaped brace', '![a](a.png)\\{width=300}\n'],
    ['a unit', '![a](a.png){width=300px}\n'],
    ['a percentage', '![a](a.png){width=50%}\n'],
    ['zero', '![a](a.png){width=0}\n'],
    ['a leading zero', '![a](a.png){width=007}\n'],
    ['past the ceiling', `![a](a.png){width=${MAX_IMAGE_WIDTH + 1}}\n`],
    ['a class rather than a key', '![a](a.png){.widght=300}\n'],
    ['another key', '![a](a.png){height=200}\n'],
    ['two keys', '![a](a.png){width=300 height=200}\n'],
    ['no picture before it', 'Text{width=300}\n'],
    ['a link rather than a picture', '[a](a.png){width=300}\n'],
    ['inline code', '`![a](a.png){width=300}`\n'],
  ])('%s', (_case, source) => {
    expect(hasWidth(source)).toBe(false)
    expect(normalise(source)).toBe(source)
  })
})

describe('the editor', () => {
  it('hands the width to the node and gets it back', () => {
    const source = '![shot](shot.png){width=300}\n'
    const node = mdastToProseMirror(parseDocument(source).root).content?.[0]?.content?.[0]
    expect(node).toMatchObject({ type: 'image', attrs: { src: 'shot.png', width: 300 } })
  })

  it('gives a picture without one no width attribute at all', () => {
    const node = mdastToProseMirror(parseDocument('![shot](shot.png)\n').root).content?.[0]?.content?.[0]
    expect(node?.attrs).toEqual({ src: 'shot.png', alt: 'shot', title: null })
  })

  it('writes the width the slider set, and nothing once it is taken off', () => {
    expect(serializeDocument(proseMirrorToMdast(picture(240)), ENVELOPE)).toBe('![a](a.png){width=240}\n')
    expect(serializeDocument(proseMirrorToMdast(picture(null)), ENVELOPE)).toBe('![a](a.png)\n')
  })

  it.each([0, -5, 12.5, MAX_IMAGE_WIDTH + 1, '300'])('never writes a width it would not read: %s', (width) => {
    expect(serializeDocument(proseMirrorToMdast(picture(width)), ENVELOPE)).toBe('![a](a.png)\n')
  })

  it('escapes typed text that would come back as the width of the picture before it', () => {
    const markdown = serializeDocument(proseMirrorToMdast(picture(null, '{width=5} is the plan')), ENVELOPE)
    expect(markdown).toBe('![a](a.png)\\{width=5} is the plan\n')
    expect(hasWidth(markdown)).toBe(false)
    expect(normalise(markdown)).toBe(markdown)
  })

  it('does not escape braces the parser would not read anyway', () => {
    for (const text of ['{width=0}', '{width=50%}', '{height=5}']) {
      expect(serializeDocument(proseMirrorToMdast(picture(null, text)), ENVELOPE)).toBe(`![a](a.png)${text}\n`)
    }
  })

  it('writes a picture that has a width and then text that looks like one, both intact', () => {
    const markdown = serializeDocument(proseMirrorToMdast(picture(64, '{width=5}')), ENVELOPE)
    expect(markdown).toBe('![a](a.png){width=64}{width=5}\n')
    expect(inline(markdown)).toMatchObject([
      { type: 'image', data: { width: 64 } },
      { type: 'text', value: '{width=5}' },
    ])
    expect(normalise(markdown)).toBe(markdown)
  })
})

})

/**
 * Vendored from Voxarium Studio: frontend/src/lib/md/marks.spec.ts
 * Source SHA256: bd31dcfd07081607cfcadbe11675127076533f833394ae2631e44c43e587e0d2
 * VS-72: source preserved; see remote-android/protocol.md section 5.
 */
describe('Studio marks.spec.ts', () => {
/**
 * Nested emphasis through the editor (block-editor spec §8.2).
 *
 * The editor saves by way of ProseMirror's flat marks, so the nesting is rebuilt on the way out,
 * and a wrong guess is not a reformat — `***a** b*` came back as `***a****&#x20;b*`, and a line
 * already damaged that way gained two literal asterisks on every save after. The plain
 * parse-and-print path never had the problem, which is why it is the yardstick here: whatever
 * the editor writes has to be what `normalise` writes.
 */



/** What the editor writes after opening the document and touching nothing. */
function save(source: string): string {
  const { root, envelope } = parseDocument(source)
  return serializeDocument(proseMirrorToMdast(mdastToProseMirror(root)), envelope)
}

const tree = (source: string): string =>
  JSON.stringify(parseDocument(source).root, (key, value) => (key === 'position' ? undefined : value))

describe('emphasis nested in emphasis survives the editor', () => {
  it.each([
    '***Дата:** 14 апреля 2148 года*\n',
    '***a** b*\n',
    '*a **b***\n',
    '**a *b***\n',
    '***a* b**\n',
    '***a***\n',
    '~~**a** b~~\n',
    'Say ***this** loudly* and **that *softly***.\n',
  ])('%s', (source) => {
    expect(save(source)).toBe(normalise(source))
    expect(tree(save(source))).toBe(tree(source))
    expect(save(save(source))).toBe(save(source))
  })

  it('stops a line an earlier save damaged from growing', () => {
    const damaged = '***Дата:****\\*\\* 14 апреля 2148 года*\n'
    const once = save(damaged)
    expect(tree(once)).toBe(tree(damaged))
    expect(save(once)).toBe(once)
  })
})

})

