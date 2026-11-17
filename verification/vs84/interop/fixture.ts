/** VS-84 independent real-browser/real-Remote acceptance. Test-only relay/host.
 * Imports BOTH current source trees. Never a Y.Text substitute.
 * The host below is NOT Kotlin RoomCoordinator: native ordering is a separate gate.
 */
import { Editor } from '@tiptap/core'
import * as Y from 'yjs'
import { RoomRuntime } from 'android-source/room-runtime'
import { roomSchema as androidSchema } from 'android-source/collab/seed'
import { parseDocument as ap, serializeDocument as as } from 'android-source/md/document'
import { mdastToProseMirror as am, proseMirrorToMdast as ab } from 'android-source/md/prosemirror'
import { encodeBase64, decodeBase64 } from 'android-source/bridge'
import { roomSchema, seedUpdate } from '@/lib/collab/seed'
import { openSession, toBase64 } from '@/lib/collab/session'
import { ProjectSession } from '@/session/project-session'
import { createExtensions } from '@/lib/editor/extensions'
import { createSlashState } from '@/lib/editor/slash-menu'
import { parseDocument, serializeDocument } from '@/lib/md/document'
import { mdastToProseMirror, proseMirrorToMdast } from '@/lib/md/prosemirror'

const wait=(ms=50)=>new Promise(r=>setTimeout(r,ms))
function ok(value:any,message:string){if(!value)throw Error(message)}
function equal(a:any,b:any,message:string){if(JSON.stringify(a)!==JSON.stringify(b))throw Error(message+'\n'+JSON.stringify({actual:a,expected:b}))}
async function until(f:()=>boolean,message:string){for(let i=0;i<150;i++){if(f())return;await wait(20)}throw Error('Timeout: '+message)}
const cleanup=new Set<()=>void>()
const rows:any[]=[]
async function test(name:string,run:()=>Promise<any>){try{const detail=await run();rows.push({name,status:'PASS',detail})}catch(e){rows.push({name,status:'FAIL',error:(e as Error).stack})}finally{for(const f of [...cleanup].reverse()){try{f()}catch{}}cleanup.clear();const p=document.createElement('pre');p.textContent=JSON.stringify(rows.at(-1),null,2);document.querySelector('#results')!.append(p)}}
let relay=''; let nextSid=0
class Wire {
  ws:WebSocket; frames:any[]=[]; sent:any[]=[]; subscribers=new Set<(f:any)=>void>(); records=new Map<string,any>(); user:any; id=0
  constructor(name:string){this.ws=new WebSocket(relay+'/'+name);this.ws.onmessage=e=>{const f=JSON.parse(e.data);this.frames.push(f);if(f.t==='hello')this.user=f.user;if(f.t==='change')this.records.set(f.path,f);for(const s of this.subscribers)s(f)};cleanup.add(()=>this.ws.close())}
  async ready(){await until(()=>!!this.user,'hello');await this.sync();return this}
  send(f:any){this.sent.push(f);this.ws.send(JSON.stringify(f))}
  async frame(t:string,from=0,predicate=(f:any)=>true){await until(()=>this.frames.slice(from).some(f=>f.t===t&&predicate(f)),t);return this.frames.slice(from).find(f=>f.t===t&&predicate(f))}
  async request(f:any,t:string){const from=this.frames.length;this.send(f);return this.frame(t,from,r=>f.sid===undefined||r.sid===f.sid)}
  async sync(){return this.request({t:'sync',since:0},'synced')}
  async put(path:string,content:string,live?:string){const base_rev=this.records.get(path)?.rev??0;const a=await this.request({t:'put',id:++this.id,path,content,base_rev,...(live?{live}:{})},'ack');this.records.set(path,{...a,content});return a}
}
class Android {
  runtime:RoomRuntime; events:any[]=[]; n=0; sid='android-'+(++nextSid); joined:any; pending:any[]=[]; ready=false; scribe=false; element:HTMLElement; prepared:any; closed=false
  constructor(public wire:Wire,public path:string,public source:string){
    this.element=document.body.appendChild(document.createElement('div'))
    this.runtime=new RoomRuntime(this.element,e=>{this.events.push(e);if(e.event==='localUpdate')wire.send({t:'update',sid:this.sid,update:(e.payload as any).update});if(e.event==='awareness')wire.send({t:'awareness',sid:this.sid,update:(e.payload as any).update})})
    this.runtime.configureUser({name:'Android',color:'#3e63dd'})
    wire.subscribers.add(this.receive);cleanup.add(()=>this.dispose())
  }
  command(command:string,fields:any={}){return this.runtime.handle({version:1,documentGeneration:1,connectionGeneration:1,sid:this.sid,requestId:++this.n,command,...fields})}
  async prepare(){await this.command('prepare',{markdown:this.source});this.prepared=this.events.find(e=>e.event==='prepared')?.payload;ok(this.prepared,'Prepared');this.joined=await this.wire.request({t:'join',sid:this.sid,path:this.path,base_rev:this.wire.records.get(this.path).rev,client_id:this.prepared.clientId},'joined');this.scribe=this.joined.scribe;return this}
  async seed(){if(this.joined.seeded)return this.joined.state;const s=await this.wire.request({t:'seed',sid:this.sid,update:this.prepared.seed},'seeded');return s.ok?this.prepared.seed:s.state}
  async initialize(state:string){await this.command('initialize',{markdown:this.source,state,readOnly:false});this.ready=true;for(const f of this.pending.splice(0))this.receive(f);await wait();return this}
  async open(){await this.prepare();return this.initialize(await this.seed())}
  receive=(f:any)=>{if(f.sid!==this.sid||this.closed)return;if(f.t==='scribe'){this.scribe=true;return}if(!['update','awareness','peer_left','peer_joined'].includes(f.t))return;if(!this.ready){this.pending.push(f);return}if(f.t==='update')void this.command('applyUpdate',{update:f.update});if(f.t==='awareness')void this.command('awareness',{update:f.update});if(f.t==='peer_left')void this.command('peerLeft',{clientId:f.client_id});if(f.t==='peer_joined')void this.command('publishAwareness')}
  get model(){return (this.runtime as any).model}
  get editor():Editor{return this.model.editor}
  snapshot(){return this.model.snapshot()}
  dispose(){if(this.closed)return;this.closed=true;this.wire.subscribers.delete(this.receive);if(this.wire.ws.readyState===1)this.wire.send({t:'leave',sid:this.sid});this.runtime.dispose();this.element.remove()}
}
class Studio {
  session:any; editor!:Editor; sid='studio-'+(++nextSid); joined:any; queuedSeed:any; releaseSeed!:()=>void; element:HTMLElement; closed=false; adapter:any; socketOpen=false
  constructor(public wire:Wire,public path:string,public source:string,public holdSeed=false){this.element=document.body.appendChild(document.createElement('div'));cleanup.add(()=>this.dispose())}
  async open(){
    const a:any={readyState:1,onopen:null,onmessage:null,onclose:null,send:(data:string)=>{const f=JSON.parse(data);const raw={...f,sid:this.sid,...(f.t==='join'?{base_rev:this.wire.records.get(this.path).rev}:{})};if(f.t==='seed'&&this.holdSeed){this.queuedSeed=raw;return}this.wire.send(raw)},close:()=>{if(a.readyState===1){a.readyState=3;this.wire.send({t:'leave',sid:this.sid});this.wire.subscribers.delete(this.receive)}}}
    this.adapter=a;this.wire.subscribers.add(this.receive)
    const promise=openSession({path:this.path,session:new ProjectSession(),seed:seedUpdate,socket:()=>a,timeoutMs:8000});queueMicrotask(()=>a.onopen?.());this.session=await promise;ok(this.session,'Studio openSession ready');this.editor=new Editor({element:this.element,extensions:createExtensions({slash:createSlashState(),collab:{session:this.session,onFirstRender:()=>{}}})});await wait(50);return this
  }
  receive=(f:any)=>{if(f.sid!==this.sid||this.closed)return;let local={...f};if(f.t==='joined'){this.joined=f;local={...f,content:this.source,user:this.wire.user};delete local.epoch}this.adapter?.onmessage?.({data:JSON.stringify(local)})}
  snapshot(){return serializeDocument(proseMirrorToMdast(this.editor.getJSON() as any),parseDocument(this.source).envelope)}
  dispose(){if(this.closed)return;this.closed=true;this.editor?.destroy();this.session?.close();this.wire.subscribers.delete(this.receive);this.element.remove()}
}
function edits(w:Wire){return w.sent.filter(f=>f.t==='put'||f.t==='delete')}
function dirty(w:Wire){return w.sent.filter(f=>f.t==='update')}
function find(editor:Editor,text:string){let at=-1;editor.state.doc.descendants((node,pos)=>{if(at<0&&node.isText&&node.text?.includes(text))at=pos+node.text.indexOf(text)});ok(at>=0,'Find '+text);return at}
async function connections(){const android=await new Wire('android').ready();const studio=await new Wire('studio').ready();const observer=await new Wire('observer').ready();return {android,studio,observer}}
async function pair(source='One.\n\nTwo.\n',race=false,studioWins=false){
  const w=await connections();const path='case-'+(++nextSid)+'.md';await w.observer.put(path,source);await w.android.sync();await w.studio.sync();const a=await new Android(w.android,path,source).prepare();const s=new Studio(w.studio,path,source,race);let state:string
  if(race){const opening=s.open();await until(()=>!!s.queuedSeed,'both independent seed offers');ok(!a.joined.seeded&&!s.joined.seeded,'Both joined unseeded');const an=w.android.frames.length, sn=w.studio.frames.length
    if(studioWins){w.studio.send(s.queuedSeed);w.android.send({t:'seed',sid:a.sid,update:a.prepared.seed})}else{w.android.send({t:'seed',sid:a.sid,update:a.prepared.seed});w.studio.send(s.queuedSeed)}
    const [ar,sr]=await Promise.all([w.android.frame('seeded',an),w.studio.frame('seeded',sn)]);ok(ar.ok!==sr.ok,'Exactly ONE seed wins (independent WS send order is not arrival order)');(s as any).actualWinner=ar.ok?'Android':'Studio';state=ar.ok?a.prepared.seed:ar.state;await a.initialize(state);await opening
  }else{state=await a.seed();await a.initialize(state);await s.open()}
  await wait();equal(a.snapshot().markdown,s.snapshot(),'Initial editor parity');return {...w,a,s,path,source,state}
}
function structural(schema:any){function specs(types:any,marks:boolean){return Object.entries(types).map(([name,type]:any,order)=>{const {toDOM,parseDOM,toText,...spec}=type.spec;return {name,order,...(marks?{rank:type.rank}:{}),attributeOrder:Object.keys(spec.attrs??{}),spec:JSON.parse(JSON.stringify(spec,(_k,v)=>{if(typeof v==='function'||v===undefined)throw Error('Nondata schema spec');return v}))}})}return{nodes:specs(schema.nodes,false),marks:specs(schema.marks,true)}}

