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

export const PRODUCT_REFERENCE_GUIDANCE = '先在生成工具上传你自己的商品参考图。“如图中产品”指这张图中的商品，外观以你的参考图为准；照着复刻镜头、动作和节奏。'

// New analyses supply product aliases. Older reports can only use identities recorded in their subject/product fields.
function analysisProductReferences(result) {
  if (Array.isArray(result.productReferences)) return result.productReferences.filter(value => typeof value === 'string' && value.trim())
  const nouns = /香水瓶|包装盒|马克杯|保温杯|水杯|杯子|白杯|红杯|黑杯|玻璃杯|陶瓷杯|香水|精华液|面霜|口红|洗发水|沐浴露|饮料|护肤品|化妆品|耳机|手机|手表|项链|手链|戒指|包包|运动鞋|球鞋|瓶子|产品|商品/g
  const descriptions = (result.parameters || []).filter(item => (/商品|产品/.test(item.key)
      && !/颜色|色彩|材质|尺寸|形状|卖点|功效|宣传/.test(item.key)) || /^(品牌|品牌名称)$/.test(item.key)
    || (/主体|主角/.test(item.key) && String(item.value || '').match(nouns)))
    .flatMap(item => String(item.value || '').split(/[，,；;、\n]/)).map(value => value.trim()).filter(Boolean)
  const references = descriptions.flatMap(value => [value, ...(value.match(nouns) || [])])
  if (references.some(value => /杯/.test(value))) references.push('杯子', '白杯', '红杯', '黑杯')
  return [...new Set(references)].filter(value => !['产品', '商品', '如图中产品'].includes(value))
}

function productTemplateText(value, references) {
  if (typeof value !== 'string') return value
  let text = value
  // Match complete recorded identities first, so replacing a short name does not leave its recorded appearance behind.
  const appearance = '(?:(?:黑|白|红|蓝|绿|黄|紫|粉|金|银|灰|棕|橙)(?:色)?|透明|陶瓷|玻璃|塑料|金属|不锈钢|木质|纸质|皮革|磨砂|圆形|方形|圆柱形|长方形|大号|小号)'
  for (const reference of [...references].sort((a, b) => b.length - a.length)) {
    const escaped = reference.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    // Common appearance words bind only to an identified product, preserving the same colors/materials elsewhere.
    const identity = new RegExp(`(?:${appearance}(?:材质)?(?:的)?)*${escaped}`, 'g')
    text = text.split('如图中产品').map(part => part.replace(identity, '如图中产品')).join('如图中产品')
  }
  return text.replace(/(?:如图中产品\s*){2,}/g, '如图中产品')
}

