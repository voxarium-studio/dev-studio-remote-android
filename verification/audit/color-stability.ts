// Independent semantic regression: the documented nested-color flattening must not lose spaces.
import { EditorModel, createSeedOffer, decodeBase64 } from 'android-source/index'
import { Editor } from '@tiptap/core'
import * as Y from 'yjs'
import { Awareness } from 'y-protocols/awareness'
import { seedUpdate } from '@/lib/collab/seed'
import { createExtensions } from '@/lib/editor/extensions'
import { createSlashState } from '@/lib/editor/slash-menu'
import { parseDocument, serializeDocument } from '@/lib/md/document'
import { proseMirrorToMdast } from '@/lib/md/prosemirror'

const delay = () => new Promise(r => setTimeout(r, 50))
async function cycle(kind: string, source: string) {
  const element = document.body.appendChild(document.createElement('div'))
  if (kind === 'Android') {
    const model = EditorModel.open({ markdown: source, state: decodeBase64(createSeedOffer(source)), element })
    await model.ready
    const result = { markdown: model.snapshot().markdown, text: model.editor.getText() }
    model.destroy(); element.remove(); return result
  }
  const doc = new Y.Doc(); Y.applyUpdate(doc, seedUpdate(source))
  const awareness = new Awareness(doc)
  const editor = new Editor({ element, extensions: createExtensions({ slash: createSlashState(),
    collab: { session: { doc, awareness, user: {id:'test',name:'test',color:'#3e63dd'} } as any, onFirstRender:()=>{} } }) })
  await delay()
  const result = { markdown: serializeDocument(proseMirrorToMdast(editor.getJSON() as any), parseDocument(source).envelope), text: editor.getText() }
  editor.destroy(); awareness.destroy(); doc.destroy(); element.remove(); return result
}
(window as any).probe = async () => {
  const source = 'A [nested [emphasis]{.blue} span]{.gray}\n'
  const results=[]
  for(const kind of ['Android','Studio']) {
    const first = await cycle(kind, source)
    const second = await cycle(kind, first.markdown)
    const third = await cycle(kind, second.markdown)
    results.push({ kind, source, first, second, third,
      semanticStable: first.text === second.text && second.text === third.text,
      bytesStableAfterFirst: first.markdown === second.markdown })
  }
  return results
}
