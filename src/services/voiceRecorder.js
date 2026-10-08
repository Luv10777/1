// Browser recordings (WebM/MP4) are decoded to genuine PCM16 WAV for CosyVoice.
export function encodeVoiceWav(audio) {
  if (audio.sampleRate < 16000) throw new Error('录音采样率不足，请更换录音设备。')
  const pcm = audio.getChannelData(0)
  const buffer = new ArrayBuffer(44 + pcm.length * 2)
  const view = new DataView(buffer)
  const label = (offset, value) => [...value].forEach((char, index) => view.setUint8(offset + index, char.charCodeAt(0)))
  label(0, 'RIFF'); view.setUint32(4, buffer.byteLength - 8, true); label(8, 'WAVE')
  label(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true)
  view.setUint32(24, audio.sampleRate, true); view.setUint32(28, audio.sampleRate * 2, true)
  view.setUint16(32, 2, true); view.setUint16(34, 16, true)
  label(36, 'data'); view.setUint32(40, pcm.length * 2, true)
  for (let index = 0; index < pcm.length; index++) {
    const value = Math.max(-1, Math.min(1, pcm[index]))
    view.setInt16(44 + index * 2, Math.round(value * (value < 0 ? 32768 : 32767)), true)
  }
  return new Blob([buffer], { type: 'audio/wav' })
}

export function createVoiceRecorder({ onState, onTick, onComplete, onError,
  mediaDevices = globalThis.navigator?.mediaDevices,
  Recorder = globalThis.MediaRecorder,
  AudioContext = globalThis.AudioContext || globalThis.webkitAudioContext,
} = {}) {
  let active = null
  const release = session => {
    clearInterval(session.timer)
    session.stream?.getTracks().forEach(track => track.stop())
  }
  const close = session => { if (session.context?.state !== 'closed') session.context?.close().catch(() => {}) }
  const fail = (session, error) => {
    if (active !== session) return
    cancel()
    const messages = { NotAllowedError: '麦克风权限未开启，请允许访问麦克风后重试。', NotFoundError: '未找到麦克风，请连接设备后重试。', NotReadableError: '麦克风被占用或无法读取，请关闭其他录音程序后重试。' }
    onError(messages[error.name] || error.message || '录音失败，请重试或上传音频文件。')
  }
  function cancel() {
    const session = active
    active = null
    if (session) {
      if (session.recorder) {
        session.recorder.onstop = null
        session.recorder.onerror = null
        if (session.recorder.state !== 'inactive') session.recorder.stop()
      }
      release(session); close(session)
    }
    onState('idle')
  }
  function stop() {
    if (!active?.recorder || active.recorder.state !== 'recording') return
    onState('processing')
    clearInterval(active.timer)
    active.recorder.stop()
    release(active)
  }
  async function start() {
    cancel()
    const session = { chunks: [] }
    active = session
    onState('starting'); onTick(0)
    try {
      if (!mediaDevices?.getUserMedia || !Recorder || !AudioContext) throw new Error('当前环境不支持录音，请使用 HTTPS 或 localhost 打开，或上传音频文件。')
      session.context = new AudioContext()
      // Resume during the user gesture, before waiting for microphone permission.
      await session.context.resume()
      if (active !== session) return
      const stream = await mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }, video: false })
      if (active !== session) { stream.getTracks().forEach(track => track.stop()); return }
      session.stream = stream
      const mimeType = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/webm'].find(type => Recorder.isTypeSupported(type))
      const recorder = new Recorder(stream, mimeType ? { mimeType } : undefined)
      session.recorder = recorder
      recorder.ondataavailable = event => { if (active === session && event.data.size) session.chunks.push(event.data) }
      recorder.onerror = event => fail(session, event.error || new Error('录音中断，请重新录制。'))
      recorder.onstop = async () => {
        release(session)
        try {
          const bytes = await new Blob(session.chunks, { type: recorder.mimeType }).arrayBuffer()
          if (active !== session) return
          const audio = await session.context.decodeAudioData(bytes)
          if (active !== session) return
          if (audio.duration < 10) throw new Error('录音不足 10 秒，请重新录制，建议朗读 10～20 秒。')
          if (audio.duration > 60) throw new Error('录音超过 60 秒，请重新录制。')
          const wav = encodeVoiceWav(audio)
          if (wav.size > 10 * 1024 * 1024) throw new Error('录音超过 10 MB，请缩短录音后重试。')
          active = null
          onState('idle')
          onComplete(new File([wav], '麦克风录音.wav', { type: 'audio/wav' }))
        } catch (error) { fail(session, error) }
        finally { close(session) }
      }
      recorder.start()
      const started = Date.now()
      onState('recording')
      session.timer = setInterval(() => {
        const seconds = Math.floor((Date.now() - started) / 1000)
        onTick(seconds)
        if (seconds >= 30) stop()
      }, 200)
    } catch (error) { fail(session, error) }
  }
  return { start, stop, cancel }
}
