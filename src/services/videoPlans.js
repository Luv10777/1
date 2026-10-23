import { get, post } from '../utils/request.js'

/** 视频工作台「帮我出方案」：看参考图出 3 个版本的提示词与分镜，每次（含换一批）1 烟火，失败退回。 */
export const videoPlanApi = {
  config: () => get('/api/video/plans/config'),
  create: request => post('/api/video/plans', request),
  get: id => get(`/api/video/plans/${id}`),
}

export const videoPlanStyles = ['高级质感', '温馨治愈', '热闹烟火气', '快节奏']
export const videoPlanLetters = ['A', 'B', 'C']

export const isVideoPlanDone = plan => ['SUCCEEDED', 'FAILED'].includes(plan?.status)

export function videoPlanSeconds(seconds) {
  return `${Number.isInteger(seconds) ? seconds : Number(seconds).toFixed(1)}`
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
