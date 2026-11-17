import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { JSDOM } from 'jsdom'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const dom = new JSDOM('<!doctype html><body></body>', { pretendToBeVisual: true, url: 'https://appassets.androidplatform.net/' })
for (const name of ['window', 'document', 'navigator', 'Node', 'Element', 'HTMLElement', 'HTMLAnchorElement', 'DOMParser', 'MutationObserver', 'getComputedStyle']) {
  Object.defineProperty(globalThis, name, { value: dom.window[name], configurable: true })
}
const moduleURL = pathToFileURL(resolve(process.env.EDITOR_DIST_DIR ?? 'dist', 'editor-model.js'))
const m = await import(moduleURL.href)
const source = '# Bundled editor\n\nOne.\n'
const seed = m.decodeBase64(m.createSeedOffer(source))
let updates = 0
const model = m.EditorModel.open({
  markdown: source, state: seed, element: document.body.appendChild(document.createElement('div')),
  onUpdate: () => updates++,
})
try {
  // Exercise the public exports from the actual ESM artifact, not a source import.
  assert.equal(typeof m.decodeBase64, 'function')
  assert.equal(typeof m.encodeBase64, 'function')
  const maxBytes = 8 * 1024 * 1024
  for (const size of [4_000_000, 8_000_000, maxBytes]) {
    for (const pattern of ['zero', 'varied']) {
      const input = Buffer.alloc(size)
      if (pattern === 'varied') {
        for (let i = 0; i < input.length; i++) input[i] = (i * 31 + (i >>> 8)) & 255
      }
      const encoded = m.encodeBase64(input)
      assert.ok(encoded === input.toString('base64'), `Canonical encoding: ${size} ${pattern}`)
      const decoded = m.decodeBase64(encoded)
      assert.ok(decoded instanceof Uint8Array)
      assert.equal(decoded.length, size)
      assert.ok(Buffer.from(decoded.buffer, decoded.byteOffset, decoded.byteLength).equals(input),
        `All decoded bytes: ${size} ${pattern}`)
    }
  }
  const oversized = Buffer.alloc(maxBytes + 1).toString('base64')
  assert.equal(oversized.length, 4 * Math.ceil(maxBytes / 3))
  for (const encoded of [
    oversized, '!!!!', '____', '\u00e9AAA', 'YQ', 'YQ=', 'YQ===', 'Y=Q=', 'YQ==AAAA',
    ' YQ==', 'YQ==\n', 'Y Q=', 'Y\tQ=', 'Y\r\nQ==', 'YR==', 'YWJ=',
  ]) {
    assert.throws(() => m.decodeBase64(encoded), /Invalid or oversized base64/)
  }
  for (const encoded of ['', 'YQ==', 'YWI=', 'YWJj', '+/8=']) {
    assert.equal(m.encodeBase64(m.decodeBase64(encoded)), encoded)
  }
  await model.ready
  if (model.snapshot().markdown !== source || updates !== 0) throw new Error('Bundle open mutated the document')
  model.editor.commands.insertContentAt(20, 'A ')
  if (model.blocked || updates === 0) throw new Error('Bundle editing failed')
  // VS-82: the shipping ESM must contain the guard, not just source-test helpers.
  assert.throws(() => m.createSeedOffer('A [nested [emphasis]{.blue} span]{.gray}.\n'), /INTEROP-01/)
  assert.throws(() => m.createSeedOffer('[ text ]{.red}\n'), /INTEROP-01/)
  let unsafeUpdates = 0
  const colors = m.EditorModel.open({
    element: document.body.appendChild(document.createElement('div')),
    markdown: '[word]{.red} tail\n', state: m.decodeBase64(m.createSeedOffer('[word]{.red} tail\n')),
    onUpdate: () => unsafeUpdates++,
  })
  try {
    await colors.ready
    colors.editor.chain().setTextSelection(5).setTextColor('red').insertContent(' ').run()
    assert.equal(colors.blocked, true)
    assert.equal(unsafeUpdates, 0)
    assert.ok(colors.recovery().rejectedUpdate?.length)
    await assert.rejects(() => colors.flush(), /unavailable/)
  } finally { colors.destroy() }
  console.log('Standalone ESM/DOM bundle smoke: pass')
} finally {
  model.destroy()
  dom.window.close()
}
