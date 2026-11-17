import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
const here=path.dirname(fileURLToPath(import.meta.url))
const temp=process.env.VS78_TEMP || path.resolve(here, '../../../VoxariumStudio/.voxarium_studio/temp/TASK-78/audit/color')
const deps=process.env.INTEROP_DEPS || path.resolve(here, '../../../VoxariumStudio/.voxarium_studio/temp/TASK-78/interop/deps')
const studio=process.env.STUDIO_ROOT || path.resolve(here, '../../../VoxariumStudio')
const android=process.env.ANDROID_ROOT || path.resolve(here, '../..')
fs.mkdirSync(temp,{recursive:true});process.env.TEMP=temp;process.env.TMP=temp
// Run interop/prepare.ps1 first. Only reuses its pinned disposable dependencies.
const req=createRequire(deps+'/package.json')
const {createServer}=await import(pathToFileURL(req.resolve('vite')).href)
const vue=(await import(pathToFileURL(req.resolve('@vitejs/plugin-vue')).href)).default
const {chromium}=req('playwright')
if(!fs.existsSync(temp+'/node_modules'))fs.symlinkSync(deps+'/node_modules',temp+'/node_modules','junction')
fs.copyFileSync(here+'/color-stability.ts',temp+'/fixture.ts')
fs.writeFileSync(temp+'/index.html','<!doctype html><body><script type="module" src="/fixture.ts"></script>')
const dedupe=Object.keys(JSON.parse(fs.readFileSync(deps+'/package-lock.json')).packages).filter(p=>p.startsWith('node_modules/')).map(p=>p.slice(13))
let server,browser
try {
  server=await createServer({configFile:false,root:temp,cacheDir:temp+'/cache',plugins:[vue()],
    resolve:{alias:{'@':studio+'/frontend/src','android-source':android+'/editor-web/src'},dedupe},
    server:{host:'127.0.0.1',port:0,watch:{ignored:['**/playwright*/**']},fs:{allow:[temp,deps,studio+'/frontend',android+'/editor-web']}}})
  await server.listen()
  browser=await chromium.launch({channel:'chrome',executablePath:process.env.CHROME_PATH,headless:true})
  const page=await browser.newPage();page.on('pageerror',e=>console.error(e))
  await page.goto('http://127.0.0.1:'+server.httpServer.address().port)
  await page.waitForFunction(()=>typeof window.probe==='function')
  const result=await page.evaluate(()=>window.probe())
  fs.writeFileSync(here+'/color-stability.json',JSON.stringify(result,null,2)+'\n')
  console.log(JSON.stringify(result,null,2))
  process.exitCode=result.every(x=>x.semanticStable&&x.bytesStableAfterFirst)?0:1
} finally {await browser?.close();await server?.close()}
