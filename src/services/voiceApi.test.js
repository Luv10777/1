import test from 'node:test'
import assert from 'node:assert/strict'
import { createVoiceApi, voiceFileType, voiceDurationProblem, measureVoiceFile } from './voiceApi.js'

const file = { name: 'test.m4a', type: 'audio/x-m4a', size: 1048 }

test('status refresh queries the saved sample without creating a new voice', async () => {
  const calls = []
  const api = createVoiceApi({ request: async (path, options) => { calls.push([path, options.method]); return { id: 42, status: 'READY', providerVoiceId: 'cosyvoice-v3.5-flash-wa-id' } } })
  const sample = await api.refresh(42)
  assert.equal(sample.providerVoiceId, 'cosyvoice-v3.5-flash-wa-id')
  assert.deepEqual(calls, [['/api/voice-samples/42/refresh', 'POST']])
})

test('voice upload records consent then PUTs bytes before confirming sample', async () => {
  const calls = []
  const api = createVoiceApi({
    request: async (path, options) => {
      calls.push({ path, ...options })
      if (path.endsWith('upload-url')) return { sample: { id: 42 }, uploadUrl: 'https://upload.invalid/signed' }
      return { id: 42, status: 'UPLOADED' }
    },
    fetch: async (path, options) => { calls.push({ path, ...options }); return { ok: true } },
  })
  assert.equal((await api.upload(5, { name: ' 老板 ', file, consent: true })).status, 'UPLOADED')
  assert.equal(calls[0].path, '/api/stores/5/voice-samples/upload-url')
  assert.deepEqual(JSON.parse(calls[0].body), { name: '老板', mimeType: 'audio/mp4', consent: true })
  assert.equal(calls[1].method, 'PUT')
  assert.equal(calls[1].body, file)
  assert.equal(calls[2].path, '/api/voice-samples/42/confirm')
})

test('no upload ticket is requested without consent or for invalid files', async () => {
  let requests = 0
  const api = createVoiceApi({ request: () => { requests++ } })
  await assert.rejects(api.upload(5, { name: '声音', file, consent: false }), /授权/)
  await assert.rejects(api.upload(5, { name: '声音', file: { ...file, size: 11 * 1024 * 1024 }, consent: true }), /10 MB/)
  assert.throws(() => voiceFileType({ ...file, type: 'text/plain' }), /音频文件/)
  assert.equal(requests, 0)
})

test('failed storage upload is not confirmed and cleans up pending sample', async () => {
  const paths = []
  const api = createVoiceApi({ request: async (path, options) => {
    paths.push([path, options.method])
    return { sample: { id: 42 }, uploadUrl: 'https://upload.invalid/signed' }
  }, fetch: async () => ({ ok: false }) })
  await assert.rejects(api.upload(5, { name: '声音', file, consent: true }), /上传失败/)
  assert.deepEqual(paths, [['/api/stores/5/voice-samples/upload-url', 'POST'], ['/api/voice-samples/42', 'DELETE']])
})

test('a malformed upload ticket never uploads bytes or confirms an undefined sample', async () => {
  const requests = []
  const api = createVoiceApi({ request: async path => {
    requests.push(path)
    return { sample: { status: 'PENDING_UPLOAD' }, uploadUrl: 'https://upload.invalid/signed' }
  }, fetch: async () => { assert.fail('must not upload without a persisted sample ID') } })
  await assert.rejects(api.upload(5, { name: '声音', file, consent: true }), /上传凭证不完整/)
  assert.deepEqual(requests, ['/api/stores/5/voice-samples/upload-url'])
})

test('an uploaded file outside the provider limits is refused with its measured length', () => {
  assert.match(voiceDurationProblem(3.24), /只有 3\.2 秒.*至少需要 5 秒/)
  assert.match(voiceDurationProblem(75.4), /有 75 秒.*超过 60 秒/)
  assert.equal(voiceDurationProblem(5), '')
  assert.equal(voiceDurationProblem(60), '')
  // A file the browser cannot measure is not blocked here; later stages decide.
  assert.equal(voiceDurationProblem(null), '')
  assert.equal(voiceDurationProblem(Infinity), '')
})

test('a file is measured from its metadata and its temporary URL is always released', async () => {
  const revoked = []
  const fake = (behaviour) => {
    const audio = { duration: NaN, set src(value) { this.url = value; queueMicrotask(() => behaviour(audio)) } }
    return audio
  }
  const options = (behaviour) => ({ createAudio: () => fake(behaviour), createUrl: () => 'blob:sample', revokeUrl: url => revoked.push(url), timeoutMillis: 20 })

  assert.equal(await measureVoiceFile(file, options(audio => { audio.duration = 12.5; audio.onloadedmetadata() })), 12.5)
  assert.equal(await measureVoiceFile(file, options(audio => audio.onerror())), null)
  assert.equal(await measureVoiceFile(file, options(audio => { audio.duration = Infinity; audio.onloadedmetadata() })), null)
  // Metadata that never arrives must not leave the form waiting forever.
  assert.equal(await measureVoiceFile(file, options(() => {})), null)
  assert.deepEqual(revoked, ['blob:sample', 'blob:sample', 'blob:sample', 'blob:sample'])
})
