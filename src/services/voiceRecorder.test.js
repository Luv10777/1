import test from 'node:test'
import assert from 'node:assert/strict'
import { createVoiceRecorder, encodeVoiceWav } from './voiceRecorder.js'

test('recorded audio is a real mono PCM16 WAV with clipped samples and its actual sample rate', async () => {
  const blob = encodeVoiceWav({ sampleRate: 48000, getChannelData: index => {
    assert.equal(index, 0)
    return Float32Array.from([-2, -0.5, 0, 0.5, 2])
  } })
  assert.equal(blob.type, 'audio/wav')
  const bytes = await blob.arrayBuffer()
  const view = new DataView(bytes)
  assert.equal(new TextDecoder().decode(bytes.slice(0, 4)), 'RIFF')
  assert.equal(new TextDecoder().decode(bytes.slice(8, 12)), 'WAVE')
  assert.equal(view.getUint32(4, true), bytes.byteLength - 8)
  assert.equal(view.getUint16(20, true), 1)
  assert.equal(view.getUint16(22, true), 1)
  assert.equal(view.getUint32(24, true), 48000)
  assert.equal(view.getUint16(34, true), 16)
  assert.equal(view.getUint32(40, true), 10)
  assert.deepEqual(Array.from({ length: 5 }, (_, index) => view.getInt16(44 + index * 2, true)), [-32768, -16384, 0, 16384, 32767])
})

function harness({ permission, duration = 12 } = {}) {
  let stopped = 0
  let closed = 0
  let resumeCalled = false
  const states = [], files = [], errors = []
  const stream = { getTracks: () => [{ stop: () => { stopped++ } }] }
  class Context {
    state = 'running'
    async resume() { resumeCalled = true }
    async close() { this.state = 'closed'; closed++ }
    async decodeAudioData() { return { duration, sampleRate: 24000, getChannelData: () => new Float32Array(duration * 24000) } }
  }
  class Recorder {
    static isTypeSupported(type) { return type === 'audio/mp4' }
    constructor(_stream, options) { this.mimeType = options.mimeType; this.state = 'inactive' }
    start() { this.state = 'recording' }
    stop() {
      this.state = 'inactive'
      this.ondataavailable?.({ data: new Blob(['encoded audio']) })
      this.onstop?.()
    }
  }
  const recorder = createVoiceRecorder({
    onState: state => states.push(state), onTick: () => {},
    onComplete: file => files.push(file), onError: error => errors.push(error),
    mediaDevices: { getUserMedia: () => { assert.equal(resumeCalled, true); return permission || Promise.resolve(stream) } },
    AudioContext: Context, Recorder,
  })
  return { recorder, stream, states, files, errors, stopped: () => stopped, closed: () => closed }
}

test('stopping a microphone session emits a WAV file and releases microphone and context', async () => {
  const h = harness()
  await h.recorder.start()
  h.recorder.stop()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(h.files.length, 1)
  assert.equal(h.files[0].name, '麦克风录音.wav')
  assert.equal(h.files[0].type, 'audio/wav')
  assert.equal(h.files[0].size, 44 + 12 * 24000 * 2)
  assert.ok(h.stopped() > 0)
  assert.equal(h.closed(), 1)
  assert.deepEqual(h.errors, [])
})

test('closing while microphone permission is pending releases the late stream and emits no recording', async () => {
  let allow
  const h = harness({ permission: new Promise(resolve => { allow = resolve }) })
  const starting = h.recorder.start()
  await Promise.resolve()
  h.recorder.cancel()
  allow(h.stream)
  await starting
  assert.equal(h.stopped(), 1)
  assert.equal(h.closed(), 1)
  assert.deepEqual(h.files, [])
  assert.deepEqual(h.errors, [])
  assert.equal(h.states.at(-1), 'idle')
})

test('short recordings are rejected and permission denial is actionable', async () => {
  const short = harness({ duration: 2 })
  await short.recorder.start()
  short.recorder.stop()
  await new Promise(resolve => setImmediate(resolve))
  assert.match(short.errors[0], /不足 10 秒/)
  assert.deepEqual(short.files, [])
  assert.equal(short.closed(), 1)
  const denied = harness({ permission: Promise.reject(Object.assign(new Error('denied'), { name: 'NotAllowedError' })) })
  await denied.recorder.start()
  assert.match(denied.errors[0], /权限未开启/)
  assert.equal(denied.closed(), 1)
})
