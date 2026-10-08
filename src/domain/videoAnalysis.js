export const VIDEO_ANALYSIS_LIMITS = { maxBytes: 100 * 1024 * 1024, maxDurationSeconds: 60, maxFrames: 48 }

export function validateAnalysisFile(file, limits = VIDEO_ANALYSIS_LIMITS) {
  if (!file || !/\.(mp4|mov)$/i.test(file.name || '')) return '请上传 MP4 或 MOV 视频。'
  if (!Number.isFinite(file.size) || file.size <= 0) return '视频文件为空，请重新选择。'
  if (file.size > limits.maxBytes) return `视频不能超过 ${Math.floor(limits.maxBytes / 1024 / 1024)} MB。`
  if (file.type && !file.type.startsWith('video/') && file.type !== 'application/octet-stream') return '请选择有效的视频文件。'
  return ''
}

export function analysisMimeType(file) {
  return /\.mov$/i.test(file.name) ? 'video/quicktime' : 'video/mp4'
}

export function analysisTime(seconds = 0) {
  const value = Math.max(0, Number.isFinite(Number(seconds)) ? Number(seconds) : 0)
  const whole = Math.floor(value)
  const fraction = Math.round((value - whole) * 10)
  if (fraction === 10) return analysisTime(whole + 1)
  return `${String(Math.floor(whole / 60)).padStart(2, '0')}:${String(whole % 60).padStart(2, '0')}${fraction ? `.${fraction}` : ''}`
}

export function analysisTerminal(status) { return ['SUCCEEDED', 'FAILED'].includes(status) }

export function analysisAudioSummary(result) {
  const audio = result?.audio
  if (audio?.status === 'ANALYZED' && result?.audioAnalyzed === true) return audio.summary || '已分析口播、配乐和音效'
  return { NO_AUDIO: '视频没有音轨，本次仅分析画面', NOT_CONFIGURED: '声音分析暂未开通，本次仅分析画面',
    FAILED: '声音分析未完成，本次仅返回画面报告，可重新分析' }[audio?.status] || '本次仅分析画面，未分析音频'
}

export function nearestAnalysisFrame(frames, seconds) {
  if (!frames?.length) return null
  return frames.reduce((best, frame) => Math.abs(frame.seconds - seconds) < Math.abs(best.seconds - seconds) ? frame : best)
}
