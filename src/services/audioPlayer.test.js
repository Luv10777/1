import test from 'node:test'
import assert from 'node:assert/strict'
import { BrowserAudioPlayer } from './audioPlayer.js'

function installBrowser(t) {
  const originalWindow = globalThis.window
  const originalFetch = globalThis.fetch
  const sources = []
  class FakeContext {
    constructor() { this.currentTime = 0; this.state = 'suspended'; this.destination = {} }
    async resume() { this.state = 'running' }
    async suspend() { this.state = 'suspended' }
    async close() { this.state = 'closed' }
    async decodeAudioData() { return { duration: 16 } }
    createGain() { return { gain: { value: 0, setTargetAtTime(value) { this.value = value } }, connect() {} } }
    createBufferSource() {
      const source = { connect() {}, disconnect() {}, start(...args) { this.started = args }, stop(at) { this.stoppedAt = at }, end() { this.onended?.() } }
      sources.push(source)
      return source
    }
  }
  globalThis.window = { AudioContext: FakeContext, location: { origin: 'https://test.invalid' } }
  globalThis.fetch = async () => ({ ok: true, arrayBuffer: async () => new ArrayBuffer(4) })
  t.after(() => { globalThis.window = originalWindow; globalThis.fetch = originalFetch })
  return sources
}
const flush = () => new Promise(resolve => setImmediate(resolve))
const clip = (id, mode = 'APPEND') => ({ id, mode, text: id, audioUrl: '/api/player/test-audio.wav', durationMillis: 16000, pauseOffsetsMillis: [3000, 7000, 12000] })

test('Web Audio pauses and resumes from its real buffer offset and controls the gain', async (t) => {
  const sources = installBrowser(t)
  const player = new BrowserAudioPlayer()
  await player.unlock()
  player.enqueue(clip('a')); await flush()
  player.context.currentTime = 4.25
  assert.equal(player.snapshot().positionMillis, 4250)
  await player.pause()
  assert.equal(player.snapshot().positionMillis, 4250)
  assert.equal(player.context.state, 'suspended')
  await player.resume(); await flush()
  assert.deepEqual(sources[1].started, [0, 4.25])
  player.setVolume(0)
  assert.equal(player.gain.gain.value, 0)
  player.setVolume(0.45)
  assert.equal(player.gain.gain.value, 0.45)
  await player.destroy()
})

test('buffer source stops at the quiet point and resumes original audio after reply completion', async (t) => {
  const sources = installBrowser(t)
  const completed = []
  const player = new BrowserAudioPlayer({ onComplete: id => completed.push(id), random: () => 0.5 })
  await player.unlock()
  player.enqueue(clip('a')); await flush()
  player.context.currentTime = 4.2
  player.enqueue(clip('reply', 'INTERRUPT')); await flush()
  assert.equal(sources[0].stoppedAt, 7)
  player.context.currentTime = 7
  sources[0].end(); await flush()
  assert.equal(player.snapshot().current.id, 'reply')
  // 回复前留一口气，不是贴着上一句就开口。
  assert.deepEqual(sources[1].started, [7.4, 0])
  assert.deepEqual(completed, [])
  player.context.currentTime = 23.4
  sources[1].end(); await flush()
  assert.equal(player.snapshot().current.id, 'a')
  assert.equal(sources[2].started[1], 7)
  assert.ok(Math.abs(sources[2].started[0] - 23.875) < 1e-9)
  assert.deepEqual(completed, ['reply'])
  await player.destroy()
})

test('clear/replay stops old media and acknowledges discarded items; duplicate snapshots do not replay', async (t) => {
  const sources = installBrowser(t)
  const completed = []
  const player = new BrowserAudioPlayer({ onComplete: id => completed.push(id) })
  await player.unlock()
  player.enqueue(clip('a')); player.enqueue(clip('b')); await flush()
  player.enqueue(clip('restart', 'CLEAR_REPLAY')); await flush()
  assert.equal(sources[0].onended, null)
  assert.deepEqual(completed, ['a', 'b'])
  assert.equal(player.snapshot().current.id, 'restart')
  player.enqueue(clip('a')); player.enqueue(clip('restart', 'CLEAR_REPLAY')); await flush()
  assert.equal(player.snapshot().pending.length, 0)
  assert.equal(sources.length, 2)
  await player.destroy()
})

