import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

test('downloading an image skips the cached copy the preview <img> left behind', async () => {
  const vite = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
  const saved = { fetch: globalThis.fetch, document: globalThis.document, createObjectURL: URL.createObjectURL, revokeObjectURL: URL.revokeObjectURL }
  const calls = [], clicked = []
  try {
    const { downloadImage } = await vite.ssrLoadModule('/src/services/imageCreation.js')
    globalThis.fetch = async (url, options) => { calls.push({ url, options }); return { ok: true, blob: async () => new Blob(['png']) } }
    URL.createObjectURL = () => 'blob:image-test'
    URL.revokeObjectURL = () => {}
    globalThis.document = { body: { appendChild() {} }, createElement: () => ({ click() { clicked.push(this.download) }, remove() {} }) }
    await downloadImage('https://bucket.test/a.png?sign=1', '一方志-1-1.png')
    assert.deepEqual(calls, [{ url: 'https://bucket.test/a.png?sign=1', options: { cache: 'no-store' } }])
    assert.deepEqual(clicked, ['一方志-1-1.png'])
  } finally {
    globalThis.fetch = saved.fetch
    globalThis.document = saved.document
    URL.createObjectURL = saved.createObjectURL
    URL.revokeObjectURL = saved.revokeObjectURL
    await vite.close()
  }
})
