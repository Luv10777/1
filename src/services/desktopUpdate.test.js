import test from 'node:test'
import assert from 'node:assert/strict'
import { compareVersions, findDesktopUpdate, installedDesktop } from './desktopUpdate.js'

// The identification the desktop app sends, as seen on the live site.
const agent = (system, version) => `Mozilla/5.0 (${system}) AppleWebKit/537.36 (KHTML, like Gecko) YifangzhiDesktop/${version} Chrome/152.0.7977.130 Electron/44.7.0 Safari/537.36`
const WINDOWS = 'Windows NT 10.0; Win64; x64'
const MAC = 'Macintosh; Intel Mac OS X 10_15_7'
const BROWSER = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36'
const LATEST = {
  windows: { version: '0.1.3', url: '/downloads/yifangzhi-setup.exe' },
  mac: { version: '0.1.3', url: '/downloads/yifangzhi-mac.dmg' },
}
const site = (body, { ok = true, calls = [] } = {}) => async (url, options) => {
  calls.push({ url, cache: options?.cache })
  return { ok, json: async () => { if (body instanceof Error) throw body; return body } }
}

test('the installed version and system are read from the identification the app sends', () => {
  assert.deepEqual(installedDesktop(agent(WINDOWS, '0.1.1')), { version: '0.1.1', platform: 'windows' })
  assert.deepEqual(installedDesktop(agent(MAC, '0.1.2')), { version: '0.1.2', platform: 'mac' })
  assert.equal(installedDesktop(BROWSER), null)
  assert.equal(installedDesktop(agent('X11; Linux x86_64', '0.1.2')), null)
  assert.equal(installedDesktop(''), null)
  assert.equal(installedDesktop(undefined), null)
})

test('versions are compared number by number, not letter by letter', () => {
  assert.ok(compareVersions('0.1.10', '0.1.9') > 0)
  assert.ok(compareVersions('0.2.0', '0.10.0') < 0)
  assert.ok(compareVersions('1.0', '0.9.9') > 0)
  assert.equal(compareVersions('0.1.3', '0.1.3'), 0)
  assert.equal(compareVersions('0.1', '0.1.0'), 0)
})

test('an older install is told about the newer version for its own system', async () => {
  const calls = []
  assert.deepEqual(await findDesktopUpdate({ userAgent: agent(WINDOWS, '0.1.1'), fetch: site(LATEST, { calls }) }), { version: '0.1.3', current: '0.1.1', platform: 'windows', url: '/downloads/yifangzhi-setup.exe' })
  assert.deepEqual(calls, [{ url: '/downloads/latest.json', cache: 'no-store' }])
  assert.deepEqual(await findDesktopUpdate({ userAgent: agent(MAC, '0.1.2'), fetch: site(LATEST) }), { version: '0.1.3', current: '0.1.2', platform: 'mac', url: '/downloads/yifangzhi-mac.dmg' })
})

test('an install that is current, or ahead of what is published, is left alone', async () => {
  assert.equal(await findDesktopUpdate({ userAgent: agent(MAC, '0.1.3'), fetch: site(LATEST) }), null)
  assert.equal(await findDesktopUpdate({ userAgent: agent(MAC, '0.2.0'), fetch: site(LATEST) }), null)
  // Each system has its own version: a newer Mac build says nothing about Windows.
  assert.equal(await findDesktopUpdate({ userAgent: agent(WINDOWS, '0.1.3'), fetch: site({ ...LATEST, mac: { version: '0.1.4', url: '/downloads/yifangzhi-mac.dmg' } }) }), null)
})

test('an ordinary browser is never asked to update, and the site is not even queried', async () => {
  const calls = []
  assert.equal(await findDesktopUpdate({ userAgent: BROWSER, fetch: site(LATEST, { calls }) }), null)
  assert.deepEqual(calls, [])
})

test('no reminder when nothing usable is published', async () => {
  const from = { userAgent: agent(WINDOWS, '0.1.1') }
  assert.equal(await findDesktopUpdate({ ...from, fetch: site(LATEST, { ok: false }) }), null)
  // A dev server answers with the app's own page, which is not JSON.
  assert.equal(await findDesktopUpdate({ ...from, fetch: site(new SyntaxError('Unexpected token <')) }), null)
  assert.equal(await findDesktopUpdate({ ...from, fetch: async () => { throw new Error('offline') } }), null)
  assert.equal(await findDesktopUpdate({ ...from, fetch: site(null) }), null)
  assert.equal(await findDesktopUpdate({ ...from, fetch: site({ mac: LATEST.mac }) }), null)
  assert.equal(await findDesktopUpdate({ ...from, fetch: site({ windows: { version: 'latest', url: '/downloads/yifangzhi-setup.exe' } }) }), null)
  assert.equal(await findDesktopUpdate({ ...from, fetch: site({ windows: { version: '0.1.3' } }) }), null)
})

test('the download must be a file in the site\'s own downloads folder', async () => {
  const from = { userAgent: agent(WINDOWS, '0.1.1') }
  for (const url of ['https://evil.example.com/setup.exe', '//evil.example.com/setup.exe', '/downloads/../api/x', '/downloads/a/b.exe', 'javascript:alert(1)', '/other/yifangzhi-setup.exe']) {
    assert.equal(await findDesktopUpdate({ ...from, fetch: site({ windows: { version: '0.1.3', url } }) }), null, url)
  }
})
