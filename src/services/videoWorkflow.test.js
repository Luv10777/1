import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

test('video reference uploads wait for verification and preserve uploads on failure', async t => {
  const previousFetch = globalThis.fetch
  const vite = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true },
    server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const { uploadVideoReference } = await vite.ssrLoadModule('/src/services/videoWorkflow.js')
  const response = data => ({ ok: true, status: 200, json: async () => ({ code: 200, data }) })
  const settle = () => new Promise(resolve => setImmediate(resolve))
  try {
    await t.test('a newly uploaded video is retained while verification runs', async () => {
      const entry = { name: 'reference.mp4', file: new File(['video'], 'reference.mp4', { type: 'video/mp4' }) }
      const calls = [], states = []
      let complete
      globalThis.fetch = async (url, options = {}) => {
        calls.push({ url, options })
        if (url === '/api/assets/upload-url') return response({ assetId: 17, uploadUrl: '/storage/17' })
        if (options.method === 'PUT') return { ok: true }
        if (url === '/api/assets/17/confirm') return response({ id: 17, status: 'VERIFYING' })
        assert.equal(url, '/api/assets/17')
        return new Promise(resolve => { complete = () => resolve(response({ id: 17, status: 'READY' })) })
      }
      let finished = false
      const pending = uploadVideoReference(entry, 'VIDEO', { onStatus: asset => states.push(asset.status) }).then(id => { finished = true; return id })
      await settle()
      assert.equal(entry.assetId, 17)
      assert.equal(finished, false)
      assert.deepEqual(states, ['VERIFYING'])
      assert.equal(calls.find(call => call.options.method === 'PUT').options.headers.Authorization, undefined)
      complete()
      assert.equal(await pending, 17)
      assert.deepEqual(states, ['VERIFYING', 'READY'])
    })
    await t.test('an invalid cached video reports the validation failure without uploading again', async () => {
      const entry = { assetId: 18, name: 'invalid.mp4' }
      globalThis.fetch = async url => {
        assert.equal(url, '/api/assets/18')
        return response({ id: 18, status: 'INVALID' })
      }
      await assert.rejects(uploadVideoReference(entry, 'VIDEO'), /未通过校验/)
      assert.equal(entry.assetId, 18)
    })
    await t.test('verification is polled and a timeout retains the asset for a successful retry', async t => {
      t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
      const entry = { assetId: 19, name: 'slow.mp4' }
      let ready = false, reads = 0
      globalThis.fetch = async url => {
        assert.equal(url, '/api/assets/19')
        reads++
        return response({ id: 19, status: ready ? 'READY' : 'VERIFYING' })
      }
      const pending = assert.rejects(uploadVideoReference(entry, 'VIDEO'), /仍在校验中.*已上传的素材会保留/)
      await settle()
      assert.equal(reads, 1)
      t.mock.timers.tick(1000)
      await settle()
      assert.equal(reads, 2)
      t.mock.timers.tick(119000)
      await pending
      assert.equal(entry.assetId, 19)
      ready = true
      assert.equal(await uploadVideoReference(entry, 'VIDEO'), 19)
    })
    await t.test('leaving the workbench stops further status polling', async t => {
      t.mock.timers.enable({ apis: ['setTimeout'] })
      const controller = new AbortController()
      let reads = 0
      globalThis.fetch = async (_url, options) => {
        assert.equal(options.signal, controller.signal)
        reads++
        return response({ id: 20, status: 'VERIFYING' })
      }
      const pending = assert.rejects(uploadVideoReference({ assetId: 20 }, 'VIDEO', { signal: controller.signal }), { name: 'AbortError' })
      await settle()
      controller.abort()
      t.mock.timers.tick(1000)
      await pending
      assert.equal(reads, 1)
    })
  } finally {
    globalThis.fetch = previousFetch
    await vite.close()
  }
})
