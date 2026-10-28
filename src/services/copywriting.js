import { get, post, put } from '../utils/request.js'

/** 文案创作：看图或一句话，给朋友圈、抖音、小红书写 3 条配文。每次（含换一批）1 烟火，失败退回；改写不另收费。 */
export const copyApi = {
  config: () => get('/api/copy/config'),
  almanac: storeId => get('/api/copy/almanac/today', { storeId }),
  create: request => post('/api/copy/batches', request),
  get: id => get(`/api/copy/batches/${id}`),
  history: (storeId, { platform = '', page = 0 } = {}) => get('/api/copy/batches', { storeId, ...(platform ? { platform } : {}), page, size: 20 }),
  rewrite: (id, index, action) => post(`/api/copy/batches/${id}/versions/${index}/rewrite`, { action }),
  finalize: (batchId, versionIndex) => post('/api/copy/journal', { batchId, versionIndex }),
  journal: (storeId, size = 20) => get('/api/copy/journal', { storeId, page: 0, size }),
  voice: storeId => get('/api/copy/voice', { storeId }),
  updateVoice: (storeId, samples) => put('/api/copy/voice', { storeId, samples }),
  products: storeId => get(`/api/stores/${storeId}/products`, { page: 0, size: 100 }),
  works: (page = 0) => get('/api/image-creations/works', { page, size: 20 }),
  /** 上传一张图片换成素材 ID：先拿上传地址，直传对象存储，再确认。 */
  async uploadImage(file, uploadFetch = (...args) => globalThis.fetch(...args)) {
    const ticket = await post('/api/assets/upload-url', { name: file.name, type: 'IMAGE', mimeType: file.type })
    let response
    try {
      response = await uploadFetch(ticket.uploadUrl, { method: 'PUT', headers: { 'Content-Type': file.type }, body: file })
    } catch {
      throw new Error('图片上传失败，请检查网络后重试')
    }
    if (!response.ok) throw new Error('图片上传失败，请重新选择图片')
    const confirmed = await post(`/api/assets/${ticket.assetId}/confirm`, { sizeBytes: file.size })
    return confirmed.id
  },
}

export const copyPlatforms = [
  { id: 'MOMENTS', label: '朋友圈' },
  { id: 'DOUYIN', label: '抖音' },
  { id: 'XIAOHONGSHU', label: '小红书' },
]
/** 配置接口没取到（例如还没登录）时显示的风格，和后端 CopyPrompt.STYLES 一致，提交时以后端校验为准。 */
export const copyStyles = [
  { name: '真诚日常', description: '像随手一写，口语，不端着' },
  { name: '文艺治愈', description: '慢、安静、有画面感，用词克制' },
  { name: '幽默玩梗', description: '自嘲、反差、接地气，但不低俗' },
  { name: '干货种草', description: '讲清楚好在哪，具体、有信息量' },
  { name: '高级简约', description: '字少、留白、克制，不堆形容词' },
  { name: '热情福利', description: '直接、有号召力，但只说资料里有的优惠' },
]
export const copyStoreLevels = [
  { id: 'NONE', label: '不提' },
  { id: 'LIGHT', label: '轻提' },
  { id: 'VISIT', label: '引导到店' },
]
export const copyRewrites = [
  { id: 'SHORTER', label: '更短' },
  { id: 'COLLOQUIAL', label: '更口语' },
  { id: 'NEW_OPENING', label: '换个开头' },
  { id: 'DE_AI', label: '去 AI 味' },
]
export const copyImageTypes = ['image/jpeg', 'image/png', 'image/webp']
export const COPY_IMAGE_MAX_BYTES = 20 * 1024 * 1024

export const platformLabel = id => copyPlatforms.find(platform => platform.id === id)?.label || id
export const isCopyDone = batch => ['SUCCEEDED', 'FAILED'].includes(batch?.status)
export const isRewriting = batch => Boolean(batch?.versions?.some(version => version.rewriting))

export function validateCopyImage(file) {
  if (!copyImageTypes.includes(file.type)) return '请选择 JPG、PNG 或 WebP 图片'
  if (file.size > COPY_IMAGE_MAX_BYTES) return '图片不能超过 20 MB'
  return ''
}

