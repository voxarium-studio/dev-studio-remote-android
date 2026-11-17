/** Fail closed BEFORE ySyncPlugin can repair an incompatible room (protocol §5). */
import * as Y from 'yjs'
import type { Schema, Node as PmDocument } from '@tiptap/pm/model'
import { yXmlFragmentToProsemirrorJSON } from '@tiptap/y-tiptap'
import { roomSchema } from './collab/seed'
import { COLLAB_FIELD, MARK_CARRIERS, NULL_CARRIERS, carry } from './collab/carried'
import { CALLOUT_KINDS, mdastToProseMirror, proseMirrorToMdast, type PmNode, type PmMark } from './md/prosemirror'
import { parseDocument, serializeDocument } from './md/document'
import { isTextColor } from './md/color'
import { assertSafeColorSource, COLOR_SAFETY_FAILURE } from './color-safety'

export class SchemaBlockedError extends Error {
  constructor(message: string, readonly rawState: Uint8Array, readonly inputState?: Uint8Array, readonly inputUpdate?: Uint8Array) {
    super(message)
    this.name = 'SchemaBlockedError'
  }
}

const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
function fail(message: string): never { throw new Error(message) }
const stable = (value: unknown): string => JSON.stringify(value, (_key, v: unknown) =>
  object(v) ? Object.fromEntries(Object.entries(v).sort(([a], [b]) => a.localeCompare(b))) : v)

function attributes(attrs: unknown, spec: Record<string, unknown> | undefined, where: string): void {
  if (attrs === undefined) return
  if (!object(attrs)) fail(`${where}: invalid attributes`)
  for (const key of Object.keys(attrs)) {
    if (!spec || !Object.hasOwn(spec, key)) fail(`${where}: unknown attribute ${key}`)
  }
}

function mark(mark: PmMark, schema: Schema): void {
  if (!object(mark) || typeof mark.type !== 'string' || !Object.hasOwn(schema.marks, mark.type)) {
    fail('Unknown or malformed mark')
  }
  if (Object.keys(mark).some(k => k !== 'type' && k !== 'attrs')) fail('Unknown mark field')
  attributes(mark.attrs, schema.marks[mark.type]!.spec.attrs, mark.type)
  if (mark.type === 'textColor' && !isTextColor(mark.attrs?.color)) fail('Unknown textColor value')
  if (mark.type === 'link' && Object.values(mark.attrs ?? {}).some(v => v !== null && typeof v !== 'string')) fail('Invalid link attrs')
}

/** Restore the exact information the binding carries outside XML text formatting. */
export function restoredJSON(node: PmNode): PmNode {
  const next = { ...node, attrs: node.attrs && { ...node.attrs } }
  const c = next.attrs?.carried
  if (object(c)) {
    if (Array.isArray(c.marks)) next.marks = c.marks as PmMark[]
    for (const key of (c.nulls as string[] | undefined) ?? []) next.attrs![key] = null
  }
  if (node.content) next.content = node.content.map(restoredJSON)
  return next
}

