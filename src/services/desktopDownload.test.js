import test from 'node:test'
import assert from 'node:assert/strict'
import { findDesktopDownloads } from './desktopDownload.js'

const WINDOWS = '/downloads/yifangzhi-setup.exe'
const MAC = '/downloads/yifangzhi-mac.dmg'
const NONE = { windows: '', mac: '' }

// What the site answers for each address; anything not listed is missing.
const site = (present) => {
  const calls = []
  const fetch = async (url, options) => {
    calls.push({ url, method: options?.method })
    const [status, type] = present[url] || [404, 'text/html']
    return { ok: status >= 200 && status < 300, status, headers: { get: () => type } }
  }
  return { fetch, calls }
}

test('each installer on the site itself is offered when it is really there', async () => {
  const { fetch, calls } = site({ [WINDOWS]: [200, 'application/octet-stream'], [MAC]: [200, 'application/x-apple-diskimage'] })
  assert.deepEqual(await findDesktopDownloads({ configured: NONE, fetch }), { windows: WINDOWS, mac: MAC })
  assert.deepEqual(calls, [{ url: WINDOWS, method: 'HEAD' }, { url: MAC, method: 'HEAD' }])
})

test('one system having no installer does not hide the other', async () => {
  assert.deepEqual(await findDesktopDownloads({ configured: NONE, fetch: site({ [WINDOWS]: [200, 'application/octet-stream'] }).fetch }), { windows: WINDOWS, mac: '' })
  assert.deepEqual(await findDesktopDownloads({ configured: NONE, fetch: site({ [MAC]: [200, 'application/x-apple-diskimage'] }).fetch }), { windows: '', mac: MAC })
})

test('no installer, no entry: a missing file, the page served in its place, or a failed request', async () => {
  assert.deepEqual(await findDesktopDownloads({ configured: NONE, fetch: site({}).fetch }), NONE)
  // A dev server answers any unknown address with the app's own page.
  const page = [200, 'text/html; charset=utf-8']
  assert.deepEqual(await findDesktopDownloads({ configured: NONE, fetch: site({ [WINDOWS]: page, [MAC]: page }).fetch }), NONE)
  assert.deepEqual(await findDesktopDownloads({ configured: NONE, fetch: async () => { throw new Error('offline') } }), NONE)
})

test('an address set at build time is used as given, without asking the site about that installer', async () => {
  const { fetch, calls } = site({})
  assert.deepEqual(
    await findDesktopDownloads({ configured: { windows: 'https://cdn.example.com/yifangzhi.exe', mac: '' }, fetch }),
    { windows: 'https://cdn.example.com/yifangzhi.exe', mac: '' },
  )
  assert.deepEqual(calls, [{ url: MAC, method: 'HEAD' }])
})
