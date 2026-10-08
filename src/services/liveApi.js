import { get, post, put, del, request } from '../utils/request.js'

export const liveApi = {
  list: (storeId) => get(`/api/stores/${storeId}/live-sessions`),
  create: (storeId, data) => post(`/api/stores/${storeId}/live-sessions`, data),
  get: (id) => get(`/api/live-sessions/${id}`),
  update: (id, data) => request(`/api/live-sessions/${id}`, { method: 'PATCH', body: JSON.stringify(data) }),
  updateAudioRoute: (id, data) => request(`/api/live-sessions/${id}/audio-route`, { method: 'PATCH', body: JSON.stringify(data) }),
  start: (id) => post(`/api/live-sessions/${id}/start`, {}),
  pause: (id) => post(`/api/live-sessions/${id}/pause`, {}),
  resume: (id) => post(`/api/live-sessions/${id}/resume`, {}),
  end: (id) => post(`/api/live-sessions/${id}/end`, {}),
  remove: (id) => del(`/api/live-sessions/${id}`),
  duplicate: (id, data) => post(`/api/live-sessions/${id}/duplicate`, data),
  pairPlayer: (id) => post(`/api/live-sessions/${id}/player/pairing`, {}),
  revokePlayer: (id) => post(`/api/live-sessions/${id}/player/revoke`, {}),
  playerStatus: (id) => get(`/api/live-sessions/${id}/player/status`),
  playerCommand: (id, data) => post(`/api/live-sessions/${id}/player/commands`, data),
  speech: (id, data) => post(`/api/live-sessions/${id}/speech`, data),
  mockComment: (id, data) => post(`/api/live-sessions/${id}/mock-comments`, data),
  // 桌面端从直播间读到的弹幕。
  comment: (id, data) => post(`/api/live-sessions/${id}/comments`, data),
  autoScript: (id) => get(`/api/live-sessions/${id}/auto-script`),
  startAutoScript: (id) => post(`/api/live-sessions/${id}/auto-script/start`, {}),
  stopAutoScript: (id) => post(`/api/live-sessions/${id}/auto-script/stop`, {}),
  speechItems: (id) => get(`/api/live-sessions/${id}/speech-items`),
  listQa: (id) => get(`/api/live-sessions/${id}/qa`),
  realtime: (id) => get(`/api/live-sessions/${id}/realtime`),
  unansweredQuestions: (id) => get(`/api/live-sessions/${id}/unanswered-questions`),
  addQa: (id, data) => post(`/api/live-sessions/${id}/qa`, data),
  updateQa: (id, qaId, data) => put(`/api/live-sessions/${id}/qa/${qaId}`, data),
  deleteQa: (id, qaId, version) => del(`/api/live-sessions/${id}/qa/${qaId}?version=${encodeURIComponent(version)}`),
}

export const PERSONA_STYLES = ['亲切自然', '热情活力', '专业沉稳', '幽默轻松']

export const defaultLiveConfig = () => ({
  tone: { opening: '场景需求引入', pain: ['需求场景代入'], detail: ['核心特点讲解'] },
  urgency: 3,
  antiRepeat: true,
  aiDisclosure: true,
  dailyHours: 6,
  persona: { name: '', style: PERSONA_STYLES[0] },
  voiceRoles: [],
  // 弹幕由助播回答。默认关：不是每场都有助播，也有人就要一个人播的感觉。
  replyByCohost: false,
})

/** 开着“弹幕由助播回答”时由谁来答：最先设为助播的那一位。没有助播时开关不起作用。 */
export function answeringCohostId(config) {
  if (!config?.replyByCohost || !(config.voiceRoles || []).some(item => item.role === 'host')) return null
  return (config.voiceRoles || []).find(item => item.role === 'cohost')?.id || null
}

const present = (value) => Object.fromEntries(Object.entries(value || {}).filter(([, entry]) => entry != null))

/**
 * 以前“参与轮换”的音色单独存了一份名单。现在轮换就是主播加助播，
 * 读到旧配置时把名单里的音色当作助播，保存后就只剩角色这一种说法。
 */
