import test from 'node:test'
import assert from 'node:assert/strict'
import { downloadMedia } from './mediaDownload.js'

test('media downloads save a blob without forwarding application credentials and clean up afterward', async t => {
  const savedDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
  const links = [], removed = [], revoked = [], requests = []
  const blob = new Blob(['video'], { type: 'video/mp4' })
  let response = { ok: true, blob: async () => blob }
  t.mock.method(globalThis, 'fetch', async (url, options) => { requests.push({ url, options }); return response })
  t.mock.method(URL, 'createObjectURL', value => { assert.equal(value, blob); return 'blob:local-download' })
  t.mock.method(URL, 'revokeObjectURL', url => revoked.push(url))
  t.mock.timers.enable({ apis: ['setTimeout'] })
  globalThis.document = {
    body: { appendChild() {} },
    createElement: () => ({ click() { links.push({ href: this.href, filename: this.download }) }, remove() { removed.push(this) } }),
  }
  try {
    await downloadMedia('https://bucket.test/video.mp4?signature=test', '一方志视频.mp4')
    assert.deepEqual(requests, [{ url: 'https://bucket.test/video.mp4?signature=test', options: { cache: 'no-store' } }])
    assert.deepEqual(links, [{ href: 'blob:local-download', filename: '一方志视频.mp4' }])
    assert.equal(removed.length, 1)
    assert.deepEqual(revoked, [], 'allow the browser time to start saving the file')
    t.mock.timers.tick(60_000)
    assert.deepEqual(revoked, ['blob:local-download'])

    response = { ok: false, status: 403 }
    await assert.rejects(downloadMedia('https://bucket.test/expired.mp4', 'video.mp4'), /已过期/)
    assert.equal(links.length, 1, 'an expired URL must not download an error response')
    response = { ok: true, blob: async () => { throw new Error('连接中断') } }
    await assert.rejects(downloadMedia('https://bucket.test/broken.mp4', 'video.mp4'), /连接中断/)
    assert.equal(links.length, 1)
  } finally {
    if (savedDocument) Object.defineProperty(globalThis, 'document', savedDocument)
    else delete globalThis.document
  }
})
