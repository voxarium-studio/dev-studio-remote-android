import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { JSDOM } from 'jsdom'

// No server or network: execute the exact packaged classic page bundle in an isolated DOM.
const dir = resolve(process.env.EDITOR_DIST_DIR ?? 'dist', 'room')
const html = readFileSync(resolve(dir, 'index.html'), 'utf8')
assert.match(html, /connect-src 'none'/)
assert.match(html, /img-src 'none'/)
assert.match(html, /script-src 'self'/)
assert.doesNotMatch(html, /https?:\/\//)
const css = readFileSync(resolve(dir, 'editor.css'), 'utf8')
assert.doesNotMatch(css, /@import|url\(/i)
const js = readFileSync(resolve(dir, 'editor.js'), 'utf8')
assert.doesNotMatch(js, /\bimport\s*\(/)
const dom = new JSDOM(html, { url: 'https://appassets.androidplatform.net/assets/editor/index.html', pretendToBeVisual: true, runScripts: 'outside-only' })
const w = dom.window
const events = []
w.fetch = () => { throw new Error('Unexpected network') }
w.WebSocket = class { constructor() { throw new Error('Unexpected WebSocket') } }
w.voxariumBridge = { postMessage(json) { events.push(JSON.parse(json)) } }
const context = { version: 1, documentGeneration: 1, connectionGeneration: 1, sid: 'bundle' }
const send = (requestId, command, fields = {}) => w.voxariumEditorReceive({ ...context, requestId, command, ...fields })
const until = async (event) => {
  const end = Date.now() + 3000
  while (!events.some(e => e.event === event)) {
    if (Date.now() > end) throw new Error(`No ${event} event`)
    await new Promise(resolve => setTimeout(resolve, 10))
  }
  return events.find(e => e.event === event)
}
try {
  // WebView provides these browser primitives. jsdom's Window omits TextEncoder.
  w.TextEncoder = TextEncoder
  w.TextDecoder = TextDecoder
  w.eval(js)
  assert.equal(typeof w.voxariumEditorReceive, 'function')
  assert.equal(typeof w.voxariumEditorConfigure, 'function')
  assert.equal(typeof w.voxariumEditorFormat, 'function')
  w.voxariumEditorConfigure({ dark: true, language: 'ru' })
  assert.equal(w.document.documentElement.lang, 'ru')
  assert.ok(w.document.documentElement.classList.contains('dark'))
  assert.equal(w.document.getElementById('status').textContent, 'Открытие документа…')
  send(1, 'prepare', { markdown: 'Bundle.\n' })
  const prepared = await until('prepared')
  assert.equal(typeof prepared.payload.clientId, 'number')
  assert.equal(w.document.getElementById('editor').children.length, 0)
  send(2, 'initialize', { markdown: 'Bundle.\n', state: prepared.payload.seed, readOnly: false })
  assert.deepEqual((await until('ready')).payload, { markdown: 'Bundle.\n', generation: 0 })
  assert.ok(!events.some(e => e.event === 'changed' || e.event === 'localUpdate'))
  assert.equal(w.voxariumEditorFormat('eval'), false)
  assert.equal(w.voxariumEditorFormat('bulletList'), true)
  const names = events.map(e => e.event)
  assert.ok(names.indexOf('localUpdate') < names.indexOf('changed'))
  assert.equal(events.find(e => e.event === 'localUpdate').requestId, null)
  send(3, 'flush')
  const flushed = await until('flushed')
  assert.equal(flushed.requestId, 3) // format did not consume coordinator request IDs
  assert.match(flushed.payload.markdown, /Bundle/)
  assert.equal(w.voxariumEditorFormat('undo'), false)
  send(4, 'dispose')
  await until('disposed')
  assert.equal(w.document.getElementById('editor').children.length, 0)
  assert.deepEqual(events.find(e => e.event === 'disposed').payload, {})
  assert.ok(!events.some(e => e.event === 'blocked'))
  // VS-82: packaged RoomRuntime must refuse the seed before publication, retain
  // original Markdown for recovery, and never claim a successful Flush afterward.
  events.length = 0
  context.documentGeneration = 2
  const unsafe = 'A [nested [emphasis]{.blue} span]{.gray}.\n'
  send(5, 'prepare', { markdown: unsafe })
  const blocked = await until('blocked')
  assert.deepEqual(blocked.payload.snapshot, { markdown: unsafe, generation: 0 })
  send(6, 'flush')
  await new Promise(resolve => setTimeout(resolve, 30))
  assert.deepEqual(events.map(e => e.event), ['blocked'])
  assert.equal(w.voxariumEditorFormat('undo'), false)
  send(7, 'dispose')
  await until('disposed')
  console.log('Standalone APK page/bridge/theme/RU/format/flush smoke: pass')
} finally {
  w.dispatchEvent(new w.Event('pagehide'))
  dom.window.close()
}
