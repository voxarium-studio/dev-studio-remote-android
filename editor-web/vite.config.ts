import { defineConfig } from 'vitest/config'
export default defineConfig({
  cacheDir: process.env.EDITOR_CACHE_DIR ?? '.cache',
  build: { lib: { entry: 'src/index.ts', formats: ['es'], fileName: 'editor-model' }, sourcemap: true },
  test: { include: ['tests/**/*.spec.ts'], environment: 'node' },
})