test('failed audio remains retryable without acknowledging an unplayed item', async (t) => {
  installBrowser(t)
  const completed = []
  const player = new BrowserAudioPlayer({ onComplete: id => completed.push(id) })
  await player.unlock()
  globalThis.fetch = async () => ({ ok: false, status: 503 })
  player.enqueue(clip('a')); await flush()
  assert.match(player.snapshot().error, /503/)
  assert.deepEqual(completed, [])
  globalThis.fetch = async () => ({ ok: true, arrayBuffer: async () => new ArrayBuffer(4) })
  player.retry(); await flush()
  assert.equal(player.snapshot().error, '')
  assert.equal(player.snapshot().current.id, 'a')
  assert.ok(player.source)
  await player.destroy()
})

test('pairing token is only sent to the exact internal audio endpoint', async (t) => {
  installBrowser(t)
  const urls = []
  globalThis.fetch = async url => { urls.push(new URL(url)); return { ok: true, arrayBuffer: async () => new ArrayBuffer(4) } }
  const player = new BrowserAudioPlayer({ getMediaToken: () => 'scoped-test-token' })
  await player.unlock()
  await player.prepare({ ...clip('secure'), audioUrl: '/api/player/audio/audio-123' })
  await player.prepare({ ...clip('external'), audioUrl: 'https://external.invalid/api/player/audio/audio-123' })
  await player.prepare({ ...clip('other'), audioUrl: '/api/player/test-audio.wav' })
  await player.prepare({ ...clip('lookalike'), audioUrl: '/api/player/audio/audio-123/elsewhere' })
  assert.equal(urls[0].searchParams.get('token'), 'scoped-test-token')
  for (const url of urls.slice(1)) assert.equal(url.searchParams.has('token'), false)
  await player.destroy()
})

test('clear/replay cannot refill a discarded buffer when an old decode finishes late', async (t) => {
  installBrowser(t)
  const player = new BrowserAudioPlayer()
  await player.unlock()
  let finishOldDecode
  let calls = 0
  player.context.decodeAudioData = () => ++calls === 1 ? new Promise(resolve => { finishOldDecode = resolve }) : Promise.resolve({ duration: 16 })
  player.enqueue(clip('old')); await flush()
  player.enqueue(clip('restart', 'CLEAR_REPLAY')); await flush()
  finishOldDecode({ duration: 16 }); await flush()
  assert.equal(player.snapshot().current.id, 'restart')
  assert.equal(player.buffers.has('old'), false)
  await player.destroy()
})

test('a long pending queue only preloads nearby audio', async (t) => {
  installBrowser(t)
  const player = new BrowserAudioPlayer()
  await player.unlock()
  for (let index = 0; index < 100; index++) player.enqueue(clip(`clip-${index}`))
  await flush()
  assert.equal(player.snapshot().pending.length, 99)
  assert.ok(player.buffers.size <= 3)
  await player.destroy()
})

test('consecutive segments are separated by a breath, but nothing is added after an idle stretch or a user pause', async (t) => {
  const sources = installBrowser(t)
  const player = new BrowserAudioPlayer({ random: () => 0.5 })
  await player.unlock()
  player.enqueue(clip('a')); player.enqueue(clip('b')); await flush()
  assert.deepEqual(sources[0].started, [0, 0])
  player.context.currentTime = 16
  sources[0].end(); await flush()
  assert.deepEqual(sources[1].started, [16.9, 0])
  // 停顿期间还没出声：位置停在片段开头，暂停后继续也不再等一遍。
  player.context.currentTime = 16.5
  assert.equal(player.snapshot().positionMillis, 0)
  await player.pause(); await player.resume(); await flush()
  assert.deepEqual(sources[2].started, [0, 0])
  player.context.currentTime = 40
  sources[2].end(); await flush()
  // 队列空了很久之后来的新片段立刻播。
  player.context.currentTime = 90
  player.enqueue(clip('c')); await flush()
  assert.deepEqual(sources[3].started, [0, 0])
  await player.destroy()
})