async function runInterop(url:string,fixtures:Record<string,string>){relay=url
  await test('Current Studio ↔ Android structural schema + codec/envelope corpus parity',async()=>{
    equal(structural(androidSchema()),structural(roomSchema()),'Actual source structural schema (not stale golden)')
    const corpus={...fixtures,empty:'',crlf:'---\r\nyaml: "keep quotes" # raw\r\n---\r\n\r\n#### H4\r\n\r\ntext **[[plan]]**\r\n',noFinal:'###### H6\n\n*one*',custom:':::mystery{foo="bar"}\nContent <kbd>X</kbd>.\n:::\n',mixed:'- [x] task\n- plain\n\n[![alt](badge.png){width=123}](https://example.invalid)\n'}
    const details=[]
    for(const [name,source]of Object.entries(corpus)){
      const sp=parseDocument(source), pa=ap(source);equal(pa.envelope,sp.envelope,name+' envelope');equal(am(pa.root),mdastToProseMirror(sp.root),name+' PM mapping')
      const a=as(ab(am(pa.root)),pa.envelope),s=serializeDocument(proseMirrorToMdast(mdastToProseMirror(sp.root)),sp.envelope);equal(a,s,name+' serialization');equal(as(ab(am(ap(a).root)),ap(a).envelope),a,name+' second save stability')
      if(name.endsWith('colors.md')){
        const w=await connections(),path='blocked-fixture-'+(++nextSid)+'.md';await w.observer.put(path,source);await w.android.sync();const a=new Android(w.android,path,source);await a.command('prepare',{markdown:source});
        equal(a.events.map(e=>e.event),['blocked'],name+' explicit fail-closed, not removed from corpus');equal(a.events[0].payload.snapshot,{markdown:source,generation:0},'Original fixture recovery');equal(dirty(w.android).length+edits(w.android).length,0,'Unsafe fixture never published');details.push({name,outcome:'BLOCKED_EXPECTED',originalMarkdownRecovered:true});continue
      }
      const p=await pair(source,true,details.length%2===1);equal(p.a.snapshot().markdown,p.s.snapshot(),name+' real editors');
      equal(p.a.snapshot().markdown,s,name+' binding/codec parity')
      equal(dirty(p.android).length+dirty(p.studio).length,0,name+' open zero updates');p.a.dispose();p.s.dispose();details.push(name)
    }
    return{fixtures:details,count:details.length,nodeTypes:Object.keys(roomSchema().nodes),markTypes:Object.keys(roomSchema().marks)}
  })
  await test('UPSTREAM FAIL: unchanged Studio-only materialize → ACK → last leave → fresh room/reopen preserves visible spaces',async()=>{
    const source='A [nested [emphasis]{.blue} span]{.gray}.\n', w=await connections(), path='studio-upstream.md';await w.observer.put(path,source);await w.studio.sync();const s=await new Studio(w.studio,path,source).open();s.editor.commands.insertContentAt(1,'edit ');const saved=s.snapshot();ok(saved.includes('[nested ]{.gray}[emphasis]{.blue}[ span]{.gray}'),'Known first flattening keeps spaces');const ack=await w.studio.put(path,saved,s.sid);s.dispose();await wait(100);await w.studio.sync();equal(w.studio.records.get(path).content,saved,'Real ACK persisted exact bytes');const s2=await new Studio(w.studio,path,saved).open();ok(!s2.joined.seeded,'Fresh Remote room, no old Y.Doc');const reopened=s2.snapshot();
    // Capture full repro before failing: this is not merely nested-color flattening.
    const detail={source,saved,reopened,ack,expectedVisible:'edit A nested emphasis span.',actualVisible:s2.editor.getText(),loss:'spaces around emphasis disappear on second parse',scope:'UPSTREAM unchanged Studio-only defect; Android must block, not fix reference'}
    ;(window as any).colorRegression=detail
    equal(s2.editor.getText(),detail.expectedVisible,'UPSTREAM visible text loss remains FAIL, never skipped or normalized')
    return detail
  })
  await test('Seed race both orders: real Remote first-wins, no duplicated blocks',async()=>{const detail=[];for(let i=0;i<8;i++){const p=await pair(undefined,true,i%2===1);equal(p.a.snapshot().markdown,p.source,'No duplicate seed content');ok(p.a.prepared.seed!==toBase64(seedUpdate(p.source)),'Independent seed clients');detail.push({winner:(p.s as any).actualWinner,androidClientId:p.a.prepared.clientId,scribe:p.a.scribe,markdown:p.a.snapshot().markdown});p.a.dispose();p.s.dispose();if(new Set(detail.map(d=>d.winner)).size===2)break}equal(new Set(detail.map(d=>d.winner)).size,2,'Observed BOTH actual winner outcomes');return detail})
  for(const same of [false,true])await test('Concurrent '+(same?'same':'different')+' position edits + Android undo isolation',async()=>{
    const p=await pair();const ap=find(p.a.editor,'One'),sp=find(p.s.editor,same?'One':'Two')
    p.a.editor.commands.insertContentAt(ap,'A ');p.s.editor.commands.insertContentAt(sp,'B ')
    ok(!p.a.snapshot().markdown.includes('B ')&&!p.s.snapshot().includes('A '),'Truly concurrent before remote delivery')
    await until(()=>p.a.snapshot().markdown===p.s.snapshot()&&p.s.snapshot().includes('A ')&&p.s.snapshot().includes('B '),'Convergence');const merged=p.s.snapshot();equal((merged.match(/A /g)||[]).length,1,'One A');equal((merged.match(/B /g)||[]).length,1,'One B');await wait(650)
    ok(p.a.runtime.format('undo'),'Android collaborative undo');await until(()=>p.a.snapshot().markdown===p.s.snapshot()&&!p.s.snapshot().includes('A '),'Undo convergence');ok(p.s.snapshot().includes('B '),'Android undo preserves Studio edit');const after=p.s.snapshot();equal(edits(p.android).length+edits(p.studio).length,0,'No implicit host PUT')
    const ack=await p.android.put(p.path,p.a.snapshot().markdown,p.a.sid);await p.observer.sync();equal(p.observer.records.get(p.path).content,after,'Materialized merged Markdown');return{merged,afterAndroidUndo:after,rev:ack.rev}
  })
  await test('50 paired clean reopens (10 × empty/table/list/custom/envelope): zero put/delete/rev/hash/content changes',async()=>{
    const w=await connections();const corpus={empty:'',table:'| A | B |\n| :--- | ---: |\n| 1 | 2 |\n',list:'- [x] task\n- plain\n',custom:':::mystery{foo="bar"}\n<kbd>X</kbd> and **[[plan]]**.\n:::\n',envelope:'---\r\ny: "raw" # keep\r\n---\r\n\r\nText without final newline'}
    for(const [name,source]of Object.entries(corpus))await w.observer.put('open-'+name+'.md',source);await w.android.sync();await w.studio.sync();const initial=await w.observer.sync();const original=JSON.stringify([...w.observer.records]);const counts=[edits(w.android).length,edits(w.studio).length,edits(w.observer).length];const stats=[]
    for(let i=0;i<10;i++)for(const [name,source]of Object.entries(corpus)){
      const path='open-'+name+'.md';const a=await new Android(w.android,path,source).open();const s=await new Studio(w.studio,path,source).open();await a.command('publishAwareness');s.session.awareness.setLocalStateField('selectionTest',i);await wait(30)
      equal(a.snapshot().markdown,s.snapshot(),'Clean editor parity '+name);await a.command('flush');const clean=a.events.filter(e=>['changed','localUpdate','blocked'].includes(e.event));equal(clean,[],'Open/awareness/flush no update/debt '+name);ok(!s.session.changed&&!s.session.aheadAtJoin,'Studio clean state');const from=w.studio.frames.length;a.dispose();await w.studio.frame('scribe',from,f=>f.sid===s.sid);s.dispose();stats.push({iteration:i,fixture:name})
    }
    await wait();const final=await w.observer.sync();equal(final.seq,initial.seq,'No space revision changes');equal(JSON.stringify([...w.observer.records]),original,'Exact remote content/hash/rev');equal([edits(w.android).length,edits(w.studio).length,edits(w.observer).length],counts,'ZERO put/delete after setup');return{pairedReopens:stats.length,totalEditorOpens:stats.length*2,zeroPutDelete:true,seqBefore:initial.seq,seqAfter:final.seq,sourceContentAndHashesUnchanged:true}
  })
  await test('Awareness/cursors replay and peer-left do not edit Markdown or Remote',async()=>{
    const p=await pair();const before=await p.observer.sync(),au=dirty(p.android).length,su=dirty(p.studio).length;await p.a.command('publishAwareness');p.s.editor.view.focus();p.s.editor.commands.setTextSelection(3);await until(()=>p.s.session.peers().some((u:any)=>u.name==='Android'),'Android awareness reaches Studio');await until(()=>(p.a.runtime as any).awareness.getStates().size>=2,'Studio awareness reaches Android');const late=await new Android(p.android,p.path,p.source).open();await until(()=>(late.runtime as any).awareness.getStates().size>=3,'Awareness replay on peer_joined');const client=late.prepared.clientId;late.dispose();await until(()=>!p.s.session.awareness.getStates().has(client),'peer_left removes cursor');equal(p.a.snapshot().markdown,p.source,'Awareness keeps Markdown');equal([dirty(p.android).length,dirty(p.studio).length],[au,su],'Zero CRDT updates');const after=await p.observer.sync();equal(after.seq,before.seq,'Zero revisions');return{peerReplay:true,peerLeft:true,zeroDocumentUpdates:true,zeroWrites:edits(p.android).length+edits(p.studio).length===0}
  })
  await test('Dirty-at-join, clean/new-scribe handoff and outstanding materialization debt',async()=>{
    const p=await pair();const start=await p.observer.sync();p.s.editor.commands.insertContentAt(find(p.s.editor,'Two'),'UNSAVED ');await until(()=>p.a.snapshot().markdown===p.s.snapshot(),'Dirty merged document');await p.observer.sync();equal(p.observer.records.get(p.path).content,p.source,'Update delivery not durable')
    const late=await new Android(p.android,p.path,p.source).open();ok(late.joined.dirty,'Real Remote dirty at join');equal(late.snapshot().generation,0,'First render baseline does not pretend local edit');ok(late.snapshot().markdown.includes('UNSAVED '),'Joined state retains outstanding text');const inherited=late.snapshot().markdown
    p.a.dispose();await until(()=>p.s.session.scribe,'First handoff to Studio');p.s.dispose();await until(()=>late.scribe,'Second handoff to late Android');equal(edits(p.android).length+edits(p.studio).length,0,'No non-scribe/autosave PUT');await late.command('flush');const flushed=late.events.findLast(e=>e.event==='flushed').payload;equal(flushed.markdown,inherited,'Flush includes foreign debt');const ack=await p.android.put(p.path,flushed.markdown,late.sid);ok(ack.rev>start.seq,'Ack advances revision only at materialization');late.dispose();await p.observer.sync();equal(p.observer.records.get(p.path).content,inherited,'Debt persisted before leave');return{dirtyAtJoin:true,lateGeneration:flushed.generation,inheritedForeignDebt:true,ackBeforeLeave:true,markdown:inherited,scope:'test host orchestration; native coordinator ordering separately tested'}
  })
  await test('Unknown schema from real Remote: reject incremental and initial state, no repair/write',async()=>{
    const w=await connections();const source='Safe.\n',path='unknown.md';await w.observer.put(path,source);await w.android.sync();await w.studio.sync();const a=await new Android(w.android,path,source).open();const sid='inject-'+(++nextSid);const clone=new Y.Doc();Y.applyUpdate(clone,decodeBase64(a.prepared.seed));const joined=await w.studio.request({t:'join',sid,path,client_id:clone.clientID,base_rev:w.studio.records.get(path).rev},'joined');ok(joined.seeded,'Inject joins actual room');const node=new Y.XmlElement('futureBlock');const text=new Y.XmlText();text.insert(0,'Do not silently delete me');node.insert(0,[text]);const vector=Y.encodeStateVector(clone);clone.getXmlFragment('default').push([node]);const update=encodeBase64(Y.encodeStateAsUpdate(clone,vector));const before=await w.observer.sync();w.studio.send({t:'update',sid,update});await until(()=>a.events.some(e=>e.event==='blocked'),'Incremental schema blocked');const blocked=a.events.find(e=>e.event==='blocked').payload;equal(blocked.snapshot.markdown,source,'Last-valid Markdown retained');ok(blocked.raw.length>=2,'Raw rejected bytes retained');equal(a.snapshot().markdown,source,'Incumbent unchanged');ok(!a.runtime.format('bold'),'No formatting after block');await a.command('flush');ok(!a.events.some(e=>e.event==='flushed'),'No false successful flush');const late=await new Android(w.android,path,source).open();ok(late.events.some(e=>e.event==='blocked'),'Initial unknown schema blocked');equal(late.element.querySelectorAll('.tiptap').length,0,'No initial binding/repair');const after=await w.observer.sync();equal(after.seq,before.seq,'No schema-repair writes');equal(edits(w.android).length+edits(w.studio).length,0,'No put/delete');clone.destroy();return{initialBlocked:true,incrementalBlocked:true,lastValidMarkdown:blocked.snapshot.markdown,rawDiagnostics:blocked.raw.length,noRepairOrWrite:true}
  })
  await safetyTests()
  return rows
}
;(window as any).runInterop=runInterop

