import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { cpSync, mkdirSync } from 'node:fs'
import { build } from 'vite'

const outDir = resolve(process.env.EDITOR_DIST_DIR ?? 'dist')
// Clear neither arbitrary directories nor project sources. Vite only writes the bundle.
execFileSync(process.execPath, [resolve('node_modules/typescript/bin/tsc'), '--project', 'tsconfig.build.json', '--outDir', outDir], { stdio: 'inherit' })
await build({ build: { outDir, emptyOutDir: false } })
// The APK copies ONLY this directory as editor assets. Classic JS avoids file/module
// resolution differences across WebView versions; all dependencies are bundled locally.
const roomDir = resolve(outDir, 'room')
await build({
  configFile: false,
  cacheDir: process.env.EDITOR_CACHE_DIR ?? '.cache',
  build: {
    outDir: roomDir, emptyOutDir: false, sourcemap: false,
    lib: { entry: resolve('src/main.ts'), name: 'VoxariumEditorPage', formats: ['iife'], fileName: () => 'editor.js', cssFileName: 'editor' },
  },
})
mkdirSync(roomDir, { recursive: true })
cpSync(resolve('src/page.html'), resolve(roomDir, 'index.html'))
for (const path of ['licenses', 'NOTICE', 'PROVENANCE.json']) cpSync(resolve(path), resolve(outDir, path), { recursive: true })