test('a reply speaks its closing bridge only when there is narration to go back to', async (t) => {
  const sources = installBrowser(t)
  const completed = []
  const player = new BrowserAudioPlayer({ onComplete: id => completed.push(id), random: () => 0 })
  await player.unlock()
  const reply = id => ({ ...clip(id, 'INTERRUPT'), outroOffsetMillis: 9000 })

  // 打断了讲解：回复播到 9 秒处停一下，确认有讲解可接，再把“接着说”播完。
  player.enqueue(clip('a')); await flush()
  player.context.currentTime = 4
  player.enqueue(reply('r1')); await flush()
  player.context.currentTime = 7
  sources[0].end(); await flush()
  assert.equal(sources[1].stoppedAt, 7.3 + 9)
  player.context.currentTime = 16.3
  sources[1].end(); await flush()
  assert.equal(player.snapshot().current.id, 'r1')
  assert.deepEqual(sources[2].started, [0, 9])
  assert.equal(sources[2].stoppedAt, undefined)
  assert.deepEqual(completed, [])
  player.context.currentTime = 23.3
  sources[2].end(); await flush()
  assert.deepEqual(completed, ['r1'])
  assert.equal(player.snapshot().current.id, 'a')

  // 讲解续讲到只剩不到 5 秒可接的位置时来了两条回复：让这一段讲完，回复紧跟着连播。
  player.context.currentTime = 24
  player.enqueue(reply('r2')); player.enqueue(reply('r3')); await flush()
  assert.equal(sources[3].stoppedAt, undefined)
  player.context.currentTime = 35
  sources[3].end(); await flush()
  assert.equal(player.snapshot().current.id, 'r2')
  // 后面紧跟着另一条回复：第一条不说“接着说”，直接进下一条。
  player.context.currentTime = 45
  sources[4].end(); await flush()
  assert.deepEqual(completed, ['r1', 'a', 'r2'])
  assert.equal(player.snapshot().current.id, 'r3')

  // 最后一条回复之后没有讲解可接：同样不说。
  player.context.currentTime = 55
  sources[5].end(); await flush()
  assert.deepEqual(completed, ['r1', 'a', 'r2', 'r3'])
  assert.equal(player.snapshot().current, null)
  await player.destroy()
})

test('a reply that is ready during the breath between two segments plays first, and the segment follows', async (t) => {
  const sources = installBrowser(t)
  const completed = []
  const player = new BrowserAudioPlayer({ onComplete: id => completed.push(id), random: () => 0.5 })
  await player.unlock()
  player.enqueue(clip('a')); player.enqueue(clip('b')); await flush()
  player.context.currentTime = 16
  sources[0].end(); await flush()
  // 下一段排在 16.9 秒开口；16.3 秒时回复合成好了。
  assert.deepEqual(sources[1].started, [16.9, 0])
  player.context.currentTime = 16.3
  player.enqueue(clip('reply', 'INTERRUPT')); await flush()
  assert.equal(sources[1].onended, null)
  assert.equal(player.snapshot().current.id, 'reply')
  // 回复前的那口气仍从上一段结束时算起（16 + 0.4），不是从现在重新等。
  assert.deepEqual(sources[2].started, [16.4, 0])
  player.context.currentTime = 32.4
  sources[2].end(); await flush()
  assert.deepEqual(completed, ['a', 'reply'])
  assert.deepEqual([player.snapshot().current.id, sources[3].started[1]], ['b', 0])

  // 已经出声的片段不会被这样撤回：照旧等到下一句开头。
  player.context.currentTime = sources[3].started[0] + 1
  player.enqueue(clip('reply-2', 'INTERRUPT')); await flush()
  assert.equal(player.snapshot().current.id, 'b')
  assert.ok(Math.abs(sources[3].stoppedAt - (sources[3].started[0] + 3)) < 1e-9)
  await player.destroy()
})
