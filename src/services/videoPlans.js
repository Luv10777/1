import { get, post } from '../utils/request.js'
import { generationWalletPath } from '../domain/billing.js'

/** 视频工作台「帮我出方案」：看参考图出 3 个版本的提示词与分镜，每次（含换一批）1 烟火，失败退回。 */
export const videoPlanApi = {
  config: () => get('/api/video/plans/config'),
  create: (request, wallet) => post(generationWalletPath('/api/video/plans', wallet), request),
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
 * 图片缺失时不能压缩序号，必须重新出方案。返回 null 表示不用动左侧图片。
 */
export function videoPlanRecordImages(record, currentImages) {
  if (!record?.images?.length) return null
  if (record.images.some(image => !image.url)) throw new Error('方案的参考图已不可用，请重新添加素材并生成方案，避免图序号错位。')
  if (record.images.map(image => image.assetId).join() === currentImages.map(entry => entry.assetId).join()) return null
  return record.images
    .map(image => ({ id: `asset-${image.assetId}`, assetId: image.assetId, name: image.name, url: image.url, file: null }))
}

export function videoPlanRequestedSeconds(idea) {
  const text = String(idea || '')
  const match = text.match(/(?:总时长|成片时长|全长|时长|总共|共|生成|制作)[^\d\n]{0,12}(\d+(?:\.\d+)?)\s*(?:秒(?:钟)?|s(?:ec(?:onds?)?)?)(?![a-z])/i)
    || text.match(/(?<![\d.])(\d+(?:\.\d+)?)\s*(?:秒(?:钟)?|s(?:ec(?:onds?)?)?)(?![a-z])/i)
  return match ? Number(match[1]) : null
}

/** 采纳时保留原始目标；不缩短时长却留下原分镜，也不继续使用违反明确时长的旧方案。 */
export function videoPlanForm(plan, { maxDuration = 30, ratios = [], idea = '', sourceBrief = '' } = {}) {
  const duration = Number(plan.durationSeconds)
  const requested = videoPlanRequestedSeconds(idea) ?? videoPlanRequestedSeconds(sourceBrief)
  if (!Number.isInteger(duration) || duration < 5 || duration > maxDuration) throw new Error(`该方案需要 ${duration} 秒，当前模型最长 ${maxDuration} 秒。请切换模型或重新出方案，不能直接截短分镜。`)
  if (requested != null && requested !== duration) throw new Error(`原需求是 ${requested} 秒，这份旧方案为 ${duration} 秒，请重新生成符合时长的方案。`)
  const original = [sourceBrief.trim(), idea.trim()].filter(Boolean).join('\n')
  const prompt = plan.promptVersion === 2 ? plan.prompt.trim()
    : [original && `原始需求（保留未修改的要求，下面最后一句需求为最新要求）：\n${original}`, plan.prompt.trim()].filter(Boolean).join('\n')
  return {
    prompt,
    format: ratios.includes(plan.ratio) ? plan.ratio : '',
    duration,
    shortened: false,
  }
}
