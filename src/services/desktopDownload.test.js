import test from 'node:test'
import assert from 'node:assert/strict'
import { findDesktopDownload } from './desktopDownload.js'

const answer = (status, type) => {
  const calls = []
  const fetch = async (url, options) => { calls.push({ url, method: options?.method }); return { ok: status >= 200 && status < 300, status, headers: { get: () => type } } }
  return { fetch, calls }
}

test('the installer on the site itself is offered when it is really there', async () => {
  const { fetch, calls } = answer(200, 'application/octet-stream')
  assert.equal(await findDesktopDownload({ configured: '', fetch }), '/downloads/yifangzhi-setup.exe')
  assert.deepEqual(calls, [{ url: '/downloads/yifangzhi-setup.exe', method: 'HEAD' }])
})

test('no installer, no entry: a missing file, the page served in its place, or a failed request', async () => {
  assert.equal(await findDesktopDownload({ configured: '', fetch: answer(404, 'text/html').fetch }), '')
  // A dev server answers any unknown address with the app's own page.
  assert.equal(await findDesktopDownload({ configured: '', fetch: answer(200, 'text/html; charset=utf-8').fetch }), '')
  assert.equal(await findDesktopDownload({ configured: '', fetch: async () => { throw new Error('offline') } }), '')
})

test('an address set at build time is used as given, without asking the site', async () => {
  const { fetch, calls } = answer(404, 'text/html')
  assert.equal(await findDesktopDownload({ configured: 'https://cdn.example.com/yifangzhi.exe', fetch }), 'https://cdn.example.com/yifangzhi.exe')
  assert.deepEqual(calls, [])
})