function validateNode(node: PmNode, schema: Schema, depth = 0): void {
  if (depth > 200) fail('Document nesting limit exceeded')
  if (!object(node) || typeof node.type !== 'string' || !Object.hasOwn(schema.nodes, node.type)) {
    fail('Unknown or malformed node')
  }
  if (Object.keys(node).some(k => !['type', 'attrs', 'marks', 'content', 'text'].includes(k))) fail('Unknown node field')
  attributes(node.attrs, schema.nodes[node.type]!.spec.attrs, node.type)
  if (node.type === 'heading' && node.attrs?.level !== undefined &&
    (!Number.isInteger(node.attrs.level) || Number(node.attrs.level) < 1 || Number(node.attrs.level) > 6)) fail('Invalid heading level')
  if (node.type === 'toggle' && node.attrs?.open !== undefined && typeof node.attrs.open !== 'boolean') fail('Invalid toggle.open')
  if (node.type === 'taskItem' && node.attrs?.checked !== undefined && node.attrs.checked !== null && typeof node.attrs.checked !== 'boolean') fail('Invalid taskItem.checked')
  if (node.type === 'callout' && node.attrs?.kind !== undefined && !(CALLOUT_KINDS as readonly unknown[]).includes(node.attrs.kind)) fail('Unknown callout kind')
  if (node.type === 'table' && node.attrs?.align !== undefined && (!Array.isArray(node.attrs.align) ||
    node.attrs.align.some(v => ![null, 'left', 'right', 'center'].includes(v)))) fail('Invalid table alignment')
  if (node.type === 'orderedList' && node.attrs?.start !== undefined && (!Number.isInteger(node.attrs.start) ||
    Number(node.attrs.start) < 0 || Number(node.attrs.start) > 999_999_999)) fail('Invalid orderedList start')
  if (node.type === 'codeBlock' && node.attrs?.language != null &&
    (typeof node.attrs.language !== 'string' || /\s/.test(node.attrs.language))) fail('Unrepresentable code language')
  if (node.type === 'image' && ['src', 'alt', 'title'].some(k => node.attrs?.[k] != null && typeof node.attrs[k] !== 'string')) fail('Invalid image attrs')
  if (node.text !== undefined && typeof node.text !== 'string') fail('Invalid text')
  if (node.marks !== undefined && !Array.isArray(node.marks)) fail('Invalid marks')
  node.marks?.forEach(m => mark(m, schema))
  const c = node.attrs?.carried
  if (c !== undefined && c !== null) {
    if (!object(c) || Object.keys(c).some(k => !['marks', 'nulls'].includes(k))) fail('Unknown carried data')
    if (c.marks !== undefined) {
      if (!(MARK_CARRIERS as readonly string[]).includes(node.type) || !Array.isArray(c.marks)) fail('Invalid carried marks')
      for (const m of c.marks) mark(m as PmMark, schema)
    }
    if (c.nulls !== undefined) {
      if (!Array.isArray(c.nulls) || c.nulls.some(k => typeof k !== 'string' || !(NULL_CARRIERS[node.type] ?? []).includes(k))) {
        fail('Invalid carried nulls')
      }
    }
  }
  if (node.content !== undefined && !Array.isArray(node.content)) fail('Invalid content')
  node.content?.forEach(n => validateNode(n, schema, depth + 1))
}

function structuralCheck(node: PmDocument): void {
  if (!node.type.validContent(node.content)) fail(`Invalid content for ${node.type.name}`)
  node.forEach(structuralCheck)
  // Do not use Node.check's mark-exclusion repair policy: Studio's exact seed codec
  // supports code + textColor in colors.md despite Code's default excludes:'_'.
  // All marks/attrs are checked above and must survive the codec comparison below.
}

/** Comparison only: the existing Studio codec omits caret-only empty paragraphs and
 * trims plain (unmarked) trailing spaces in paragraphs/headings. These are normal
 * intermediate Space/Enter states, NOT unknown schema. Never mutate live XML/PM. */
function comparisonJSON(input: PmNode): PmNode {
  const node = carry(input)
  let content = node.content?.map(comparisonJSON)
  if (content && (node.type === 'paragraph' || node.type === 'heading')) {
    content = [...content]
    while (content.length) {
      const last = content.at(-1)!
      if (last.type !== 'text' || last.marks?.length) break
      const text = last.text!.replace(/[ \t]+$/, '')
      if (text === last.text) break
      if (text) { content[content.length - 1] = { ...last, text }; break }
      content.pop()
    }
  }
  if (content) content = content.filter(n => !(n.type === 'paragraph' && !n.content?.length))
  const result = { ...node }
  if (content?.length) result.content = content
  else delete result.content
  return result
}

function derivedAttributes(node: PmNode): void {
  if (node.type === 'wikiLink') {
    const { value, target, label } = node.attrs ?? {}
    if (typeof value !== 'string') fail('Invalid wikiLink.value')
    const bar = value.indexOf('|')
    const wantedTarget = (bar < 0 ? value : value.slice(0, bar)).trim()
    const wantedLabel = bar < 0 ? null : value.slice(bar + 1).trim() || null
    if (target !== wantedTarget || label !== wantedLabel) fail('Inconsistent derived wikiLink attrs')
  }
  node.content?.forEach(derivedAttributes)
}

/** Guard the codec-to-schema boundary too: Node.fromJSON drops unknown attrs while
 * building a seed. In this exact Studio schema code-fence meta is not an attribute;
 * non-null metadata must block instead of silently disappearing before preflight. */
export function validateSourceMarkdown(markdown: string): void {
  assertSafeColorSource(markdown)
  const schema = roomSchema()
  function visit(node: PmNode): void {
    const type = schema.nodes[node.type]
    if (!type) fail(`Unknown source node: ${node.type}`)
    for (const [key, value] of Object.entries(node.attrs ?? {})) {
      if (value !== null && value !== undefined && !Object.hasOwn(type.spec.attrs ?? {}, key)) {
        fail(`Source codec attribute cannot be represented: ${node.type}.${key}`)
      }
    }
    node.content?.forEach(visit)
  }
  visit(mdastToProseMirror(parseDocument(markdown).root))
}

