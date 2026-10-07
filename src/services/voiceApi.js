import { request as defaultRequest } from '../utils/request.js'

export function voiceFileType(file) {
  if (!file || !file.size) throw new Error('请选择有效的声音样本。')
  if (file.size > 10 * 1024 * 1024) throw new Error('声音样本不能超过 10 MB。')
  const byExtension = { wav: 'audio/wav', mp3: 'audio/mpeg', m4a: 'audio/mp4' }
  const expected = byExtension[file.name?.split('.').pop()?.toLowerCase()]
  const byMime = { 'audio/wav': 'audio/wav', 'audio/x-wav': 'audio/wav', 'audio/mpeg': 'audio/mpeg', 'audio/mp3': 'audio/mpeg', 'audio/mp4': 'audio/mp4', 'audio/x-m4a': 'audio/mp4' }
  if (!expected || (file.type && file.type !== 'application/octet-stream' && byMime[file.type] !== expected)) {
    throw new Error('请上传 WAV、MP3 或 M4A 音频文件。')
  }
  return expected
}

/**
 * 供应商要求样本里至少有 5 秒连续人声，最长 60 秒。麦克风录音在录的时候就有时长限制，
 * 上传的文件没有，所以选完文件先量一下，别等克隆失败了才知道。
 */
export function voiceDurationProblem(seconds) {
  if (!Number.isFinite(seconds)) return ''
  if (seconds < 5) return `这段音频只有 ${seconds.toFixed(1)} 秒，至少需要 5 秒，建议 10～20 秒。`
  if (seconds > 60) return `这段音频有 ${Math.round(seconds)} 秒，超过 60 秒上限，请截取 10～20 秒后再上传。`
  return ''
}

/** 读不出时长（浏览器解不了这个文件）时返回 null，不拦截，交给后面的环节判断。 */
export function measureVoiceFile(file, {
  createAudio = () => new Audio(),
  createUrl = value => URL.createObjectURL(value),
  revokeUrl = value => URL.revokeObjectURL(value),
  timeoutMillis = 5000,
} = {}) {
  return new Promise((resolve) => {
    const audio = createAudio()
    const url = createUrl(file)
    let timer = null
    const done = (seconds) => {
      clearTimeout(timer)
      audio.onloadedmetadata = null
      audio.onerror = null
      revokeUrl(url)
      resolve(seconds)
    }
    timer = setTimeout(() => done(null), timeoutMillis)
    audio.preload = 'metadata'
    audio.onloadedmetadata = () => done(Number.isFinite(audio.duration) ? audio.duration : null)
    audio.onerror = () => done(null)
    audio.src = url
  })
}

export function createVoiceApi({ request = defaultRequest, fetch: uploadFetch = (...args) => globalThis.fetch(...args) } = {}) {
  const read = path => request(path, { method: 'GET' })
  const write = (path, method, body) => request(path, { method, body: JSON.stringify(body) })
  const remove = id => request(`/api/voice-samples/${id}`, { method: 'DELETE' })
  return {
    capabilities: () => read('/api/voice/capabilities'),
    list: storeId => read(`/api/stores/${storeId}/voice-samples`),
    download: id => read(`/api/voice-samples/${id}/download-url`),
    rename: (id, name) => write(`/api/voice-samples/${id}`, 'PATCH', { name }),
    /** 老板调整这个声音开放给哪些门店；至少保留一家。 */
    setStores: (id, storeIds) => write(`/api/voice-samples/${id}/stores`, 'PUT', { storeIds }),
    clone: id => write(`/api/voice-samples/${id}/clone`, 'POST', {}),
    refresh: id => write(`/api/voice-samples/${id}/refresh`, 'POST', {}),
    delete: remove,
    synthesize: (storeId, data) => write(`/api/stores/${storeId}/voice/synthesize`, 'POST', data),
    async upload(storeId, { name, file, consent }) {
      if (!consent) throw new Error('请确认这是本人声音，或已获得声音所有者授权。')
      if (!name.trim()) throw new Error('请填写音色名称。')
      const mimeType = voiceFileType(file)
      const ticket = await write(`/api/stores/${storeId}/voice-samples/upload-url`, 'POST', { name: name.trim(), mimeType, consent: true })
      if (!ticket?.sample?.id || !ticket.uploadUrl) throw new Error('上传凭证不完整，请刷新声音列表后重试。')
      try {
        const result = await uploadFetch(ticket.uploadUrl, { method: 'PUT', headers: { 'Content-Type': mimeType }, body: file })
        if (!result.ok) throw new Error('声音样本上传失败，请重试。')
        return await write(`/api/voice-samples/${ticket.sample.id}/confirm`, 'POST', { sizeBytes: file.size })
      } catch (error) {
        try { await remove(ticket.sample.id) } catch { /* The unfinished sample stays visible for manual deletion. */ }
        throw new Error(error.message || '声音样本上传失败，请检查网络后重试。')
      }
    },
  }
}

export const voiceApi = createVoiceApi()