// Independent VS-84 checks. Real RoomRuntime/EditorModel and Studio Tiptap;
// test host only routes events and explicitly materializes safe snapshots.
async function remoteUnchanged(w:any,path:string,before:any){
  const after=await w.observer.sync();equal(after.seq,before.seq,'Remote seq unchanged');equal(w.observer.records.get(path),before.record,'Remote content/hash/rev unchanged');equal(edits(w.android).length+edits(w.studio).length,0,'No Android or Studio PUT/DELETE after setup')
}
async function baseline(w:any,path:string){const state=await w.observer.sync();return {...state,record:structuredClone(w.observer.records.get(path))}}
async function stopped(a:Android){const count=a.events.length;await a.command('flush');await a.command('flush');ok(!a.runtime.format('bold')&&!a.runtime.format('undo'),'Block cannot be bypassed by format/undo');equal(a.events.length,count,'No false Flushed after block')}
function xmlText(doc:Y.Doc){return (doc.getXmlFragment('default').get(0) as Y.XmlElement).get(0) as Y.XmlText}
function plain(doc:Y.Doc){return xmlText(doc).toDelta().map((d:any)=>typeof d.insert==='string'?d.insert:'').join('')}
function decoded(raw:string){const d=new Y.Doc();Y.applyUpdate(d,decodeBase64(raw));cleanup.add(()=>d.destroy());return d}
function recovery(a:Android,expected:string){
  const block=a.events.findLast(e=>e.event==='blocked')?.payload;ok(block,'Blocked event');const r=a.model.recovery();ok(r.rejectedUpdate,'Rejected local update retained');const state=encodeBase64(r.lastValidState),update=encodeBase64(r.rejectedUpdate);ok(block.raw.includes(state)&&block.raw.includes(update),'Emitted recovery includes exact valid state and rejected bytes');const d=decoded(state);Y.applyUpdate(d,decodeBase64(update));equal(plain(d),expected,'Exact rejected visible text reconstructed from emitted recovery');equal(block.snapshot,a.snapshot(),'Snapshot remains last valid, not unsafe Markdown');return {snapshot:block.snapshot,raw:block.raw,recoveredText:plain(d),delta:xmlText(d).toDelta()}
}
async function safetyTests(){
  await test('Fail-closed initial corpus: nested/adjacent/boundary spaces/tabs/bare CR + Unicode; exact recovery and zero writes',async()=>{
    const corpus=[
      'A [nested [emphasis]{.blue} span]{.gray}.\n','[a ]{.red}[b]{.blue}\n','[ a]{.red}\n','[a  ]{.blue}\n','[a\t]{.red}\n','[\ta]{.blue}\n','[a\rb]{.red}\n','[a\r]{.red}\n',
      '[Привет 😀 ]{.red}[世界e\u0301]{.blue}\n','[outer [внутри😀]{.blue} tail]{.gray}\n','[ ]{.gray}\n',
    ];const details=[]
    for(const source of corpus){const w=await connections(),path='initial-'+(++nextSid)+'.md';await w.observer.put(path,source);await w.android.sync();const before=await baseline(w,path),a=new Android(w.android,path,source);await a.command('prepare',{markdown:source});equal(a.events.map(e=>e.event),['blocked'],'Block before Prepared');equal(a.events[0].payload.snapshot,{markdown:source,generation:0},'Original source retained byte-exactly');equal(a.element.childElementCount,0,'No editor binding');equal(w.android.sent.filter(f=>['join','seed','update','put','delete'].includes(f.t)),[],'No unsafe join/seed/update/write');await stopped(a);await remoteUnchanged(w,path,before);details.push({source,events:a.events,recovery:a.events[0].payload});a.dispose()}
    return details
  })
  for(const kind of ['safe','nested','leading space','trailing tab','bare CR'])await test('Real Remote foreign full winner from safe initial: '+kind,async()=>{
    const source='a b.\n',w=await connections(),path='winner-'+(++nextSid)+'.md';await w.observer.put(path,source);await w.android.sync();await w.studio.sync();const before=await baseline(w,path)
    // Raw boundary Markdown is already destructively trimmed by Studio's reader.
    // Construct the dangerous winner via real Studio editing instead, so Android
    // receives actual unsafe CRDT text, not an already-damaged (now safe) seed.
    const foreign=kind==='nested'?'A [nested [emphasis]{.blue} span]{.gray}.\n':kind==='safe'?'[Привет]{.red} [世界😀e\u0301]{.blue}\n':source
    const s=await new Studio(w.studio,path,foreign).open();
    if(kind==='leading space')s.editor.chain().setTextSelection({from:2,to:4}).setTextColor('red').run()
    // Use text-bearing boundaries: a whitespace-only span (or x\ry fallback)
    // can round-trip literally and is not the unsafe loss case being asserted.
    if(kind==='trailing tab'||kind==='bare CR')s.editor.commands.insertContentAt(2,{type:'text',text:kind==='trailing tab'?'x\t':'x \r',marks:[{type:'textColor',attrs:{color:'red'}}]})
    if(!['safe','nested'].includes(kind)){ok(dirty(w.studio).length>0,'Real Studio emitted unsafe transaction');await wait(80)}
    if(kind!=='safe')ok(roomSchema().nodeFromJSON(mdastToProseMirror(parseDocument(s.snapshot()).root)).textContent!==s.editor.getText(),'Selected unsafe foreign winner demonstrably loses exact text in unchanged Studio reader')
    const a=await new Android(w.android,path,source).open();ok(a.joined.seeded,'Studio actually won Remote CAS');
    ;((window as any).safetyDiagnostics??=[]).push({kind,foreign,studioJSON:s.editor.getJSON(),studioMarkdown:s.snapshot(),studioText:s.editor.getText(),androidEvents:a.events,joined:a.joined})
    if(kind==='safe'){ok(!a.events.some(e=>e.event==='blocked'),'Safe foreign winner allowed');equal(a.editor.getText(),'Привет 世界😀e\u0301','Safe foreign visible text');equal(a.snapshot().generation,0,'Foreign baseline no local generation')}
    else {equal(a.events.map(e=>e.event),['prepared','blocked'],'Foreign unsafe winner blocked before Ready');equal(a.events[1].payload.snapshot,{markdown:source,generation:0},'Safe original kept');ok(a.events[1].payload.raw.includes(a.joined.state),'Rejected winner exact bytes retained');equal(a.element.querySelectorAll('.tiptap').length,0,'No unsafe binding');await stopped(a)}
    equal(dirty(w.android),[],'No Android echo');await remoteUnchanged(w,path,before);return {kind,foreign,events:a.events,actualForeignVisible:s.editor.getText()}
  })
  for(const unsafe of [false,true])await test('Real Remote foreign incremental '+(unsafe?'unsafe color boundary blocks before Applied':'safe color update remains editable'),async()=>{
    const p=await pair('a b.\n'),before=await baseline(p,p.path),state=encodeBase64(p.a.model.encodeState()),snapshot=p.a.snapshot(),at=p.a.events.length
    p.s.editor.chain().setTextSelection({from:1,to:unsafe?3:2}).setTextColor('red').run();await until(()=>p.a.events.slice(at).some(e=>e.event===(unsafe?'blocked':'applied')),'Remote delivered foreign formatting');
    if(unsafe){equal(p.a.events.slice(at).map(e=>e.event),['blocked'],'Block before Applied/Changed');equal(encodeBase64(p.a.model.encodeState()),state,'Live XML unchanged');equal(p.a.snapshot(),snapshot,'Last valid snapshot unchanged');const block=p.a.events.at(-1).payload;ok(block.raw.includes(state),'Valid state retained');const r=p.a.model.recovery();ok(block.raw.includes(encodeBase64(r.rejectedUpdate))&&block.raw.includes(encodeBase64(r.rejectedState)),'Rejected merged state + update retained');equal(xmlText(decoded(encodeBase64(r.rejectedState))).toDelta(),[{insert:'a ',attributes:{textColor:{color:'red'}}},{insert:'b.'}],'Exact rejected boundary retained');await stopped(p.a)}
    else {equal(p.a.editor.getText(),'a b.','Safe formatting visible text');equal(p.a.snapshot().markdown,p.s.snapshot(),'Safe formatting parity');ok(!p.a.model.blocked&&p.a.editor.isEditable,'Safe foreign update editable')}
    equal(dirty(p.android).length,0,'No echo');await remoteUnchanged(p,p.path,before);return {unsafe,events:p.a.events.slice(at),lastValid:snapshot}
  })
  for(const kind of ['typing','format','final DOM flush','final composition flush'])await test('Unsafe local '+kind+': stop before LocalUpdate/PUT, exact rejected recovery, no false final flush',async()=>{
    const source=kind==='format'?'a b.\n':'[word]{.red} tail\n',p=await pair(source),before=await baseline(p,p.path),at=p.a.events.length,snapshot=p.a.snapshot();let expected='word  tail';
    if(kind==='typing')p.a.editor.chain().setTextSelection(5).setTextColor('red').insertContent(' ').run()
    else if(kind==='format'){p.a.editor.chain().setTextSelection({from:1,to:3}).setTextColor('red').run();expected='a b.'}
    else {
      const view=p.a.editor.view,colored=view.dom.querySelector('[data-text-color="red"]')!.firstChild as Text,originalBlur=view.dom.blur;let composing=kind==='final composition flush';
      Object.defineProperty(view,'composing',{configurable:true,get:()=>composing});
      view.dom.blur=()=>{if(!composing)colored.data='word '};
      const flushing=p.a.command('flush');if(composing){await wait(5);equal(p.a.events.length,at,'Flush waits for outstanding composition');colored.data='word ';composing=false}await flushing;view.dom.blur=originalBlur
    }
    equal(p.a.events.slice(at).map(e=>e.event),['blocked'],'No LocalUpdate/Changed/Flushed before block');equal(p.a.snapshot(),snapshot,'Valid recovery snapshot retained');equal(dirty(p.android).length,0,'No rejected update escaped to actual Remote');ok(!p.a.editor.isEditable,'Editor is frozen');const detail=recovery(p.a,expected);await stopped(p.a);await remoteUnchanged(p,p.path,before);return {...detail,scope:'Chrome DOMObserver/composition seam, not physical Android IME or native recovery persistence'}
  })
  await test('Safe colors + Unicode: 4 edit/materialize/real ACK/last-leave/fresh-room cycles each, exact visible text',async()=>{
    const corpus=[{source:'[a]{.red}[b]{.blue} [c]{.gray}\n',text:'ab c'},{source:'[one two]{.red} [three four]{.blue}\n',text:'one two three four'},{source:'[outer[inner]{.blue}tail]{.gray}\n',text:'outerinnertail'},{source:'[Привет 😀]{.red} [世界e\u0301]{.blue}\n',text:'Привет 😀 世界e\u0301'},{source:'[A\u00a0B]{.red} [C\u2003D]{.blue}\n',text:'A\u00a0B C\u2003D'}];const details=[]
    for(const entry of corpus){const w=await connections(),path='cycles-'+(++nextSid)+'.md';let markdown=entry.source,expected=entry.text;await w.observer.put(path,markdown);const cycles=[];
      for(let i=0;i<4;i++){
        await w.android.sync();await w.studio.sync();const a=await new Android(w.android,path,markdown).open();ok(!a.joined.seeded,'Last leave truly destroyed Remote room');const s=await new Studio(w.studio,path,markdown).open();equal(a.editor.getText(),expected,'Android exact reopened visible text');equal(s.editor.getText(),expected,'Studio exact reopened visible text');
        a.editor.commands.insertContentAt(2,'x');expected=expected.slice(0,1)+'x'+expected.slice(1);a.editor.chain().setTextSelection({from:1,to:2}).setTextColor(i%2?'green':'red').run();await until(()=>a.snapshot().markdown===s.snapshot(),'Safe edit and formatting converge');equal(a.editor.getText(),expected,'Android exact edited text');equal(s.editor.getText(),expected,'Studio exact edited text');ok(!a.model.blocked,'Safe color stays supported');
        await a.command('flush');const flushed=a.events.findLast(e=>e.event==='flushed')?.payload;ok(flushed,'Successful safe Flush');markdown=flushed.markdown;const ack=await w.android.put(path,markdown,a.sid);await w.observer.sync();equal(w.observer.records.get(path).content,markdown,'Real persisted ACK bytes');ok(ack.rev>0,'Real Remote ACK');cycles.push({cycle:i,markdown,expectedVisible:expected,androidVisible:a.editor.getText(),studioVisible:s.editor.getText(),ack});a.dispose();s.dispose();await wait(80)
      }
      await w.android.sync();await w.studio.sync();const a=await new Android(w.android,path,markdown).open(),s=await new Studio(w.studio,path,markdown).open();ok(!a.joined.seeded,'Final fresh room');equal(a.editor.getText(),expected,'Final Android text exact');equal(s.editor.getText(),expected,'Final Studio text exact');a.dispose();s.dispose();details.push({source:entry.source,cycles,finalVisible:expected})
    }return details
  })
  await test('RoomRuntime strict bridge security: malformed/version/type/context/replay/unknown format',async()=>{
    const p=await pair(),a=p.a,base={version:1,documentGeneration:1,connectionGeneration:1,sid:a.sid,requestId:100,command:'flush'};const before=a.events.length;
    for(const bad of [{...base,version:2},{...base,requestId:-1},{...base,connectionGeneration:[]},null]){let rejected=false;try{await a.runtime.handle(bad)}catch{rejected=true}ok(rejected,'Malformed envelope rejected')}
    await a.runtime.handle({...base,sid:'stale-room'});equal(a.events.length,before,'Stale context inert');await a.command('publishAwareness');const count=a.events.length;await a.runtime.handle({...base,requestId:1});equal(a.events.length,count,'Replayed request inert');ok(!a.runtime.format('__proto__')&&!a.runtime.format('constructor'),'Unknown format cannot call prototype');ok(!a.model.blocked,'Invalid envelopes do not corrupt incumbent');equal(dirty(p.android).length+edits(p.android).length,0,'Security attempts cannot write');return {invalidRejected:4,staleContextInert:true,replayInert:true,prototypeDenied:true,scope:'browser runtime; native origin/TLS transport covered separately'}
  })
  await test('Reference literal-fallback controls: whitespace-only and interior CR are not falsely called data loss',async()=>{
    const details=[];for(const inner of [' ','\t','\r','x\ry']){let source='a['+inner+']{.red} b.\n';const expected='a'+inner+' b.';const cycles=[];
      for(let i=0;i<4;i++){const parsed=parseDocument(source),pm=mdastToProseMirror(parsed.root);equal(roomSchema().nodeFromJSON(pm).textContent,expected,'Reference fallback retains exact literal control bytes');source=serializeDocument(proseMirrorToMdast(pm),parsed.envelope);cycles.push(source)}details.push({inner,expected,cycles})
    }return {details,scope:'Reference codec-only negative controls, not an Android supported-operation claim; Android raw-source guard is intentionally more conservative'}
  })
}
