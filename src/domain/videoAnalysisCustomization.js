import { analysisTime, localizeAnalysisPrompts } from './videoAnalysis.js'

const owns = (value, key) => Object.prototype.hasOwnProperty.call(value, key)
const overlaps = (a, b) => Number(a.start) < Number(b.end) + 0.15 && Number(a.end) > Number(b.start) - 0.15

function replaceText(value, replacements) {
  const entries = [...new Map(replacements.filter(([from]) => from)).entries()].sort((a, b) => b[0].length - a[0].length)
  if (!entries.length) return value
  const pattern = new RegExp(entries.map(([from]) => from.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'g')
  const values = new Map(entries)
  return value.replace(pattern, match => values.get(match))
}

function protectSpokenText(value, items) {
  if (!items.length) return value
  const originals = new Map(items.map(item => [item.original, `{{speech:${item.id}}}`]))
  const phrases = [...originals.keys()].sort((a, b) => b.length - a.length).map(text => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  // Historical reports may quote speech inline. Match a speech label so a short utterance does not preserve a visual product description.
  const pattern = new RegExp(`((?:口播|对白|对话|台词|配音|说|问|回答|开口)(?:内容)?\\s*[：:为是]?\\s*[“"'「]?)(${phrases.join('|')})`, 'g')
  return value.replace(pattern, (match, label, original) => label + originals.get(original))
}

export function analysisEditableContent(analysis) {
  const result = analysis?.result
  if (result?.audioAnalyzed !== true || result.audio?.status !== 'ANALYZED') return []
  const recorded = (result.editableContent || []).filter(item => item.kind === 'dialogue' && item.source === 'audio')
  const used = new Set()
  return (result.audio.transcript || []).filter(item => typeof item.text === 'string' && item.text.trim()).map((segment, index) => {
    const match = recorded.find(item => !used.has(item.id) && item.original === segment.text && overlaps(item, segment))
    const id = match?.id || `speech${index}`
    used.add(id)
    return { id, kind: 'dialogue', label: `口播 ${index + 1}`, original: segment.text, source: 'audio', start: segment.start, end: segment.end }
  })
}

export function customizeAnalysisRecreation(analysis, edits = {}) {
  if (!analysis?.result) return null
  const items = analysisEditableContent(analysis)
  const values = new Map(items.map(item => [item.id, owns(edits, item.id) ? String(edits[item.id]) : item.original]))
  // Protect spoken words before visual product descriptions are prepared for the merchant's reference image.
  const tokens = (analysis.result.editableContent || []).map(item => {
    const candidates = item.kind === 'dialogue' && item.source === 'audio'
      ? items.filter(segment => segment.original.includes(item.original) && overlaps(item, segment)) : []
    const speech = candidates.find(segment => segment.id === item.id) || candidates.sort((a, b) =>
      Math.abs(a.start - item.start) + Math.abs(a.end - item.end) - Math.abs(b.start - item.start) - Math.abs(b.end - item.end))[0]
    return [`{{edit:${item.id}}}`, speech ? `{{speech:${speech.id}}}` : '']
  })
  const prepare = value => typeof value === 'string' ? protectSpokenText(replaceText(value, tokens), items)
    .replace(/\{\{edit:[^}]+}}/g, '')
    .replace(/(?:卡片)?(?:品牌|字幕|画面文字|文字)(?:内容)?(?:安排为|使用|为|：|:)\s*(?=[，；。])/g, '')
    .replace(/(?:[、，；]\s*)+(?=[、，；。])/g, '') : value
  const result = localizeAnalysisPrompts(analysis, prepare)
  const time = item => `${analysisTime(item.start)}–${analysisTime(item.end)}`
  const directive = item => {
    const value = values.get(item.id)
    return value.trim() ? `${time(item)} 口播：“${value}”。` : `${time(item)}不安排口播。`
  }
  const render = (value, includeAll = true) => {
    const text = value || ''
    const used = new Set(items.filter(item => text.includes(`{{speech:${item.id}}}`)).map(item => item.id))
    // Insert the transcript or the merchant's literal words last; never replace product or brand names inside them.
    const rendered = replaceText(text, items.map(item => [`{{speech:${item.id}}}`, directive(item)]))
    const extra = includeAll ? items.filter(item => !used.has(item.id)).map(directive) : []
    return [rendered, extra.length ? `口播内容：\n${extra.join('\n')}` : ''].filter(Boolean).join('\n\n')
  }
  const prompt = render(result.prompt), generationScript = render(result.generationScript)
  return { ...result, prompt, generationScript, reuseScript: analysis.mode === 'real' ? render(prepare(result.reuseScript)) : generationScript,
    negativePrompt: render(result.negativePrompt, false),
    recreation: result.recreation && Object.fromEntries(Object.entries(result.recreation).map(([key, value]) => [key, render(value, false)])),
    shots: result.shots.map(shot => ({ ...shot, prompt: render(shot.prompt, false), firstFramePrompt: render(shot.firstFramePrompt, false),
      ...(shot.continuity ? { continuity: render(shot.continuity, false) } : {}) })),
    keyframes: result.keyframes.map(frame => ({ ...frame, prompt: render(frame.prompt, false) })),
    editableContent: items.map(item => ({ ...item, kindLabel: '口播', replacement: values.get(item.id),
      changed: owns(edits, item.id), timeLabel: time(item) })) }
}

export function analysisRecreationRoute(analysis, edits = {}) {
  const result = customizeAnalysisRecreation(analysis, edits)
  const prompt = [`整体复刻提示词：\n${result?.prompt || ''}`, `分镜脚本：\n${result?.generationScript || ''}`,
    result?.negativePrompt && `避免出现：\n${result.negativePrompt}`].filter(Boolean).join('\n\n')
  const width = Number(analysis.width), height = Number(analysis.height)
  const ratios = ['16:9', '4:3', '1:1', '3:4', '9:16', '21:9']
  const ratio = width > 0 && height > 0 ? ratios.find(value => {
    const [a, b] = value.split(':').map(Number)
    return Math.abs(width / height - a / b) < 0.03
  }) || 'auto' : 'auto'
  // Keep the full draft out of the URL: Chinese copy and long dialogue can exceed the server's URL limit.
  return { path: '/video/workbench', query: { ratio, duration: String(Math.max(5, Math.round(analysis.durationMs / 1000))) },
    state: { recreationPrompt: prompt } }
}