function voiceRolesOf(config) {
  const roles = (config.voiceRoles || []).filter(item => item?.id && ['host', 'cohost'].includes(item.role))
  if (config.rotateRoles && !roles.some(item => item.role === 'cohost')) {
    for (const id of config.rotationSelection || []) {
      if (!roles.some(item => item.id === id)) roles.push({ id, role: 'cohost' })
    }
  }
  return roles
}

export function normalizeLiveConfig(config = {}) {
  const defaults = defaultLiveConfig()
  const value = config || {}
  const { rotateRoles: _rotateRoles, rotationSelection: _rotationSelection, ...current } = present(value)
  return {
    ...defaults,
    ...current,
    tone: { ...defaults.tone, ...present(value.tone) },
    persona: { ...defaults.persona, ...present(value.persona) },
    voiceRoles: voiceRolesOf(value),
  }
}

const SESSION_STATUS = { DRAFT: '未开始', LIVE: '直播中', PAUSED: '已暂停', ENDED: '已结束', CANCELLED: '已取消' }
export const sessionStatusLabel = (status) => SESSION_STATUS[status] || status || '未开始'
export const isActiveSession = (session) => ['LIVE', 'PAUSED'].includes(session?.status)

/** 默认场次名带上日期；同一天开第二场时再带上场次序号，历史列表里才分得清哪一场是哪一场。 */
export function defaultSessionName(date = new Date(), existingNames = []) {
  const base = `${date.getMonth() + 1}月${date.getDate()}日直播`
  const sameDay = existingNames.filter(name => name === base || name?.startsWith(`${base}（第`)).length
  return sameDay ? `${base}（第 ${sameDay + 1} 场）` : base
}

/**
 * 打开页面时该看哪一场：正在进行的优先（一个门店最多一场），其次是还没开始的那一场；
 * 都没有就返回空，由页面提示新建。
 */
export function pickCurrentSession(sessions = []) {
  return sessions.find(isActiveSession) || sessions.find(session => session.status === 'DRAFT') || null
}

