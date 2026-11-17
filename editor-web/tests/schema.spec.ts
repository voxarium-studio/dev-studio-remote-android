/**
 * VS-72 / remote-android protocol §5: freeze the reviewed Studio room schema, not a
 * self-derived Android snapshot. schema-golden.json was captured from the original
 * frontend/src/lib/collab/seed.ts:roomSchema() with Studio's existing Vue-capable Vitest
 * loader in TASK-72, after comparison against the port. Its capture metadata records the
 * exact original source SHA-256 values, package-lock hash, versions and regeneration steps.
 *
 * Only DOM/clipboard presentation functions (toDOM, parseDOM, toText) are omitted. Every
 * remaining structural spec field/default must be JSON data; fail rather than silently
 * omit a future function-valued validator. Type order, mark ranks and attribute order are
 * explicit arrays/fields, so even insertion-order changes are caught. No Studio checkout,
 * Vue dependency, network access or automatic snapshot update is used by this test.
 */
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'

import { getSchema } from '@tiptap/core'
import type { Schema } from '@tiptap/pm/model'
import { describe, expect, it } from 'vitest'

import { roomSchema } from '../src/collab/seed'
import { createExtensions } from '../src/editor/extensions'

interface SourceHash { source: string; sha256: string }
interface Golden {
  format: number
  capture: { sourceRoot: string; sourceFiles: SourceHash[]; packages: Record<string, string> }
  schema: unknown
}
const golden = JSON.parse(readFileSync(new URL('./schema-golden.json', import.meta.url), 'utf8')) as Golden

function structuralSchema(schema: Schema) {
  function specs(types: Schema['nodes'] | Schema['marks'], marks: boolean) {
    return Object.entries(types).map(([name, type], order) => {
      const { toDOM, parseDOM, toText, ...spec } = type.spec
      const json: unknown = JSON.parse(JSON.stringify(spec, (key, value: unknown) => {
        if (typeof value === 'function' || value === undefined) {
          throw new Error(`Non-JSON structural spec: ${name}.${key}; review golden capture`)
        }
        return value
      }))
      return {
        name,
        order,
        // ProseMirror exposes rank at runtime but strips this @internal field from .d.ts.
        ...(marks ? { rank: (type as unknown as { rank: number }).rank } : {}),
        attributeOrder: Object.keys(spec.attrs ?? {}),
        spec: json,
      }
    })
  }
  return { nodes: specs(schema.nodes, false), marks: specs(schema.marks, true) }
}

describe('reviewed Studio room schema golden', () => {
  it('freezes exact structural node/mark specs, defaults, type order and mark ranks', () => {
    expect(golden.format).toBe(1)
    expect(structuralSchema(roomSchema())).toEqual(golden.schema)
    expect(structuralSchema(getSchema(createExtensions()))).toEqual(golden.schema)
  })

  it('matches original-source provenance and retains the exact carried plugin bytes', () => {
    const provenance = JSON.parse(readFileSync(new URL('../src/editor/provenance.json', import.meta.url), 'utf8')) as {
      sourceRoot: string; sources: (SourceHash & { destination: string })[]
    }
    expect(provenance.sourceRoot).toBe(golden.capture.sourceRoot)
    expect(provenance.sources).toHaveLength(5)
    for (const { source, sha256 } of provenance.sources) {
      expect(golden.capture.sourceFiles.find(row => row.source === source)).toEqual({ source, sha256 })
      expect(sha256).toMatch(/^[a-f0-9]{64}$/)
    }
    const carried = golden.capture.sourceFiles.find(row => row.source.endsWith('/collab/carried.ts'))!
    const actual = createHash('sha256').update(readFileSync(new URL('../src/collab/carried.ts', import.meta.url))).digest('hex')
    expect(actual).toBe(carried.sha256)
    expect(golden.capture.sourceFiles.find(row => row.source === 'frontend/package-lock.json')?.sha256)
      .toMatch(/^[a-f0-9]{64}$/)
  })

  it('runs against the same exact locally installed schema dependency versions', () => {
    for (const [name, version] of Object.entries(golden.capture.packages)) {
      const installed = JSON.parse(readFileSync(new URL(`../node_modules/${name}/package.json`, import.meta.url), 'utf8')) as {
        name: string; version: string
      }
      expect(installed.name).toBe(name)
      expect(installed.version).toBe(version)
    }
  })
})
