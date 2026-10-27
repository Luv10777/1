import { get, post } from '../utils/request.js'

/** 视频工作台「帮我出方案」：看参考图出 3 个版本的提示词与分镜，每次（含换一批）1 烟火，失败退回。 */
export const videoPlanApi = {
  config: () => get('/api/video/plans/config'),
  create: request => post('/api/video/plans', request),
  get: id => get(`/api/video/plans/${id}`),
  history: page => get('/api/video/plans', { page, size: 10 }),
}

export const videoPlanStyles = ['高级质感', '温馨治愈', '热闹烟火气', '快节奏']
export const videoPlanLetters = ['A', 'B', 'C']

export const isVideoPlanDone = plan => ['SUCCEEDED', 'FAILED'].includes(plan?.status)

export function videoPlanSeconds(seconds) {
  return `${Number.isInteger(seconds) ? seconds : Number(seconds).toFixed(1)}`
}

/**
 * 复用灵感记录时要换回的参考图：方案按「图1、图2」写提示词，左侧图片和当初不同才换，顺序保持一致；
 * 已删除的图片跳过。返回 null 表示不用动左侧图片。
 */
export function videoPlanRecordImages(record, currentImages) {
  if (!record?.images?.length) return null
  if (record.images.map(image => image.assetId).join() === currentImages.slice(0, record.images.length).map(entry => entry.assetId).join()) return null
  return record.images.filter(image => image.url)
    .map(image => ({ id: `asset-${image.assetId}`, assetId: image.assetId, name: image.name, url: image.url, file: null }))
}

/** 「用这个」填进表单的内容：提示词后面带上要避免的问题；时长按当前模型上限截断。 */
export function videoPlanForm(plan, { maxDuration = 30, ratios = [] } = {}) {
  const avoid = plan.negativePrompt?.trim()
  const duration = Math.min(maxDuration, Math.max(5, Math.round(Number(plan.durationSeconds) || 10)))
  return {
    prompt: avoid ? `${plan.prompt.trim()}\n避免：${avoid}` : plan.prompt.trim(),
    format: ratios.includes(plan.ratio) ? plan.ratio : 'auto',
    duration,
    shortened: duration < Number(plan.durationSeconds),
  }
}
