import test from 'node:test'
import assert from 'node:assert/strict'
import { asciiUserAgent } from './userAgent.js'

const WINDOWS = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) 一方志/0.1.0 Chrome/144.0.0.0 Electron/44.7.0 Safari/537.36'

test('the Chinese app name is replaced by an English one and the rest is left as it was', () => {
  assert.equal(asciiUserAgent(WINDOWS, '0.1.1'),
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) YifangzhiDesktop/0.1.1 Chrome/144.0.0.0 Electron/44.7.0 Safari/537.36')
})

test('whatever the app is called, nothing outside plain ASCII is left in the identification', () => {
  for (const name of ['一方志', '一方志测试版', 'yifangzhi-desktop', 'Electron', '一方志 桌面端']) {
    const result = asciiUserAgent(WINDOWS.replace('一方志/0.1.0', `${name}/0.1.0`), '0.1.1')
    assert.match(result, /^[\x20-\x7E]+$/)
    assert.match(result, /\(KHTML, like Gecko\) YifangzhiDesktop\/0\.1\.1 Chrome\/144\.0\.0\.0 Electron\/44\.7\.0 Safari\/537\.36$/)
  }
})

test('an identification in a shape it does not recognise is still stripped of non-ASCII characters', () => {
  assert.equal(asciiUserAgent('SomethingElse 一方志/1.0 (custom)', '0.1.1'), 'SomethingElse /1.0 (custom)')
  assert.equal(asciiUserAgent(null, '0.1.1'), '')
})
