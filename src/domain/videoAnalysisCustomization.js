import { analysisTime, localizeAnalysisPrompts, productTemplateText } from './videoAnalysis.js'

const kinds = { brand: '品牌', dialogue: '对白／口播', subtitle: '画面字幕', text: '其他文字' }
const owns = (value, key) => Object.prototype.hasOwnProperty.call(value, key)

function replaceText(value, replacements) {
  const entries = [...new Map(replacements.filter(([from]) => from)).entries()].sort((a, b) => b[0].length - a[0].length)
  if (!entries.length) return value
  const pattern = new RegExp(entries.map(([from]) => from.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'g')
  const values = new Map(entries)
  return value.replace(pattern, match => values.get(match))
}

export function analysisEditableContent(analysis) {
  const result = analysis?.result
  if (!result) return []
  if (Array.isArray(result.editableContent)) return result.editableContent.map(item => ({ ...item }))
  const items = (result.parameters || []).filter(item => /^(品牌|品牌名称|品牌信息)$/.test(item.key) && item.value)
    .map((item, index) => ({ id: `brand${index}`, kind: 'brand', label: '原视频品牌', original: item.value, source: 'video', start: 0, end: analysis.durationMs / 1000 }))
  if (result.audioAnalyzed === true && result.audio?.status === 'ANALYZED') {
    items.push(...(result.audio.transcript || []).filter(item => item.text).map((item, index) => ({
      id: `speech${index}`, kind: 'dialogue', label: `对白／口播 ${index + 1}`, original: item.text, source: 'audio', start: item.start, end: item.end,
    })))
  }
  return items
}

export function customizeAnalysisRecreation(analysis, edits = {}) {
  const result = localizeAnalysisPrompts(analysis)
  if (!result) return null
  const items = analysisEditableContent(analysis)
  const brands = items.filter(item => item.kind === 'brand')
  const brandValues = new Map(brands.map(item => [item.id, owns(edits, item.id) ? String(edits[item.id]).trim() : '']))
  const values = new Map(items.map(item => {
    if (owns(edits, item.id)) return [item.id, String(edits[item.id]).trim()]
    if (item.kind === 'brand') return [item.id, '']
    // Protect nested brand edits while removing the original product's physical identity.
    const protectedText = replaceText(item.original, brands.map(brand => [brand.original, `{{brand:${brand.id}}}`]))
    const neutral = productTemplateText(protectedText, (result.productReferences || []).filter(reference => !brands.some(brand => brand.original === reference)))
    return [item.id, replaceText(neutral, brands.map(brand => [`{{brand:${brand.id}}}`, brandValues.get(brand.id) || '本店']))]
  }))
  const time = item => `${analysisTime(item.start)}–${analysisTime(item.end)}`
  const directive = item => {
    const value = values.get(item.id)
    if (!value) return item.kind === 'brand' ? '不使用原视频品牌文字。' : `${time(item)}不安排${kinds[item.kind] || '文字'}。`
    return `${item.kind === 'brand' ? '' : time(item) + ' '}${kinds[item.kind] || '文字'}：“${value}”${item.kind === 'brand' ? '，只用于文字表达，商品外观依据用户参考图。' : '。'}`
  }
  const render = value => {
    const text = value || ''
    const used = new Set(items.filter(item => text.includes(`{{edit:${item.id}}}`)).map(item => item.id))
    // Resolve slots and historical phrases in one pass. Never scan the merchant's inserted words again.
    const replacements = items.filter(item => !used.has(item.id)).flatMap(item => [
      [item.original, values.get(item.id)],
      [productTemplateText(item.original, result.productReferences || []), values.get(item.id)],
    ]).filter(([from]) => from && from !== '如图中产品')
    const rendered = replaceText(text, [...replacements, ...items.map(item => [`{{edit:${item.id}}}`, directive(item)])])
    const extra = items.filter(item => !used.has(item.id)).map(directive)
    return [rendered, extra.length ? `文字与对白安排：\n${extra.join('\n')}` : ''].filter(Boolean).join('\n\n')
  }
  const prompt = render(result.prompt), generationScript = render(result.generationScript)
  return { ...result, prompt, generationScript, reuseScript: analysis.mode === 'real' ? result.reuseScript : generationScript,
    editableContent: items.map(item => ({ ...item, kindLabel: kinds[item.kind] || '文字', replacement: values.get(item.id),
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
