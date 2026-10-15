import { videoModelLabel } from '../services/videoConversations.js'

export const taskModules = {
  IMAGE_GENERATION: '图片生成', DIGITAL_HUMAN_VIDEO: '数字人视频生成',
  VIDEO_GENERATION: '视频生成', VIDEO_ANALYSIS: '视频反推',
}
export const taskStatuses = {
  RUNNING: { label: '进行中', tone: 'info' }, SUCCEEDED: { label: '已完成', tone: 'success' },
  PARTIAL: { label: '部分成功', tone: 'warning' }, FAILED: { label: '失败', tone: 'error' },
  CANCELLED: { label: '已取消', tone: 'muted' }, NEEDS_INPUT: { label: '待补充', tone: 'warning' },
  UPSTREAM_UNKNOWN: { label: '待核对', tone: 'warning' }, INTERRUPTED: { label: '执行中断', tone: 'error' },
  QUEUED: { label: '排队中', tone: 'info' }, GENERATING: { label: '生成中', tone: 'info' }, SAVING: { label: '保存中', tone: 'info' },
}
export const taskStatus = status => taskStatuses[status] || { label: status, tone: 'muted' }
export const taskModel = model => model ? model.split(', ').map(name => {
  const alias = name.replace(/[-.]/g, '_').toUpperCase()
  const label = videoModelLabel(alias)
  return label === alias ? name : label
}).join(' / ') : '未记录模型'
export const taskWorkflow = task => task.module === 'IMAGE_GENERATION'
  ? ({ POSTER: '营销海报', PRODUCT_SET: '产品套图' }[task.workflow] || '图片创作')
  : [task.resolution, task.durationSeconds ? `${task.durationSeconds} 秒` : ''].filter(Boolean).join(' · ')
export const taskDate = value => value ? new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
}).format(new Date(value)).replaceAll('/', '-') : '—'

// Interpret datetime-local in the displayed business timezone, independently of the browser timezone.
export function taskLogQuery(filters, page = 0, size = 20) {
  const result = { page, size }
  for (const key of ['module', 'model', 'status']) if (filters[key]?.trim()) result[key] = filters[key].trim()
  for (const key of ['from', 'until']) {
    if (!filters[key]) continue
    const value = new Date(`${filters[key]}+08:00`)
    if (Number.isNaN(value.getTime())) throw new Error('请选择有效的查询时间')
    result[key] = value.toISOString()
  }
  if (result.from && result.until && result.from >= result.until) throw new Error('结束时间须晚于开始时间')
  return result
}
export function taskResultLink(task) {
  if (task.module === 'IMAGE_GENERATION') return {
    path: task.workflow === 'PRODUCT_SET' ? '/image/create/product-set' : '/image/create/poster', query: { creation: task.taskId },
  }
  if (task.module === 'VIDEO_ANALYSIS') return { path: '/video/analyze', query: { history: task.taskId } }
  return null
}
