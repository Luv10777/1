import test from 'node:test'
import assert from 'node:assert/strict'
import { launchApp, onPhone } from './appLaunch.js'

const fakeBrowser = () => {
  const listeners = {}
  const on = (target) => ({
    addEventListener: (name, fn) => { listeners[`${target}:${name}`] = fn },
    removeEventListener: (name) => { delete listeners[`${target}:${name}`] },
  })
  const win = { ...on('win'), location: { href: 'https://yifangzhi.com/digital-human' } }
  const doc = { ...on('doc'), hidden: false }
  return { win, doc, listeners }
}

test('counts the page losing focus as the app having opened', async () => {
  const { win, doc, listeners } = fakeBrowser()
  const result = launchApp('yifangzhi://live?store=3', { timeout: 1000, win, doc })
  assert.equal(win.location.href, 'yifangzhi://live?store=3')
  listeners['win:blur']()
  assert.equal(await result, true)
  assert.deepEqual(Object.keys(listeners), [], 'stops listening once decided')
})

test('counts the page going to the background as the app having opened', async () => {
  const { win, doc, listeners } = fakeBrowser()
  const result = launchApp('yifangzhi://live', { timeout: 1000, win, doc })
  doc.hidden = true
  listeners['doc:visibilitychange']()
  assert.equal(await result, true)
})

test('treats nothing happening as the app not being installed', async () => {
  const { win, doc, listeners } = fakeBrowser()
  assert.equal(await launchApp('yifangzhi://live', { timeout: 10, win, doc }), false)
  assert.deepEqual(Object.keys(listeners), [])
})

test('phones and tablets get the download page instead of a launch attempt', () => {
  assert.equal(onPhone('Mozilla/5.0 (Linux; Android 14; ALI-AN00) Chrome/120 Mobile'), true)
  assert.equal(onPhone('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)'), true)
  assert.equal(onPhone('Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120'), false)
  assert.equal(onPhone('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Safari/605'), false)
})
