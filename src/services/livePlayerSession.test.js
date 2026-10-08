import test from 'node:test'
import assert from 'node:assert/strict'
import { LivePlayerSession } from './livePlayerSession.js'

const flush = () => new Promise(resolve => setImmediate(resolve))
const clip = id => ({ id, mode: 'APPEND', text: id, audioUrl: `/api/player/audio/${id}`, durationMillis: 1000, pauseOffsetsMillis: [] })

class FakeSocket {
  constructor(url) { this.url = url; this.readyState = 0; this.sent = []; this.closed = null }
  open() { this.readyState = 1; this.onopen?.() }
  receive(message) { this.onmessage?.({ data: JSON.stringify(message) }) }
  send(data) { this.sent.push(JSON.parse(data)) }
  close(code, reason) { this.closed = { code, reason }; this.readyState = 3 }
  drop(code = 1006) { this.readyState = 3; this.onclose?.({ code }) }
}

function harness(t, { issueToken } = {}) {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'] })
  const sockets = []
  const states = []
  const engine = {
    queued: [], paused: false, unlocked: 0, destroyed: false,
    async unlock() { this.unlocked += 1; this.paused = false },
    enqueue(command) { this.queued.push(command.id) },
    async pause() { this.paused = true },
    async resume() { this.paused = false },
    setVolume(value) { this.volume = value },
    retry() {},
    async destroy() { this.destroyed = true },
    snapshot() { return { current: null, pending: this.queued.map(id => ({ id })), paused: this.paused, loading: false, error: '', positionMillis: 0 } },
  }
  let issued = 0
  const session = new LivePlayerSession({
    baseUrl: 'https://console.test',
    issueToken: issueToken || (async () => ({ token: `token-${++issued}` })),
    onChange: state => states.push(state.status),
    createSocket: (url) => { const socket = new FakeSocket(url); sockets.push(socket); return socket },
    createEngine: (options) => { engine.options = options; return engine },
  })
  return { session, sockets, states, engine, issued: () => issued }
}

test('starting unlocks sound before asking for a credential, then connects with it over a secure socket', async (t) => {
  const order = []
  const { session, sockets, engine } = harness(t, { issueToken: async () => { order.push('token'); return { token: 'a b' } } })
  const unlock = engine.unlock.bind(engine)
  engine.unlock = async () => { order.push('unlock'); return unlock() }

  await session.start()

  assert.deepEqual(order, ['unlock', 'token'])
  assert.equal(sockets.length, 1)
  assert.equal(sockets[0].url, 'wss://console.test/api/player/ws?token=a+b')
  assert.equal(session.snapshot().status, 'connecting')
  // The clip endpoint is authorised by the same credential.
  assert.equal(engine.options.getMediaToken(), 'a b')

  sockets[0].open()
  sockets[0].receive({ type: 'snapshot', commands: [clip('first')] })
  assert.equal(session.snapshot().status, 'connected')
  assert.deepEqual(engine.queued, ['first'])
  sockets[0].receive({ type: 'command', command: clip('second') })
  assert.deepEqual(engine.queued, ['first', 'second'])
})

test('a second start while one is in flight does not issue a second credential', async (t) => {
  const { session, sockets, issued } = harness(t)
  await Promise.all([session.start(), session.start()])
  assert.equal(issued(), 1)
  assert.equal(sockets.length, 1)
  sockets[0].open()
  sockets[0].receive({ type: 'snapshot', commands: [] })
  await session.start()
  assert.equal(issued(), 1)
})

test('a refused credential leaves the page idle with the reason and can be retried', async (t) => {
  let fail = true
  const { session, sockets } = harness(t, { issueToken: async () => { if (fail) throw new Error('请填写场次名称'); return { token: 'ok' } } })
  await assert.rejects(session.start(), /请填写场次名称/)
  assert.equal(session.snapshot().status, 'idle')
  assert.equal(session.snapshot().connectionError, '请填写场次名称')
  assert.equal(sockets.length, 0)
  fail = false
  await session.start()
  assert.equal(sockets.length, 1)
  assert.equal(session.snapshot().connectionError, '')
})

