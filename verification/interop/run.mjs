// Browser relay only adds private test Bearer headers; NOT Android native transport.
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawn, execFileSync } from 'node:child_process';
import { once } from 'node:events';
import net from 'node:net';

const here = path.dirname(fileURLToPath(import.meta.url));
const temp = process.env.INTEROP_TEMP || path.resolve(here, '../../../VoxariumStudio/.voxarium_studio/temp/TASK-78/interop');
const studio = process.env.STUDIO_ROOT || path.resolve(here, '../../../VoxariumStudio');
const android = process.env.ANDROID_ROOT || path.resolve(here, '../..');
fs.mkdirSync(temp+'/system-temp',{recursive:true});
process.env.TEMP=temp+'/system-temp';process.env.TMP=process.env.TEMP;
const req = createRequire(path.join(temp, 'deps/package.json'));
const { createServer } = await import(pathToFileURL(req.resolve('vite')).href);
const vue = (await import(pathToFileURL(req.resolve('@vitejs/plugin-vue')).href)).default;
const { chromium } = req('playwright');
const { WebSocket, WebSocketServer } = req('ws');
const evidence = path.join(here, 'evidence'); fs.mkdirSync(evidence, { recursive: true });
const runData = fs.mkdtempSync(path.join(temp, 'remote-'));
const sourceFiles = [];
function walk(root) { for (const e of fs.readdirSync(root, { withFileTypes: true })) { const p = path.join(root, e.name); if(e.isDirectory()) walk(p); else sourceFiles.push(p); } }
for (const p of [studio+'/frontend/src/lib/collab',studio+'/frontend/src/lib/editor',studio+'/frontend/src/lib/md',studio+'/remote/voxarium_studio_remote',android+'/editor-web/src']) walk(p);
sourceFiles.push(studio+'/frontend/package-lock.json',android+'/editor-web/package-lock.json');
const hash = p => createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const manifest = Object.fromEntries(sourceFiles.filter(p=>!p.includes('__pycache__')).map(p=>[p.replaceAll('\\','/'),hash(p)]));
let child, vite, bridge, browser; const trace=[], errors=[]; let outcome;const allocatedPorts=[];
const sockets = new Set();
try {
  child = spawn(temp+'/venv/Scripts/python.exe', [here+'/serve.py',runData], { cwd: temp, windowsHide:true, env: {...process.env, PYTHONPATH:studio+'/remote',PYTHONDONTWRITEBYTECODE:'1',TEMP:temp+'/system-temp',TMP:temp+'/system-temp'} });
  let stdout=''; let stderr=''; child.stderr.on('data',d=>stderr+=d);
  const info = await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('Remote startup timeout: '+stderr)),30000); child.stdout.on('data',d=>{stdout+=d;const match=stdout.match(/HARNESS:(.*)\r?\n/);if(match){clearTimeout(timer);resolve(JSON.parse(match[1]));}});child.on('exit',code=>{clearTimeout(timer);reject(new Error('Remote exited '+code+': '+stderr));});});
  const url='http://127.0.0.1:'+info.port;
  allocatedPorts.push(info.port);
  for(let i=0;;i++){try { const h=await fetch(url+'/healthz'); if(h.ok) break; }catch{} if(i>100)throw Error('Remote health timeout');await new Promise(r=>setTimeout(r,50));}
  bridge = new WebSocketServer({host:'127.0.0.1',port:0,maxPayload:32*1024*1024}); await once(bridge,'listening');
  allocatedPorts.push(bridge.address().port);
  bridge.on('connection',(down,request)=>{
    const name=request.url.slice(1); if(!info.tokens[name]){down.close();return;}
    const channel=trace.filter(f=>f.kind==='connect').length;trace.push({kind:'connect',channel,name});
    const up=new WebSocket(url.replace('http','ws')+'/api/v1/sync?space=vs78-interop',{headers:{Authorization:'Bearer '+info.tokens[name]}});
    sockets.add(up);sockets.add(down); const pending=[];
    const record=(direction,data)=>{trace.push({channel,name,direction,frame:JSON.parse(data.toString())});};
    down.on('message',data=>{record('toRemote',data);if(up.readyState===WebSocket.OPEN)up.send(data.toString());else pending.push(data.toString());});
    up.on('open',()=>pending.splice(0).forEach(d=>up.send(d)));
    up.on('message',data=>{record('fromRemote',data);if(down.readyState===WebSocket.OPEN)down.send(data.toString());});
    down.on('close',()=>up.close());up.on('close',()=>down.close());
    up.on('error',e=>errors.push(String(e))); down.on('error',e=>errors.push(String(e)));
  });
  fs.copyFileSync(here+'/index.html',temp+'/deps/index.html');fs.copyFileSync(here+'/fixture.ts',temp+'/deps/fixture.ts');
  const pkg=JSON.parse(fs.readFileSync(temp+'/deps/package.json'));
  const allPackages=Object.keys(JSON.parse(fs.readFileSync(temp+'/deps/package-lock.json')).packages).filter(p=>p.startsWith('node_modules/')).map(p=>p.slice(13));
  vite=await createServer({configFile:false,root:temp+'/deps',cacheDir:temp+'/vite-cache',plugins:[vue()],resolve:{alias:{'@':studio+'/frontend/src','android-source':android+'/editor-web/src'},dedupe:allPackages},server:{host:'127.0.0.1',port:0,fs:{allow:[studio+'/frontend',android+'/editor-web',temp]}},optimizeDeps:{include:['vue','pinia','@tiptap/core','yjs','y-protocols/awareness']}});
  await vite.listen(); const port=vite.httpServer.address().port;
  allocatedPorts.push(port);
  browser=await chromium.launch({channel:'chrome',executablePath:process.env.CHROME_PATH,headless:true,args:['--disable-extensions']});
  const context=await browser.newContext();const page=await context.newPage();page.on('pageerror',e=>errors.push(e.stack||String(e)));page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
  await page.goto('http://127.0.0.1:'+port,{waitUntil:'networkidle'});
  await page.waitForFunction(()=>typeof window.runInterop==='function',null,{timeout:90000});
  const fixtures={...Object.fromEntries(fs.readdirSync(android+'/editor-web/tests/fixtures').filter(n=>n.endsWith('.md')).map(n=>['android/'+n,fs.readFileSync(android+'/editor-web/tests/fixtures/'+n,'utf8')])),...Object.fromEntries(fs.readdirSync(studio+'/frontend/src/lib/md/__fixtures__').filter(n=>n.endsWith('.md')).map(n=>['studio/'+n,fs.readFileSync(studio+'/frontend/src/lib/md/__fixtures__/'+n,'utf8')]))};
  outcome=await page.evaluate(async ({relay,fixtures})=>await window.runInterop(relay,fixtures),{relay:'ws://127.0.0.1:'+bridge.address().port,fixtures});
  fs.writeFileSync(evidence+'/color-regression.json',JSON.stringify(await page.evaluate(()=>window.colorRegression??null),null,2));
  await page.screenshot({path:evidence+'/browser.png',fullPage:true});
  const changed=Object.keys(manifest).filter(p=>hash(p)!==manifest[p]);
  fs.writeFileSync(evidence+'/sources.json',JSON.stringify({manifest,unchanged:changed.length===0,changed},null,2));
  fs.writeFileSync(evidence+'/harness.json',JSON.stringify(Object.fromEntries(['run.mjs','fixture.ts','serve.py','prepare.ps1','python-lock.txt','index.html'].map(n=>[n,hash(here+'/'+n)])),null,2));
  fs.writeFileSync(evidence+'/environment.json',JSON.stringify({date:new Date().toISOString(),node:process.version,browser:browser.version(),packages:Object.fromEntries(['vite','vue','yjs','@tiptap/core','@tiptap/y-tiptap','playwright','ws'].map(n=>[n,JSON.parse(fs.readFileSync(temp+'/deps/node_modules/'+n+'/package.json')).version])),remote:await(await fetch(url+'/healthz')).json(),ports:{remote:info.port,relay:bridge.address().port,vite:port},python:execFileSync(temp+'/venv/Scripts/python.exe',['-m','pip','freeze'],{encoding:'utf8'}),nativeTransportTested:false},null,2));
  if(changed.length)throw Error('Read-only source changed: '+changed);
} catch(e) { errors.push(e.stack||String(e)); }
finally {
  if(browser)await browser.close();
  for(const s of sockets)s.terminate();
  if(bridge)await new Promise(r=>bridge.close(r));
  if(vite)await vite.close();
  if(child&&child.exitCode===null){if(process.platform==='win32'){try{execFileSync('taskkill.exe',['/PID',String(child.pid),'/T','/F'],{windowsHide:true,stdio:'pipe'});}catch{child.kill();}}else child.kill();if(child.exitCode===null&&child.signalCode===null)await once(child,'exit');}
  if(fs.existsSync(runData+'/remote.db')){try{const snapshot=execFileSync(temp+'/venv/Scripts/python.exe',['-c',"import sqlite3,json,sys; c=sqlite3.connect(sys.argv[1]); c.row_factory=sqlite3.Row; print(json.dumps({k:[dict(r) for r in c.execute(q)] for k,q in {'tables':\"SELECT name,sql FROM sqlite_schema WHERE type='table'\",'spaces':'SELECT id,name,seq FROM spaces','documents':'SELECT path,rev,hash,deleted,content FROM docs ORDER BY path'}.items()},ensure_ascii=False))",runData+'/remote.db'],{encoding:'utf8',env:{...process.env,PYTHONIOENCODING:'utf-8'}});fs.writeFileSync(evidence+'/database.json',snapshot);}catch(e){errors.push('SQLite evidence failed: '+String(e));}}
  const portChecks=await Promise.all(allocatedPorts.map(async port=>{for(let i=0;i<50;i++){const closed=await new Promise(resolve=>{const s=net.createConnection({host:'127.0.0.1',port});s.once('connect',()=>{s.destroy();resolve(false)});s.once('error',()=>resolve(true));});if(closed)return{port,closed:true};await new Promise(r=>setTimeout(r,40));}return{port,closed:false};}));
  if(portChecks.some(p=>!p.closed))errors.push('An allocated test port is still listening');
  fs.writeFileSync(evidence+'/wire.json',JSON.stringify(trace,null,2));
  fs.writeFileSync(evidence+'/results.json',JSON.stringify({outcome,errors,cleanup:{remoteStopped:!child||child.exitCode!==null||child.signalCode!==null,relayClosed:!bridge||!bridge.address(),viteClosed:!vite||!vite.httpServer?.listening,portChecks},runData},null,2));
}
console.log(JSON.stringify({outcome,errors},null,2));
if(errors.length||!outcome||outcome.some(r=>r.status!=='PASS'))process.exitCode=1;