// Historical prompts are prepared for reuse without changing the saved observations or stored report.
export function localizeAnalysisPrompts(analysis) {
  const result = analysis?.result
  if (!result) return null
  const references = analysisProductReferences(result)
  const neutral = value => productTemplateText(value, references)
  const chinese = value => isChineseAnalysisPrompt(value) ? neutral(value.trim()) : ''
  const referenceRule = references.length ? '画面中的商品统一为如图中产品，外观、颜色、材质、包装及标识完全以用户上传的商品参考图为准。' : ''
  const format = analysis.width && analysis.height
    ? analysis.width > analysis.height ? '横屏' : analysis.width < analysis.height ? '竖屏' : '正方形画幅' : ''
  const settings = (result.parameters || []).filter(item => chinese(item.key) && chinese(item.value)
      && !/商品|产品|品牌|材质|包装|标识|卖点|功效|宣传/.test(item.key))
    .map(item => `${chinese(item.key)}：${chinese(item.value)}`).join('；')
  let localized = false
  const prompt = (value, fallback) => {
    if (isChineseAnalysisPrompt(value)) return neutral(value)
    localized = true
    return fallback.filter(Boolean).join('\n')
  }
  const staticPrompt = (scene, shot = {}) => [
    `生成一张${format}静态参考图片。`,
    scene && `画面内容：${scene}`,
    chinese(shot.framing), chinese(shot.lighting), settings,
    referenceRule, '定格呈现这一镜头的主体和场景，保持人物、服装和场景一致。',
  ]
  const shots = (result.shots || []).map((shot, index) => {
    const scene = chinese(shot.scene) || chinese(result.summary)
    const firstFrame = (result.keyframes || []).find(frame => Math.abs(Number(frame.seconds) - Number(shot.start)) <= 0.15)
    const firstFrameScene = chinese(firstFrame?.description) || chinese(firstFrame?.title) || scene
    return { ...shot,
      prompt: prompt(shot.prompt, [
        `镜头 ${index + 1}，生成${format}视频，时长 ${Math.max(0, Number(shot.end) - Number(shot.start)).toFixed(1)} 秒。`,
        scene, chinese(shot.framing), chinese(shot.camera), chinese(shot.lighting), settings,
        chinese(shot.continuity), referenceRule, '动作自然连续，保持主体和场景风格一致。',
      ]),
      firstFramePrompt: prompt(shot.firstFramePrompt, staticPrompt(firstFrameScene, shot)),
      ...(shot.continuity ? { continuity: neutral(shot.continuity) } : {}),
    }
  })
  const keyframes = (result.keyframes || []).map(frame => {
    const shot = (result.shots || []).find(item => frame.seconds >= item.start && frame.seconds <= item.end)
    const scene = chinese(frame.description) || chinese(frame.title) || chinese(shot?.scene) || chinese(result.summary)
    return { ...frame, prompt: prompt(frame.prompt, staticPrompt(scene, shot)) }
  })
  const wholePrompt = prompt(result.prompt, [
    `生成一段${format}视频${analysis.durationMs ? `，总时长 ${(analysis.durationMs / 1000).toFixed(1)} 秒` : ''}，按以下画面拆解复刻。`,
    referenceRule, chinese(result.summary), settings,
    ...(result.shots || []).map((shot, index) => [
      `镜头 ${index + 1}（${analysisTime(shot.start)}–${analysisTime(shot.end)}）：`,
      chinese(shot.scene), chinese(shot.framing), chinese(shot.camera), chinese(shot.lighting), chinese(shot.continuity),
    ].filter(Boolean).join(' ')),
    '保持各镜头的主体外形、服装、产品、场景和色彩一致，动作与镜头衔接自然。',
  ])
  const negativePrompt = prompt(result.negativePrompt, [
    '避免画面模糊、主体变形、多余肢体、手指数量异常、产品外形变化、字幕乱码、画面闪烁、动作突变和前后镜头不一致。',
  ])
  const generationScript = result.schemaVersion >= 3 && analysis.mode !== 'real' && isChineseAnalysisPrompt(result.reuseScript)
    ? neutral(result.reuseScript) : shots.map((shot, index) => [
      `镜头 ${index + 1}｜${analysisTime(shot.start)}–${analysisTime(shot.end)}｜时长 ${(shot.end - shot.start).toFixed(1)} 秒`,
      chinese(shot.scene), chinese(shot.camera), chinese(shot.editing),
    ].filter(Boolean).join('｜')).join('\n')
  const recreation = result.recreation && Object.fromEntries(Object.entries(result.recreation)
    .map(([key, value]) => [key, analysis.mode !== 'real' || key === 'aiWorkflow' ? neutral(value) : value]))
  const withReference = value => referenceRule && value && !value.includes('用户上传的商品参考图') ? `${referenceRule}\n${value}` : value
  return { ...result, prompt: withReference(wholePrompt), negativePrompt,
    shots: shots.map(shot => ({ ...shot, prompt: withReference(shot.prompt), firstFramePrompt: withReference(shot.firstFramePrompt) })),
    keyframes: keyframes.map(frame => ({ ...frame, prompt: withReference(frame.prompt) })), recreation,
    generationScript: withReference(generationScript), reuseScript: analysis.mode === 'real' ? result.reuseScript : withReference(generationScript),
    promptsLocalized: localized, productReferences: references }
}
