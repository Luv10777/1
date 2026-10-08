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

export function analysisReportMode(analysis, selectedMode = 'ai') {
  return ['ai', 'real'].includes(analysis?.mode) ? analysis.mode : selectedMode === 'real' ? 'real' : 'ai'
}

export function analysisRecreationSteps(result, mode, route = 'filming') {
  const fields = mode === 'real'
    ? route === 'ai' ? [['aiWorkflow', '用 AI 重做的步骤']]
      : [['preparation', '开拍准备'], ['cameraSetup', '相机位置与移动'], ['lightingSetup', '灯光怎么布置'],
          ['recording', '声音怎么录'], ['editing', '剪辑怎么做']]
    : [['workflow', '按什么顺序生成'], ['consistency', '前后镜头如何保持一致'], ['assembly', '剪辑与声音怎么合成']]
  return fields.filter(([key]) => result?.recreation?.[key]).map(([key, title]) => ({ title, text: result.recreation[key] }))
}

export function analysisShotDetails(shot, mode, route = 'filming') {
  const fields = [['framing', '拍多近、从什么角度看'], ['camera', '相机如何移动'], ['lighting', '光线与颜色']]
  if (mode === 'real' && route !== 'ai') fields.push(['filming', '照着拍的操作步骤'])
  else fields.push(['continuity', '前后镜头如何衔接'])
  fields.push(['sound', '声音安排'], ['editing', '剪辑与字幕'])
  return fields.filter(([key]) => shot?.[key]).map(([key, label]) => ({ label, text: shot[key] }))
}

export function isChineseAnalysisPrompt(value) {
  if (typeof value !== 'string') return false
  const chinese = value.match(/\p{Script=Han}/gu)?.length || 0
  const englishWords = value.match(/[A-Za-z]+/g)?.length || 0
  return chinese >= Math.max(1, englishWords * 2)
}

// Historical English prompts are rebuilt from existing Chinese observations, not translated or written back to storage.
export function localizeAnalysisPrompts(analysis) {
  const result = analysis?.result
  if (!result) return null
  const chinese = value => isChineseAnalysisPrompt(value) ? value.trim() : ''
  const format = analysis.width && analysis.height
    ? analysis.width > analysis.height ? '横屏' : analysis.width < analysis.height ? '竖屏' : '正方形画幅' : ''
  const settings = (result.parameters || []).filter(item => chinese(item.key) && chinese(item.value))
    .map(item => `${item.key}：${item.value}`).join('；')
  let localized = false
  const prompt = (value, fallback) => {
    if (isChineseAnalysisPrompt(value)) return value
    localized = true
    return fallback.filter(Boolean).join('\n')
  }
  const staticPrompt = (scene, shot = {}) => [
    `生成一张${format}静态参考图片。`,
    scene && `画面内容：${scene}`,
    chinese(shot.framing), chinese(shot.lighting), settings,
    '定格呈现这一镜头的主体和场景，保持主体外形、服装、道具与参考画面一致。',
  ]
  const shots = (result.shots || []).map((shot, index) => {
    const scene = chinese(shot.scene) || chinese(result.summary)
    const firstFrame = (result.keyframes || []).find(frame => Math.abs(Number(frame.seconds) - Number(shot.start)) <= 0.15)
    const firstFrameScene = chinese(firstFrame?.description) || chinese(firstFrame?.title) || scene
    return { ...shot,
      prompt: prompt(shot.prompt, [
        `镜头 ${index + 1}，生成${format}视频，时长 ${Math.max(0, Number(shot.end) - Number(shot.start)).toFixed(1)} 秒。`,
        scene, chinese(shot.framing), chinese(shot.camera), chinese(shot.lighting), settings,
        chinese(shot.continuity), '动作自然连续，保持主体和场景风格一致。',
      ]),
      firstFramePrompt: prompt(shot.firstFramePrompt, staticPrompt(firstFrameScene, shot)),
    }
  })
  const keyframes = (result.keyframes || []).map(frame => {
    const shot = (result.shots || []).find(item => frame.seconds >= item.start && frame.seconds <= item.end)
    const scene = chinese(frame.description) || chinese(frame.title) || chinese(shot?.scene) || chinese(result.summary)
    return { ...frame, prompt: prompt(frame.prompt, staticPrompt(scene, shot)) }
  })
  const wholePrompt = prompt(result.prompt, [
    `生成一段${format}视频${analysis.durationMs ? `，总时长 ${(analysis.durationMs / 1000).toFixed(1)} 秒` : ''}，按以下画面拆解复刻。`,
    chinese(result.summary), settings,
    ...(result.shots || []).map((shot, index) => [
      `镜头 ${index + 1}（${analysisTime(shot.start)}–${analysisTime(shot.end)}）：`,
      chinese(shot.scene), chinese(shot.framing), chinese(shot.camera), chinese(shot.lighting), chinese(shot.continuity),
    ].filter(Boolean).join(' ')),
    '保持各镜头的主体外形、服装、产品、场景和色彩一致，动作与镜头衔接自然。',
  ])
  const negativePrompt = prompt(result.negativePrompt, [
    '避免画面模糊、主体变形、多余肢体、手指数量异常、产品外形变化、字幕乱码、画面闪烁、动作突变和前后镜头不一致。',
  ])
  return { ...result, prompt: wholePrompt, negativePrompt, shots, keyframes, promptsLocalized: localized }
}
