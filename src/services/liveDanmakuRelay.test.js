import test from 'node:test'
import assert from 'node:assert/strict'
import { LiveDanmakuRelay, parseDanmakuMessage } from './liveDanmakuRelay.js'

const chat = (msgId, content) => ({ method: 'WebcastChatMessage', common: { msgId }, content })
const status = (code, live, statusText) => ({ type: 'system', event: 'live_status', code, live, status_text: statusText })
const settle = async () => { for (let i = 0; i < 5; i++) await Promise.resolve() }

/** Stands in for window.yifangzhiDesktop.danmaku. */
function fakeBridge() {
  const bridge = {
    started: [], stops: 0, listeners: new Set(), answer: null,
    start(roomId) { bridge.started.push(roomId); return new Promise((resolve, reject) => { bridge.answer = { resolve, reject } }) },
    stop() { bridge.stops += 1; return Promise.resolve() },
    onMessage(listener) { bridge.listeners.add(listener); return () => bridge.listeners.delete(listener) },
    push(message) { for (const listener of [...bridge.listeners]) listener(typeof message === 'string' ? message : JSON.stringify(message)) },
  }
  return bridge
}

function harness(t, options = {}) {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
  const bridge = fakeBridge()
  const chats = []
  const states = []
  const relay = new LiveDanmakuRelay({ bridge, roomId: '850050208045', onChat: comment => chats.push(comment), onChange: state => states.push(state), ...options })
  return { relay, bridge, chats, states }
}

test('only viewer text and room status are read; anything else the app might send is passed over', () => {
  assert.deepEqual(parseDanmakuMessage(JSON.stringify(chat('7301', '  多少钱  '))), { kind: 'chat', id: '7301', text: '多少钱' })
  assert.deepEqual(parseDanmakuMessage(JSON.stringify(status('ROOM_ONLINE', true, '直播间已开播，正在接收弹幕'))),
    { kind: 'status', code: 'ROOM_ONLINE', live: true, text: '直播间已开播，正在接收弹幕' })
  assert.equal(parseDanmakuMessage(JSON.stringify({ method: 'WebcastGiftMessage', gift: { name: '小心心' } })), null)
  assert.equal(parseDanmakuMessage(JSON.stringify(chat('7302', '   '))), null)
  assert.equal(parseDanmakuMessage('not json'), null)
  assert.equal(parseDanmakuMessage(JSON.stringify({ type: 'system', event: 'other' })), null)
  assert.equal(parseDanmakuMessage(JSON.stringify(chat('7303', '长'.repeat(600)))).text.length, 500)
})

test('the app is asked to read the room, and what it reports first is not lost', async (t) => {
  const { relay, bridge } = harness(t)
  relay.start()
  await settle()
  assert.deepEqual(bridge.started, ['850050208045'])
  assert.equal(relay.snapshot().status, 'connecting')

  // The app reports before it has answered that it started.
  bridge.push(status('OPENING', false, '正在打开直播间网页…'))
  assert.deepEqual([relay.snapshot().status, relay.snapshot().message], ['waiting', '正在打开直播间网页…'])
  bridge.answer.resolve()
  await settle()
  bridge.push(status('ROOM_OFFLINE', false, '直播间未开播'))
  assert.deepEqual([relay.snapshot().status, relay.snapshot().message], ['waiting', '直播间未开播'])
  bridge.push(status('ROOM_ONLINE', true, '直播间已开播，正在接收弹幕'))
  assert.equal(relay.snapshot().status, 'live')
})

test('a comment is handed over once, however many times it is delivered', async (t) => {
  const { relay, bridge, chats } = harness(t)
  relay.start()
  bridge.answer.resolve()
  await settle()
  bridge.push(status('ROOM_ONLINE', true, '直播间已开播，正在接收弹幕'))
  bridge.push(chat('1', '有货吗'))
  t.mock.timers.tick(300)
  bridge.push(chat('1', '有货吗'))
  bridge.push({ method: 'WebcastGiftMessage' })

  assert.deepEqual(chats, [{ id: '1', text: '有货吗' }])
  assert.deepEqual(relay.snapshot(), { status: 'live', message: '直播间已开播，正在接收弹幕', received: 2, forwarded: 1, skipped: 1 })
})

test('a flood is thinned out: comments closer together than the minimum interval are dropped', async (t) => {
  const { relay, bridge, chats } = harness(t)
  relay.start()
  bridge.answer.resolve()
  await settle()
  bridge.push(chat('1', '第一条'))
  bridge.push(chat('2', '紧跟着的'))
  t.mock.timers.tick(299)
  bridge.push(chat('3', '还是太快'))
  t.mock.timers.tick(1)
  bridge.push(chat('4', '隔够了'))

  assert.deepEqual(chats.map(item => item.id), ['1', '4'])
  assert.equal(relay.snapshot().skipped, 2)
  // 没等到状态通知也算开播：弹幕已经在来了。
  assert.equal(relay.snapshot().status, 'live')
})

test('a comment that was dropped for coming too fast can still go on if it is delivered again later', async (t) => {
  const { relay, bridge, chats } = harness(t)
  relay.start()
  bridge.answer.resolve()
  await settle()
  bridge.push(chat('1', '第一条'))
  bridge.push(chat('2', '太快'))
  t.mock.timers.tick(300)
  bridge.push(chat('2', '太快'))
  assert.deepEqual(chats.map(item => item.id), ['1', '2'])
})

test('a comment without an id still goes on, since there is nothing to tell a repeat by', async (t) => {
  const { relay, bridge, chats } = harness(t)
  relay.start()
  bridge.answer.resolve()
  await settle()
  bridge.push({ method: 'WebcastChatMessage', content: '在吗' })
  t.mock.timers.tick(300)
  bridge.push({ method: 'WebcastChatMessage', content: '在吗' })
  assert.equal(chats.length, 2)
  assert.notEqual(chats[0].id, chats[1].id)
})

test('when the app cannot start reading, the reason it gives is shown and nothing is left listening', async (t) => {
  const { relay, bridge } = harness(t)
  relay.start()
  bridge.answer.reject(new Error('没有认出直播间：请粘贴抖音 App 里“分享 → 复制链接”得到的整段内容'))
  await settle()
  assert.equal(relay.snapshot().status, 'failed')
  assert.match(relay.snapshot().message, /没有认出直播间/)
  assert.equal(bridge.listeners.size, 0)
  // 失败后可以再试。
  relay.start()
  await settle()
  assert.equal(bridge.started.length, 2)
})

test('stopping tells the app to stop, and nothing is handed over afterwards', async (t) => {
  const { relay, bridge, chats } = harness(t)
  relay.start()
  bridge.answer.resolve()
  await settle()
  relay.stop()
  bridge.push(chat('1', '停了以后的'))

  assert.equal(bridge.stops, 1)
  assert.equal(bridge.listeners.size, 0)
  assert.deepEqual(chats, [])
  assert.equal(relay.snapshot().status, 'idle')
})

test('stopping before the app has answered wins: a late failure does not bring the error back', async (t) => {
  const { relay, bridge } = harness(t)
  relay.start()
  relay.stop()
  bridge.answer.reject(new Error('boom'))
  await settle()
  assert.equal(relay.snapshot().status, 'idle')
  assert.equal(bridge.stops, 1)
})