const shortTime = (value) => {
  const date = value ? new Date(value) : null
  if (!date || Number.isNaN(date.getTime())) return ''
  const pad = (number) => String(number).padStart(2, '0')
  return `${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 历史列表里的一行：名称之外带上状态和开始时间。 */
export function describeSession(session) {
  const when = shortTime(session.startedAt)
  return [sessionStatusLabel(session.status), when && `${when} 开始`].filter(Boolean).join(' · ')
}

/** 开始本场前还缺什么。开始后配置就锁定了，所以缺一样都不让开始。 */
export function startBlockers({ name, hostVoice, products }) {
  const blockers = []
  if (!String(name || '').trim()) blockers.push({ key: 'name', label: '填写场次名称', step: null })
  if (!hostVoice) blockers.push({ key: 'voice', label: '选择主播音色', step: 'voice' })
  else if (hostVoice.usable === false) blockers.push({ key: 'voice', label: '重新选择主播音色（当前这个不可用）', step: 'voice' })
  if (!products?.length) blockers.push({ key: 'products', label: '添加本场商品', step: 'script' })
  return blockers
}

export function toSessionQa(row, products = []) {
  const scope = row.persistMode === 'STORE_KNOWLEDGE' ? 'session' : (row.targetId || 'session')
  return {
    ...row,
    id: `session-${row.id}`,
    apiId: row.id,
    q: row.question,
    a: row.answer,
    scope,
    source: scope === 'session' ? '本场通用' : `本场 · ${products.find(product => product.id === scope)?.name || '已移除商品'}`,
    sourceType: 'session',
  }
}

const SPEECH_STATES = {
  GENERATING: { label: '正在写话术', tone: 'working' },
  PENDING: { label: '正在合成语音', tone: 'working' },
  READY: { label: '待播', tone: 'ready' },
  PLAYED: { label: '已播', tone: 'done' },
  FAILED: { label: '未完成', tone: 'failed' },
  DISCARDED: { label: '已取消', tone: 'done' },
}
const SPEECH_KINDS = { SCRIPT: '自动讲解', MANUAL: '手动播报', REPLY: '互动回复', TEST: '测试音' }

/** 合成在后台进行，播报记录要说清楚每一条走到了哪一步。 */
export function toSpeechFeedItem(item, products = []) {
  const state = SPEECH_STATES[item.status] || { label: item.status, tone: 'done' }
  const product = item.productId == null ? null : products.find(entry => entry.id === item.productId)
  const title = [SPEECH_KINDS[item.kind] || '播报', product?.name, item.beat].filter(Boolean).join(' · ')
  return {
    id: item.id,
    title,
    text: item.text || (item.status === 'GENERATING' ? '话术生成中…' : ''),
    stateLabel: state.label,
    tone: state.tone,
    error: item.status === 'FAILED' ? (item.error || '未知原因') : '',
    working: state.tone === 'working',
  }
}

const COMMENT_STATES = {
  ANSWERING: { label: 'AI 正在回答', tone: 'working' },
  ANSWERED: { label: '已回复', tone: 'done' },
  UNANSWERED: { label: '未回复', tone: 'failed' },
  FAILED: { label: '回复失败', tone: 'failed' },
  // 夸奖、闲聊这类不需要回的弹幕：不是出了问题，只是没有可回的。
  SKIPPED: { label: '无需回复', tone: 'done' },
  // 投诉、说吃了不舒服、要退款、问是不是真人：AI 不回，但一定要让人看到。
  ATTENTION: { label: '需要人工处理', tone: 'failed' },
}
const ANSWER_SOURCES = { SESSION: '本场问答', PRODUCT_FAQ: '商品问答', PRODUCT: '商品知识', STORE: '门店知识库', AI: 'AI 依据资料回答' }
const COMMENT_PROVIDERS = { MOCK: '模拟弹幕', DOUYIN_WEB: '直播间弹幕' }

/** 弹幕流里的一条：观众问了什么、回了什么、回答出自哪里；没回的要说明原因。 */
export function toCommentFeedItem(item) {
  const state = COMMENT_STATES[item.state] || { label: item.state, tone: 'done' }
  return {
    id: item.id,
    question: item.text,
    answer: item.answer || '',
    // 商家写好的原话和模型组织的回答要分得清。
    sourceLabel: item.answer ? (ANSWER_SOURCES[item.source] || '') : '',
    stateLabel: state.label,
    tone: state.tone,
    note: item.answer || item.state === 'SKIPPED' ? '' : (item.note || ''),
    providerLabel: COMMENT_PROVIDERS[item.provider] || item.provider || '',
    needsPerson: item.state === 'ATTENTION',
    working: state.tone === 'working',
  }
}

export function describeAutoScript(status) {
  if (!status) return '按本场商品顺序和话术风格自动生成讲解，播完一段补一段。'
  if (!status.enabled) {
    return status.generated
      ? `已停止，本场共生成 ${status.generated} 段。已合成的 ${status.buffered} 段仍会播完。`
      : '按本场商品顺序和话术风格自动生成讲解，播完一段补一段。'
  }
  if (status.sessionStatus === 'PAUSED') return `本场已暂停，继续本场后恢复生成。待播 ${status.buffered} 段。`
  const parts = [`自动讲解中 · 已生成 ${status.generated} 段`, `待播 ${status.buffered} 段`]
  if (status.inFlight) parts.push('正在准备下一段')
  return parts.join(' · ')
}

/** 本页播报的状态说明。声音在控制台页面里播放，没有别的设备需要查看。 */
export function describePlayback(playback) {
  const waiting = playback?.pending?.length || 0
  switch (playback?.status) {
    case 'connecting':
      return { label: '连接中', detail: '正在连接本场的播报队列…' }
    case 'reconnecting':
      return { label: '重连中', detail: '连接中断，正在恢复；已缓存的音频会继续播放。' }
    case 'revoked':
      return { label: '已停止', detail: '播报已在其他页面开始，或本场已结束。需要在本页播放时请重新点击开始播报。' }
    case 'connected': {
      const label = playback.paused ? '已暂停' : playback.loading ? '缓冲中' : playback.current ? '播放中' : '等待内容'
      const now = playback.current
        ? `${playback.paused ? '当前片段' : '正在播放'}：${playback.current.text || '本场音频'}`
        : '有讲解或回复合成完成后会自动播放。'
      return { label, detail: waiting ? `${now} · 待播 ${waiting} 条` : now }
    }
    default:
      return { label: '未开始', detail: '点击开始播报，AI 声音会从这台电脑播放。播报期间请保持本页面打开。' }
  }
}