test('finished clips are acknowledged, and an acknowledgement lost while offline is resent on reconnect', async (t) => {
  const { session, sockets, engine } = harness(t)
  await session.start()
  sockets[0].open()
  sockets[0].receive({ type: 'snapshot', commands: [clip('first'), clip('second')] })

  engine.options.onComplete('first')
  assert.deepEqual(sockets[0].sent.filter(message => message.type === 'ack'), [{ type: 'ack', id: 'first' }])

  sockets[0].drop()
  assert.equal(session.snapshot().status, 'reconnecting')
  // Finished while disconnected: the server still believes it is queued.
  engine.options.onComplete('second')
  t.mock.timers.tick(1000)
  assert.equal(sockets.length, 2)
  assert.equal(sockets[1].url, sockets[0].url, 'a reconnect reuses the credential')
  sockets[1].open()
  sockets[1].receive({ type: 'snapshot', commands: [clip('second'), clip('third')] })

  assert.deepEqual(sockets[1].sent.filter(message => message.type === 'ack'), [{ type: 'ack', id: 'second' }])
  assert.equal(session.snapshot().status, 'connected')
})

test('reconnect attempts back off, and a silent server is treated as a lost connection', async (t) => {
  const { session, sockets } = harness(t)
  await session.start()
  sockets[0].drop()
  t.mock.timers.tick(999)
  assert.equal(sockets.length, 1)
  t.mock.timers.tick(1)
  assert.equal(sockets.length, 2)
  sockets[1].drop()
  t.mock.timers.tick(1999)
  assert.equal(sockets.length, 2)
  t.mock.timers.tick(1)
  assert.equal(sockets.length, 3)

  sockets[2].open()
  sockets[2].receive({ type: 'snapshot', commands: [] })
  t.mock.timers.tick(10000)
  assert.equal(sockets[2].sent.at(-1).type, 'heartbeat')
  assert.equal(sockets[2].closed, null)
  // No heartbeat reply for more than thirty seconds.
  t.mock.timers.tick(30000)
  assert.deepEqual(sockets[2].closed, { code: 4000, reason: 'heartbeat timeout' })
})

test('when another page takes over, this one stops playing and does not fight to reconnect', async (t) => {
  const { session, sockets, engine, issued } = harness(t)
  await session.start()
  sockets[0].open()
  sockets[0].receive({ type: 'snapshot', commands: [] })

  sockets[0].drop(1008)
  await flush()

  assert.equal(session.snapshot().status, 'revoked')
  assert.equal(engine.paused, true)
  t.mock.timers.tick(60000)
  assert.equal(sockets.length, 1)

  // The user can take playback back with a fresh credential.
  await session.start()
  assert.equal(issued(), 2)
  assert.equal(sockets.length, 2)
  assert.equal(engine.paused, false)
})

test('stopping is deliberate: the socket closes, sound pauses and nothing reconnects until started again', async (t) => {
  const { session, sockets, engine } = harness(t)
  await session.start()
  sockets[0].open()
  sockets[0].receive({ type: 'snapshot', commands: [] })

  await session.stop()
  sockets[0].drop(1000)

  assert.deepEqual(sockets[0].closed, { code: 1000, reason: 'player stopped' })
  assert.equal(session.snapshot().status, 'idle')
  assert.equal(engine.paused, true)
  t.mock.timers.tick(60000)
  assert.equal(sockets.length, 1)

  await session.destroy()
  assert.equal(engine.destroyed, true)
  await session.start()
  assert.equal(sockets.length, 1)
})

test('pause toggles the engine and volume is given as a percentage', async (t) => {
  const { session, engine } = harness(t)
  await session.togglePause()
  assert.equal(engine.paused, true)
  await session.togglePause()
  assert.equal(engine.paused, false)
  session.setVolume(45)
  assert.equal(engine.volume, 0.45)
})
