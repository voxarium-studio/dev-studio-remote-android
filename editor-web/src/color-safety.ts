/** VS-82 / INTEROP-01, protocol §§5,11,13: Android-only publication guard.
 * The shared Studio reader is intentionally NOT changed. Never publish a document
 * whose visible color-boundary spaces it would discard on the next room seed.
 */
import type { Nodes } from 'mdast'
import { maskColors } from './md/color'
import { parseDocument } from './md/document'
import { maskWikiLinks } from './md/wikilink'

export const COLOR_SAFETY_FAILURE = 'INTEROP-01: unsafe color round-trip; preserve recovery and reopen read-only'

/** Raw-source check MUST precede seed parsing: that reader has already lost the
 * evidence in `[ text ]{.red}`. Check only tokens used as phrasing text, not code,
 * frontmatter, image alt/URLs, HTML or wiki-link payloads. Reuse the codec's exact
 * scanner rather than interpreting arbitrary bracket lookalikes as color spans.
 */
export function assertSafeColorSource(markdown: string, depth = 0): void {
  if (depth > 200) throw new Error('Document nesting limit exceeded')
  const parsed = parseDocument(markdown)
  const normalized = markdown.replace(/\r\n/g, '\n')
  const body = normalized.slice(parsed.envelope.frontmatter.length)
  const masked = maskColors(maskWikiLinks(body).text)
  if (!masked.sentinel || !masked.spans.length) return
  const used = new Set<number>()
  const pattern = new RegExp(`${masked.sentinel}(\\d+)${masked.sentinel}`, 'g')
  const stack: Nodes[] = [parseDocument(masked.text).root]
  while (stack.length) {
    const node = stack.pop()!
    if (node.type === 'text') {
      for (const match of node.value.matchAll(pattern)) used.add(Number(match[1]))
    }
    if ('children' in node) for (const child of node.children) stack.push(child as Nodes)
  }
  for (const index of used) {
    const span = masked.spans[index]
    if (!span) continue
    // CommonMark parses each span as a paragraph: these literal boundary bytes
    // are consumed. Entities/Unicode whitespace are instead checked by the real
    // serialize→parse comparison, not by broad whitespace "normalization".
    // The scanner is LF-bounded but remark also treats bare CR as a line ending;
    // reject that mismatch before paragraph parsing can eat adjacent spaces.
    if (/^[ \t]|[ \t]$/.test(span.inner) || span.inner.includes('\r')) throw new Error(COLOR_SAFETY_FAILURE)
    assertSafeColorSource(span.inner, depth + 1)
  }
}
