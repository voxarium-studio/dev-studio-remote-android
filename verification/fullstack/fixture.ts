import { Editor } from '@tiptap/core'
import { RoomRuntime } from 'android-source/room-runtime'
import { seedUpdate } from '@/lib/collab/seed'
import { openSession } from '@/lib/collab/session'
import { ProjectSession } from '@/session/project-session'
import { createExtensions } from '@/lib/editor/extensions'
import { createSlashState } from '@/lib/editor/slash-menu'
import { parseDocument, serializeDocument } from '@/lib/md/document'
import { proseMirrorToMdast } from '@/lib/md/prosemirror'
import * as Y from 'yjs'
import { COLLAB_FIELD } from 'android-source/collab/carried'
import { decodeBase64 } from 'android-source/bridge'

const events:any[]=[]
const runtime=new RoomRuntime(document.querySelector('#native')!,e=>events.push(e))
runtime.configureUser({name:'Production native coordinator',color:'#3e63dd'})
let wire:WebSocket, adapter:any, session:any, editor:Editor|undefined, source='', user:any, sid='', records=new Map<string,any>()
const frames:any[]=[]
const sleep=(n=20)=>new Promise(r=>setTimeout(r,n))
async function until(f:()=>boolean){for(let n=0;n<500;n++){if(f())return;await sleep()}throw Error('Reference Studio timeout')}
function closeStudio(){editor?.destroy();editor=undefined;session?.close();session=undefined;wire?.close();adapter=undefined}
async function openStudio(a:any){
  closeStudio();source=a.source;sid='studio-'+Date.now();frames.length=0;user=undefined;records.clear()
  wire=new WebSocket(a.relay)
  wire.onmessage=e=>{const f=JSON.parse(e.data);frames.push(f);if(f.t==='hello')user=f.user;if(f.t==='change')records.set(f.path,f)
    if(f.sid===sid&&adapter){const local=f.t==='joined'?{...f,content:source,user}:f;
      // Match Studio backend relay contract: Remote room epoch is NOT a project-renderer epoch.
      if(f.t==='joined')delete local.epoch
      adapter.onmessage?.({data:JSON.stringify(local)})}}
  await until(()=>!!user);wire.send(JSON.stringify({t:'sync',since:0}));await until(()=>frames.some(f=>f.t==='synced'))
  const socket:any={readyState:1,onopen:null,onmessage:null,onclose:null,
    send:(raw:string)=>{const f=JSON.parse(raw);wire.send(JSON.stringify({...f,sid,...(f.t==='join'?{base_rev:records.get(a.path).rev}:{})}))},
    close:()=>{if(socket.readyState===1){socket.readyState=3;wire.send(JSON.stringify({t:'leave',sid}))}}}
  adapter=socket
  const pending=openSession({path:a.path,session:new ProjectSession(),seed:seedUpdate,socket:()=>socket,timeoutMs:8000})
  queueMicrotask(()=>socket.onopen?.());session=await pending
  if(!session)throw Error('Reference openSession failed')
  editor=new Editor({element:document.querySelector('#studio')!,extensions:createExtensions({slash:createSlashState(),collab:{session,onFirstRender:()=>{}}})})
  await sleep(50);return {scribe:session.scribe}
}
;(window as any).gate={
  command:async(c:any)=>{await runtime.handle(c);return true},
  events:()=>events.splice(0),
  action:async(a:any)=>{
    if(a.op==='openStudio')return openStudio(a)
    if(a.op==='closeStudio'){closeStudio();return true}
    if(a.op==='editNative'){const model=(runtime as any).model;if(!model)throw Error('Native editor absent');model.editor.commands.insertContentAt(1,a.text);return model.snapshot()}
    if(a.op==='editStudio'){if(!editor)throw Error('Studio absent');editor.commands.insertContentAt(1,a.text);return true}
    if(a.op==='unsafeNative'||a.op==='unsafeStudio'){
      const target=a.op==='unsafeNative'?(runtime as any).model?.editor:editor
      if(!target)throw Error('Editor absent')
      target.commands.insertContentAt(1,[{type:'text',text:'REJECTED ',marks:[{type:'textColor',attrs:{color:'red'}}]}])
      return true
    }
    if(a.op==='safeColorNative'){
      const target=(runtime as any).model?.editor
      if(!target)throw Error('Editor absent')
      target.chain().setTextSelection({from:1,to:2}).setTextColor('blue').run();return true
    }
    if(a.op==='decodeRecovery'){
      const states=a.raw.map((raw:string)=>{const d=new Y.Doc();Y.applyUpdate(d,decodeBase64(raw));const xml=d.getXmlFragment(COLLAB_FIELD).toString();d.destroy();return xml})
      const d=new Y.Doc();for(const raw of a.raw)Y.applyUpdate(d,decodeBase64(raw));const merged=d.getXmlFragment(COLLAB_FIELD).toString();d.destroy()
      return {states,merged}
    }
    if(a.op==='visible')return {native:(runtime as any).model?.editor.getText()??null,studio:editor?.getText()??null}
    if(a.op==='snapshot')return {native:(runtime as any).model?.snapshot()??null,studio:editor?serializeDocument(proseMirrorToMdast(editor.getJSON() as any),parseDocument(source).envelope):null,scribe:session?.scribe??null}
    throw Error('Unknown browser action')
  }
}