/**
 * 把文案切成可渲染的片段：〔店名〕这类门店占位显示成门店资料里的值（store），
 * 【开店年数】这类资料里没有的地方原样标出来等商家补（missing）。门店没填的字段也当作待补。
 */
export function copySegments(text, store = {}) {
  const segments = []
  const pattern = /〔([^〔〕]{1,8})〕|【([^【】]{1,12})】/g
  let cursor = 0
  for (const match of String(text || '').matchAll(pattern)) {
    if (match.index > cursor) segments.push({ kind: 'plain', text: text.slice(cursor, match.index) })
    if (match[1] && store[match[1]]) segments.push({ kind: 'store', text: store[match[1]], label: match[1] })
    else segments.push({ kind: 'missing', text: match[2] ? match[0] : `【${match[1]}】` })
    cursor = match.index + match[0].length
  }
  if (cursor < String(text || '').length) segments.push({ kind: 'plain', text: text.slice(cursor) })
  return segments
}

/** 复制出去的文字：门店占位换成真实值，标题、正文、话题按平台习惯排好。 */
export function copyText(version, store = {}) {
  const fill = text => copySegments(text, store).map(segment => segment.text).join('')
  return [fill(version.title), fill(version.body), (version.tags || []).join(' ')].filter(part => part && part.trim()).join('\n\n')
}

/** 复制前还要商家自己补的地方。 */
export function missingFacts(version, store = {}) {
  return [version.title, version.body].flatMap(text => copySegments(text, store)).filter(segment => segment.kind === 'missing').map(segment => segment.text)
}

export function aiLevel(score) {
  const value = Math.min(5, Math.max(1, Math.round(Number(score) || 3)))
  return { value, label: value <= 2 ? '低' : value === 3 ? '中' : '偏高', high: value >= 4 }
}

/** 创作记录按天分组：今天、昨天，其余写月日。 */
export function groupByDay(records, now = new Date()) {
  const dayKey = date => `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`
  const today = dayKey(now)
  const yesterday = dayKey(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1))
  const groups = []
  for (const record of records) {
    const date = new Date(record.createdAt)
    const key = dayKey(date)
    const label = key === today ? '今天' : key === yesterday ? '昨天' : `${date.getMonth() + 1}月${date.getDate()}日`
    let group = groups.find(item => item.key === key)
    if (!group) groups.push(group = { key, label, items: [] })
    group.items.push(record)
  }
  return groups
}

export function clockTime(value) {
  const date = new Date(value)
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

/** 「今天可以发」就是今日宜的那几条：点进工作区，带上平台、风格，「想说的话」写成「今日宜：晒一杯暖茶（霜降）」。 */
export function topicLink(topic, term) {
  const note = `今日宜：${topic.title}${term ? `（${term}）` : ''}${topic.idea ? `。${topic.idea}` : ''}`
  const query = new URLSearchParams({ platform: topic.platform, style: topic.style, note })
  return `/copy/create?${query}`
}

/** 品牌行业判断不了时，黄历卡底部的提示和去处。店长去品牌库补，员工只看提示。 */
export function industryPrompt(almanac, isOwner) {
  if (!almanac || almanac.industryStatus === 'KNOWN') return null
  if (!isOwner) return { text: '请店长在品牌库补充行业，推荐会更贴合你的店', link: null }
  return almanac.industryStatus === 'NO_BRAND'
    ? { text: '门店还没关联品牌，关联后推荐更贴合你的店', label: '去品牌库关联品牌', link: '/brands' }
    : { text: '补充行业，推荐更贴合你的店', label: '去品牌库完善', link: `/brands?edit=${almanac.brandId}` }
}

/** 打开一条旧记录时左栏要恢复的设置。 */
export function copyFormFromBatch(batch) {
  return {
    platform: batch.platform,
    style: batch.style,
    storeLevel: batch.storeLevel,
    voiceOn: batch.voiceOn,
    note: batch.note || '',
    pictures: (batch.images || []).map(image => ({ key: `${image.kind}-${image.id}`, kind: image.kind, id: image.id, name: image.name, url: image.url, file: null })),
    products: batch.products || [],
  }
}
