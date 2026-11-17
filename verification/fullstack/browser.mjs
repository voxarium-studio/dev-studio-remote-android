// Loopback-only test service. Native commands/events are encoded/validated by production Kotlin codec.
// Credentials are only held here for the reference Studio's authenticated Remote socket adapter.
import fs from 'node:fs';import path from 'node:path';import http from 'node:http';import net from 'node:net';
import {createRequire} from 'node:module';import {fileURLToPath,pathToFileURL} from 'node:url';import {once} from 'node:events';
const here=path.dirname(fileURLToPath(import.meta.url)),config=JSON.parse(fs.readFileSync(process.argv[2])),run=config.run;
const studio=process.env.STUDIO_ROOT||path.resolve(here, '../../../VoxariumStudio'),android=process.env.ANDROID_ROOT||path.resolve(here, '../..');
const prepared=path.dirname(run)+'/deps';
const deps=process.env.FULLSTACK_DEPS||(fs.existsSync(prepared+'/node_modules')?prepared:path.resolve(here, '../../../VoxariumStudio/.voxarium_studio/temp/TASK-78/interop/deps'));
const req=createRequire(deps+'/package.json'),{chromium}=req('playwright'),{WebSocket,WebSocketServer}=req('ws');
const {createServer}=await import(pathToFileURL(req.resolve('vite')).href),vue=(await import(pathToFileURL(req.resolve('@vitejs/plugin-vue')).href)).default;
const fixture=run+'/fixture';fs.mkdirSync(fixture,{recursive:true});
for(const n of ['fixture.ts','index.html'])fs.copyFileSync(here+'/'+n,fixture+'/'+n);
fs.symlinkSync(deps+'/node_modules',fixture+'/node_modules','junction');
const traces=[],errors=[],sockets=new Set();let vite,browser,service,relay;const ports=[];
async function stop(){
  fs.writeFileSync(run+'/browser-wire.json',JSON.stringify(traces,null,2));fs.writeFileSync(run+'/browser-errors.json',JSON.stringify(errors,null,2));
  if(browser)await browser.close();for(const s of sockets)s.terminate();if(relay)await new Promise(r=>relay.close(r));if(service)await new Promise(r=>service.close(r));if(vite)await vite.close();
  const checks=await Promise.all(ports.map(port=>new Promise(resolve=>{const s=net.createConnection({host:'127.0.0.1',port});s.once('connect',()=>{s.destroy();resolve({port,closed:false})});s.once('error',()=>resolve({port,closed:true}));})));
  fs.writeFileSync(run+'/browser-cleanup.json',JSON.stringify(checks));
}
try{
  relay=new WebSocketServer({host:'127.0.0.1',port:0,maxPayload:32*1024*1024});await once(relay,'listening');ports.push(relay.address().port);
  relay.on('connection',(down,request)=>{
    const space=new URL(request.url,'http://localhost').searchParams.get('space');
    const up=new WebSocket(config.url.replace('https:','wss:')+'/api/v1/sync?space='+encodeURIComponent(space),{headers:{Authorization:'Bearer '+config.bob},ca:fs.readFileSync(config.cert)});
    sockets.add(up);sockets.add(down);const pending=[];
    down.on('message',d=>{traces.push({direction:'toRemote',frame:JSON.parse(d)});if(up.readyState===1)up.send(d.toString());else pending.push(d.toString())});up.on('open',()=>pending.splice(0).forEach(d=>up.send(d)));
    up.on('message',d=>{traces.push({direction:'fromRemote',frame:JSON.parse(d)});if(down.readyState===1)down.send(d.toString())});up.on('close',()=>down.close());down.on('close',()=>up.close());up.on('error',e=>errors.push(String(e)));
  });
  const packages=Object.keys(JSON.parse(fs.readFileSync(deps+'/package-lock.json')).packages).filter(p=>p.startsWith('node_modules/')).map(p=>p.slice(13));
  vite=await createServer({configFile:false,root:fixture,cacheDir:run+'/vite-cache',plugins:[vue()],resolve:{alias:{'@':studio+'/frontend/src','android-source':android+'/editor-web/src'},dedupe:packages},server:{host:'127.0.0.1',port:0,fs:{allow:[studio+'/frontend',android+'/editor-web',run,deps]}},optimizeDeps:{include:['vue','pinia','@tiptap/core','yjs','y-protocols/awareness']}});
  await vite.listen();ports.push(vite.httpServer.address().port);
  browser=await chromium.launch({headless:true,channel:'chrome',executablePath:process.env.CHROME_PATH,args:['--disable-extensions']});const page=await browser.newPage();page.on('pageerror',e=>errors.push(e.stack||String(e)));
  await page.goto('http://127.0.0.1:'+vite.httpServer.address().port);await page.waitForFunction(()=>!!window.gate,null,{timeout:90000});
  let chain=Promise.resolve();service=http.createServer((request,response)=>{chain=chain.then(async()=>{
    try{let raw='';for await(const b of request)raw+=b;const payload=raw?JSON.parse(raw):{};let result;
      if(request.url==='/command')result=await page.evaluate(c=>window.gate.command(c),payload);
      else if(request.url==='/events')result=await page.evaluate(()=>window.gate.events());
      else if(request.url==='/action'){if(payload.op==='openStudio')payload.relay='ws://127.0.0.1:'+relay.address().port+'/?space='+encodeURIComponent(payload.space);result=await page.evaluate(a=>window.gate.action(a),payload)}
      else throw Error('Unknown endpoint');response.writeHead(200,{'Content-Type':'application/json'});response.end(JSON.stringify(result));
    }catch(e){errors.push(e.stack||String(e));response.writeHead(500);response.end(JSON.stringify({error:String(e)}))}
  })});service.listen(0,'127.0.0.1');await once(service,'listening');ports.push(service.address().port);
  fs.writeFileSync(run+'/browser-service.json',JSON.stringify({url:'http://127.0.0.1:'+service.address().port,browser:browser.version(),node:process.version,deps,playwright:req('playwright/package.json').version,vite:req('vite/package.json').version}));
  console.log('BROWSER_READY');
  await new Promise(resolve=>{process.stdin.resume();process.stdin.once('data',resolve);process.once('SIGTERM',resolve)});
  await page.screenshot({path:run+'/browser.png',fullPage:true});
}catch(e){errors.push(e.stack||String(e));process.exitCode=1}finally{await stop()}