function xmlShape(fragment: Y.XmlFragment, depth = 0): void {
  if (depth > 200) fail('Document nesting limit exceeded')
  for (const child of fragment.toArray()) {
    if (child instanceof Y.XmlElement) xmlShape(child, depth + 1)
    else if (child instanceof Y.XmlText) {
      if (depth === 0) fail('XML text at document root')
      for (const delta of child.toDelta()) {
        if (typeof delta.insert !== 'string') fail('Unsupported XML text embed')
        if (delta.attributes && Object.values(delta.attributes).some(a => !object(a))) fail('Invalid XML formatting')
      }
    } else fail('Unsupported Yjs XML type')
  }
}

/** Reads only. Never invokes ySyncPlugin or mutates a bound document. */
export function validateDocument(doc: Y.Doc): PmNode {
  for (const name of doc.share.keys()) if (name !== COLLAB_FIELD) fail(`Unknown Yjs field: ${name}`)
  // A partial update can hide incompatible structures until a later dependency arrives.
  if (doc.store.pendingStructs || doc.store.pendingDs) fail('Incomplete Yjs update dependencies')
  const fragment = doc.getXmlFragment(COLLAB_FIELD)
  xmlShape(fragment)
  const json = yXmlFragmentToProsemirrorJSON(fragment) as PmNode
  const schema = roomSchema()
  validateNode(json, schema)
  const restored = restoredJSON(json)
  const pm = schema.nodeFromJSON(restored)
  structuralCheck(pm)
  derivedAttributes(pm.toJSON() as PmNode)
  // ProseMirror silently drops unknown attrs; the codec can also drop valid but
  // non-Markdown attrs. Require representability, not merely Node.fromJSON success.
  const canonical = schema.nodeFromJSON(carry(mdastToProseMirror(proseMirrorToMdast(restored))))
  // Some PM attributes are intentionally permissive (e.g. heading.level). They must
  // also be accepted by the actual serializer BEFORE a live binding sees them.
  const serialized = serializeDocument(proseMirrorToMdast(restored), { frontmatter: '', eol: '\n', finalNewline: true })
  // The seed has sparse mark attrs in carried; an editor later fills schema defaults.
  // Recompute this derived carrier on both sides before comparing semantic content.
  if (stable(comparisonJSON(pm.toJSON() as PmNode)) !== stable(comparisonJSON(canonical.toJSON() as PmNode))) {
    fail('Room cannot be represented losslessly by this codec')
  }
  // VS-82 / INTEROP-01: mdast↔PM alone is insufficient. Studio's *Markdown*
  // reader reparses a color's contents as a paragraph, losing boundary spaces
  // produced by flattened nested marks or subsequent typing/foreign updates.
  // Compare the actual persisted form with the exact unchanged reader, BEFORE
  // binding a seed/remote clone or emitting any local update/change/save debt.
  const hasColor = (node: PmNode): boolean => Boolean(node.marks?.some(m => m.type === 'textColor') || node.content?.some(hasColor))
  if (hasColor(restored)) {
    const reopened = schema.nodeFromJSON(carry(mdastToProseMirror(parseDocument(serialized).root)))
    if (stable(comparisonJSON(pm.toJSON() as PmNode)) !== stable(comparisonJSON(reopened.toJSON() as PmNode))) {
      fail(COLOR_SAFETY_FAILURE)
    }
  }
  // Normalize schema defaults exactly as editor.getJSON does, including carried links.
  // Otherwise sparse atom-link attrs and full XML-text link attrs split one Markdown link.
  return pm.toJSON() as PmNode
}

/** Validate a state/update against an isolated clone, before touching the live binding. */
export function preflight(state: Uint8Array, update?: Uint8Array): Y.Doc {
  const copy = new Y.Doc()
  try {
    if (state.byteLength > 8 * 1024 * 1024 || (update?.byteLength ?? 0) > 8 * 1024 * 1024) fail('Update exceeds 8 MiB')
    Y.applyUpdate(copy, state)
    if (update) Y.applyUpdate(copy, update)
    validateDocument(copy)
    return copy
  } catch (error) {
    const raw = Y.encodeStateAsUpdate(copy)
    copy.destroy()
    throw new SchemaBlockedError(error instanceof Error ? error.message : String(error), raw, state.slice(), update?.slice())
  }
}
