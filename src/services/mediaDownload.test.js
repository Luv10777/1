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

test('desktop Chrome and Edge open a save-as dialog before fetching the file', async t => {
  const saved = ['window', 'navigator'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)])
  const steps = [], written = []
  const blob = new Blob(['video'], { type: 'video/mp4' })
  t.mock.method(globalThis, 'fetch', async () => { steps.push('fetch'); return { ok: true, blob: async () => blob } })
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { userAgent: 'Mozilla/5.0 (Windows NT 10.0) Chrome/130' } })
  let cancel = false
  globalThis.window = { showSaveFilePicker: async options => {
    steps.push('picker:' + options.suggestedName)
    if (cancel) throw Object.assign(new Error('cancelled'), { name: 'AbortError' })
    return { createWritable: async () => ({ write: async value => written.push(value), close: async () => steps.push('closed') }) }
  } }
  try {
    await downloadMedia('https://bucket.test/video.mp4', '一方志视频.mp4')
    assert.deepEqual(steps, ['picker:一方志视频.mp4', 'fetch', 'closed'])
    assert.deepEqual(written, [blob])
    cancel = true; steps.length = 0
    await downloadMedia('https://bucket.test/video.mp4', '一方志视频.mp4')
    assert.deepEqual(steps, ['picker:一方志视频.mp4'], 'cancelling the dialog downloads nothing')
  } finally {
    for (const [key, descriptor] of saved) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key] }
  }
})

test('Apple devices open the share sheet so the file can go to Photos', async t => {
  const saved = ['window', 'navigator'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)])
  const shared = []
  t.mock.method(globalThis, 'fetch', async () => ({ ok: true, blob: async () => new Blob(['video'], { type: 'video/mp4' }) }))
  let refuse = true
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Safari/604.1',
    canShare: data => data.files?.length === 1,
    share: async data => {
      if (refuse) { refuse = false; throw Object.assign(new Error('no gesture'), { name: 'NotAllowedError' }) }
      shared.push(data.files[0])
    },
  } })
  globalThis.window = {}
  try {
    await assert.rejects(downloadMedia('https://bucket.test/video.mp4', '一方志视频.mp4'), /再点一次/, 'a slow download loses the tap; ask for one more')
    await downloadMedia('https://bucket.test/video.mp4', '一方志视频.mp4')
    assert.equal(shared.length, 1)
    assert.equal(shared[0].name, '一方志视频.mp4')
    assert.equal(shared[0].type, 'video/mp4')
    assert.equal(fetch.mock.callCount(), 1, 'the second tap reuses the file already fetched')
  } finally {
    for (const [key, descriptor] of saved) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key] }
  }
})
