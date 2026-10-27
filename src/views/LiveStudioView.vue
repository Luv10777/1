<script setup>
import AppSelect from '../components/AppSelect.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { auth } from '../stores/auth'
import { Check, ChevronDown, MessageSquare, Package, BarChart3 } from 'lucide-vue-next'
import { selectedStoreId, stores } from '../stores/merchantContext'
import ProductFormModal from '../components/ProductFormModal.vue'
import VoiceLibraryPanel from '../components/VoiceLibraryPanel.vue'
import LiveAudioControl from '../components/LiveAudioControl.vue'
import { desktopBridge, desktopLink, inDesktop } from '../utils/desktop'
import { launchApp, onPhone } from '../utils/appLaunch'
import { findDesktopDownloads } from '../services/desktopDownload'
import { productApi } from '../services/productApi'
import { knowledgeApi } from '../services/knowledgeApi'
import { liveApi, defaultLiveConfig, normalizeLiveConfig, toSessionQa, defaultSessionName, pickCurrentSession, describeSession, sessionStatusLabel, isActiveSession, startBlockers, toCommentFeedItem, answeringCohostId, liveCostEstimate, liveBillingProgress } from '../services/liveApi'
import { billing as account, refreshBilling } from '../stores/billing'
import YanhuoIcon from '../components/YanhuoIcon.vue'

/* ---------------- 场次配置 ---------------- */
const route = useRoute()
const stage = ref('setup')
// 开播只在一方志软件（桌面端、安卓 App）里进行：网页读不到直播间弹幕。所以浏览器里没有直播工作台，
// 只管配置和复盘。本地开发时在地址后面加 ?workbench 可以照旧打开，用来发模拟弹幕测试回复。
const hasWorkbench = inDesktop || (import.meta.env.DEV && route.query.workbench !== undefined)
const stageTabs = [
  { key: 'setup', label: '直播配置' },
  ...(hasWorkbench ? [{ key: 'live', label: '直播工作台' }] : []),
  { key: 'review', label: '场次复盘' },
]
const sessionTools = ref(null)
const realtimeFeed = ref({ status: 'NOT_READY', message: '', items: [] })
let realtimePoll = null
let sessionStatusPoll = null
const audioControl = ref(null)
const setStage = (next) => {
  stage.value = next
  sessionTools.value?.removeAttribute('open')
}
const editStep = async (key) => {
  setStage('setup')
  await nextTick()
  document.getElementById(`ls-step-${key}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/* ---------------- 01 真实声音资产与角色 ---------------- */
const voices = ref([])
const voiceRoleSelection = ref([])
const voiceLibraryStoreId = ref(null)
const persona = ref({ name: '', style: '亲切自然' })
const hostVoice = computed(() => voices.value.find(voice => voice.role === 'host'))
const hostReady = computed(() => !!hostVoice.value && hostVoice.value.usable !== false)
const cohostVoices = computed(() => voices.value.filter(voice => voice.role === 'cohost'))
const withRoles = (list) => list.map(voice => ({ ...voice, role: voiceRoleSelection.value.find(item => item.id === voice.id)?.role || 'none' }))
const updateVoiceLibrary = (available, complete = true) => {
  if (complete) {
    const ids = new Set(available.map(voice => voice.id))
    voiceLibraryStoreId.value = selectedStoreId.value
    voiceRoleSelection.value = voiceRoleSelection.value.filter(item => ids.has(item.id))
  } else voiceLibraryStoreId.value = null
  voices.value = withRoles(available)
}
const updateVoiceSelection = (selection) => {
  voiceRoleSelection.value = selection.voiceRoles
  voices.value = withRoles(voices.value)
}

/* ---------------- 02 话术与知识库 ---------------- */
const toLiveProduct = (product) => ({ ...product, tag: product.category, price: `${product.price} / ${product.unit}`, source: 'library' })
const libraryProducts = ref([])
const productQueue = ref([])
const pickedProductIds = computed(() => productQueue.value.filter(p => p.source === 'library').map(p => p.id))
const productPickerOpen = ref(false)
const manualProductOpen = ref(false)
const productSearch = ref('')
const filteredLibraryProducts = computed(() => {
  const query = productSearch.value.trim().toLowerCase()
  if (!query) return libraryProducts.value
  return libraryProducts.value.filter(product => `${product.name} ${product.tag} ${product.price}`.toLowerCase().includes(query))
})
const manualProduct = ref(null)
const manualProductError = ref('')
const openProductPicker = () => { productPickerOpen.value = true; manualProductOpen.value = false }
const openManualProduct = () => { manualProduct.value = null; manualProductError.value = ''; manualProductOpen.value = true; productPickerOpen.value = false }
const closeProductDialogs = () => { if (!apiBusy.value) { productPickerOpen.value = false; manualProductOpen.value = false } }
const toggleLibraryProduct = (product) => {
  const index = productQueue.value.findIndex(p => p.id === product.id)
  if (index >= 0) productQueue.value.splice(index, 1)
  else productQueue.value.push({ ...product })
}
const addManualProduct = async (form) => {
  if (apiBusy.value || loadBusy.value) return
  const ticket = screenTicket
  const storeId = selectedStoreId.value
  if (!storeId) return
  apiBusy.value = true
  manualProductError.value = ''
  try {
    const product = toLiveProduct(await productApi.create(storeId, form))
    if (!isCurrent(ticket, storeId)) return
    libraryProducts.value.push(product)
    productQueue.value.push(product)
    manualProductOpen.value = false
    try {
      await saveDraftCore(ticket, storeId)
      if (isCurrent(ticket, storeId)) draftNotice.value = `「${product.name}」已保存到商品库并关联本场。`
    } catch (error) {
      if (isCurrent(ticket, storeId)) draftError.value = `商品已保存到商品库，但本场关联未保存：${error.message}。请点击保存草稿重试。`
    }
  } catch (error) {
    if (isCurrent(ticket, storeId)) manualProductError.value = error.message || '商品保存失败，请重试。'
  } finally {
    if (isCurrent(ticket, storeId)) apiBusy.value = false
  }
}
const removeProduct = (index) => productQueue.value.splice(index, 1)
const moveProduct = (index, direction) => {
  const nextIndex = index + direction
  if (nextIndex < 0 || nextIndex >= productQueue.value.length) return
  const items = productQueue.value
  ;[items[index], items[nextIndex]] = [items[nextIndex], items[index]]
}

const toneGroups = [
  { key: 'opening', label: '开场', options: ['场景需求引入', '直接报价', '悬念提问'] },
  { key: 'pain', label: '痛点挖掘', options: ['需求场景代入', '顾虑问题拆解', '不展开'] },
  { key: 'detail', label: '细节讲解', options: ['核心特点讲解', '使用/服务流程', '售后与保障'] },
]
const tone = ref({ opening: '场景需求引入', pain: ['需求场景代入'], detail: ['核心特点讲解'] })
const multiToneKeys = new Set(['pain', 'detail'])
const isToneSelected = (key, option) => multiToneKeys.has(key) ? tone.value[key].includes(option) : tone.value[key] === option
const selectTone = (key, option) => {
  if (!multiToneKeys.has(key)) {
    tone.value[key] = option
    return
  }
  if (option === '不展开') {
    tone.value[key] = tone.value[key].includes('不展开') ? [] : ['不展开']
    return
  }
  const selected = tone.value[key].filter(item => item !== '不展开')
  tone.value[key] = selected.includes(option) ? selected.filter(item => item !== option) : [...selected, option]
}
const urgency = ref(3)
const urgencyLabel = computed(() => {
  const level = Math.min(5, Math.max(1, Math.round(urgency.value)))
  return ['极缓 · 只讲不催', '偏慢', '标准节奏', '偏紧', '强促单 · 高频逼单'][level - 1]
})

const liveSessions = ref([])
const activeSessionId = ref(null)
const currentSession = ref(null)
const sessionName = ref('')
const loadBusy = ref(false)
const loadFailed = ref(false)
const apiBusy = ref(false)
const draftError = ref('')
const draftNotice = ref('')
const contextError = ref('')
const savedFingerprint = ref('')
let screenTicket = 0
let contextTicket = 0
const isCurrent = (ticket, storeId) => ticket === screenTicket && storeId === selectedStoreId.value
const readOnlySession = computed(() => !!currentSession.value && currentSession.value.status !== 'DRAFT')
const currentStatus = computed(() => currentSession.value?.status || 'DRAFT')
// 一个门店同时只有一场在进行，也只准备一场还没开始的。
const runningSession = computed(() => liveSessions.value.find(isActiveSession) || null)
const pendingSession = computed(() => liveSessions.value.find(session => session.status === 'DRAFT') || null)
// 换场次会让本页停止播报，所以直播进行中不让切走。
const switchLocked = computed(() => !!runningSession.value)

const inheritedKnowledge = ref([])
const knowledgeSets = ref([])
const storeQaPairs = computed(() => inheritedKnowledge.value.map(entry => ({ id: `store-${entry.id}`, q: entry.question, a: entry.answer, scope: 'store', source: entry.brandId ? '品牌通用' : '门店通用', sourceType: 'store' })))
const sessionQaPairs = ref([])
const productQaPairs = computed(() => productQueue.value.flatMap(product => (product.faqs || [])
  .filter(pair => pair.status === 'active' || pair.status === 'ACTIVE')
  .map(pair => ({ id: `product-${product.id}-${pair.id}`, q: pair.question, a: pair.answer, scope: product.id, source: product.name, sourceType: 'product' }))))
const qaPairs = computed(() => {
  if (readOnlySession.value) return (currentSession.value.knowledge || []).map(row => ({
    id: `snapshot-${row.id}`, q: row.question, a: row.answer, sourceType: 'snapshot',
    scope: ['STORE', 'BRAND'].includes(row.sourceType) ? 'store' : row.sourceType === 'SESSION' ? 'session' : 'product-snapshot',
    source: { STORE: '门店通用 · 开播快照', BRAND: '品牌通用 · 开播快照', SESSION: '本场通用 · 开播快照' }[row.sourceType] || '商品问答 · 开播快照',
  }))
  return [...sessionQaPairs.value.map(pair => ({ ...pair, source: pair.scope !== 'session' && !productQueue.value.some(product => product.id === pair.scope) ? '已移除商品 · 不参与本场' : pair.source })), ...productQaPairs.value, ...storeQaPairs.value]
})
const qaFilter = ref('all')
const qaFilters = computed(() => [
  { value: 'all', label: '全部', count: qaPairs.value.length },
  { value: 'session', label: '本场', count: qaPairs.value.filter(pair => pair.scope === 'session').length },
  { value: 'store', label: '门店与品牌', count: qaPairs.value.filter(pair => pair.scope === 'store').length },
  ...(readOnlySession.value ? [{ value: 'product-snapshot', label: '商品问答', count: qaPairs.value.filter(pair => pair.scope === 'product-snapshot').length }] : productQueue.value.map(product => ({ value: product.id, label: product.name, count: qaPairs.value.filter(pair => pair.scope === product.id).length }))),
])
const visibleQaPairs = computed(() => qaFilter.value === 'all' ? qaPairs.value : qaPairs.value.filter(pair => pair.scope === qaFilter.value))
watch(qaFilters, (filters) => {
  if (!filters.some(filter => filter.value === qaFilter.value)) qaFilter.value = 'all'
})
const newQ = ref('')
const newA = ref('')
const newQaScope = ref('session')
const editingQaId = ref('')
const qaPersistMode = ref('SESSION')
const qaKnowledgeSetId = ref('')
const qaNotice = ref('')
const qaError = ref('')
const qaHelpOpen = ref(false)
watch(() => productQueue.value.map(product => product.id), (ids) => {
  if (newQaScope.value !== 'session' && !ids.includes(newQaScope.value)) { newQaScope.value = 'session'; qaPersistMode.value = 'SESSION' }
})
// 新问答保存到哪：仅本场；门店通用（同时存为门店知识库草稿）；某件商品（同时存进这件商品的问答）。
const qaTarget = computed({
  get: () => qaPersistMode.value === 'STORE_KNOWLEDGE' ? 'store' : newQaScope.value,
  set: (target) => {
    newQaScope.value = target === 'store' ? 'session' : target
    qaPersistMode.value = target === 'session' ? 'SESSION' : target === 'store' ? 'STORE_KNOWLEDGE' : 'PRODUCT_FAQ'
    if (target === 'store' && !qaKnowledgeSetId.value && knowledgeSets.value.length === 1) qaKnowledgeSetId.value = knowledgeSets.value[0].id
  },
})
const cancelQaEdit = () => {
  newQ.value = ''
  newA.value = ''
  newQaScope.value = 'session'
  editingQaId.value = ''
  qaPersistMode.value = 'SESSION'
  qaKnowledgeSetId.value = ''
  qaError.value = ''
}
const refreshSessionVersion = async (ticket, storeId) => {
  const latest = await liveApi.get(activeSessionId.value)
  if (isCurrent(ticket, storeId)) currentSession.value = latest
}
const addQa = async () => {
  if (apiBusy.value || loadBusy.value || readOnlySession.value) return
  qaNotice.value = ''
  qaError.value = ''
  if (!newQ.value.trim() || !newA.value.trim()) {
    qaError.value = '请填写问题和回答后再添加。'
    return
  }
  if (!editingQaId.value && qaPersistMode.value === 'STORE_KNOWLEDGE' && !qaKnowledgeSetId.value) {
    qaError.value = '请选择要保存到的门店问答知识集。'
    return
  }
  const ticket = screenTicket
  const storeId = selectedStoreId.value
  const editing = sessionQaPairs.value.find(pair => pair.id === editingQaId.value)
  const mode = qaPersistMode.value
  const scope = newQaScope.value
  const payload = { question: newQ.value.trim(), answer: newA.value.trim(), persistMode: mode, targetId: mode === 'STORE_KNOWLEDGE' ? Number(qaKnowledgeSetId.value) : (scope === 'session' ? null : scope) }
  apiBusy.value = true
  try {
    await saveDraftCore(ticket, storeId)
    if (!isCurrent(ticket, storeId)) return
    const row = editing
      ? await liveApi.updateQa(activeSessionId.value, editing.apiId, { question: payload.question, answer: payload.answer, version: editing.version })
      : await liveApi.addQa(activeSessionId.value, payload)
    if (!isCurrent(ticket, storeId)) return
    const pair = toSessionQa(row, productQueue.value)
    if (editing) sessionQaPairs.value.splice(sessionQaPairs.value.findIndex(item => item.id === editing.id), 1, pair)
    else sessionQaPairs.value.push(pair)
    cancelQaEdit()
    qaFilter.value = 'all'
    qaNotice.value = editing ? '本场问答已更新；已沉淀的知识库或商品问答不受本次修改影响。' : ({ SESSION: '已添加，仅用于本场直播。', PRODUCT_FAQ: '已添加，并保存至关联商品的问答中。', STORE_KNOWLEDGE: '已添加，并存入门店知识库（停用）；在知识库中启用后用于之后的场次。' }[mode])
    // The write has succeeded. Refresh failures must not invite a duplicate submission.
    try {
      await refreshSessionVersion(ticket, storeId)
      if (mode === 'PRODUCT_FAQ' && !editing) {
        const product = toLiveProduct(await productApi.get(scope))
        if (isCurrent(ticket, storeId)) {
          for (const list of [libraryProducts.value, productQueue.value]) {
            const index = list.findIndex(item => item.id === product.id)
            if (index >= 0) list.splice(index, 1, product)
          }
        }
      }
    } catch (error) {
      if (isCurrent(ticket, storeId)) draftError.value = `问答已保存，刷新最新数据失败：${error.message}。请重新加载。`
    }
  } catch (error) {
    if (isCurrent(ticket, storeId)) qaError.value = error.message || '问答保存失败，请重试。'
  } finally {
    if (isCurrent(ticket, storeId)) apiBusy.value = false
  }
}
const removeQa = async (pair) => {
  if (apiBusy.value || readOnlySession.value) return
  const ticket = screenTicket
  const storeId = selectedStoreId.value
  apiBusy.value = true
  qaError.value = ''
  try {
    await saveDraftCore(ticket, storeId)
    if (!isCurrent(ticket, storeId)) return
    await liveApi.deleteQa(activeSessionId.value, pair.apiId, pair.version)
    if (!isCurrent(ticket, storeId)) return
    sessionQaPairs.value = sessionQaPairs.value.filter(item => item.id !== pair.id)
    qaNotice.value = '已删除本场问答；已沉淀至商品库或知识库的内容仍会保留。'
    try { await refreshSessionVersion(ticket, storeId) }
    catch (error) { if (isCurrent(ticket, storeId)) draftError.value = `删除成功，刷新失败：${error.message}。请重新加载。` }
  } catch (error) {
    if (isCurrent(ticket, storeId)) qaError.value = error.message || '删除失败，请重试。'
  } finally {
    if (isCurrent(ticket, storeId)) apiBusy.value = false
  }
}
const editQa = (pair) => {
  qaPersistMode.value = 'SESSION'
  qaError.value = ''
  newQ.value = pair.q
  newA.value = pair.a
  newQaScope.value = pair.scope || 'session'
  editingQaId.value = pair.id
}

const antiRepeat = ref(true)
const replyByCohost = ref(false)
// 和服务端同一条规则：最先设为助播的那一位来回答；没有助播时开关不起作用。
const answeringCohost = computed(() => {
  const id = answeringCohostId({ replyByCohost: replyByCohost.value, voiceRoles: voiceRoleSelection.value })
  return id ? voices.value.find(voice => voice.id === id) || null : null
})

/* ---------------- 场次时长偏好（兼容已有草稿） ---------------- */
const dailyHours = ref(6)

/* ---------------- 03 启动引导 ---------------- */
// 直播间链接和 AI 标识提醒目前没有编辑入口：声音直接在本页播放，页面上只保留一句标识提示。
// 已保存的值原样带回，不会被这次保存清掉。
const roomUrl = ref('')
const aiDisclosure = ref(true)
const formatSessionTime = (value) => {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false })
}
const recordedDuration = computed(() => {
  const { startedAt, endedAt } = currentSession.value || {}
  if (!startedAt || !endedAt) return '—'
  const seconds = Math.floor((new Date(endedAt).getTime() - new Date(startedAt).getTime()) / 1000)
  if (!Number.isFinite(seconds) || seconds < 0) return '—'
  return `${Math.floor(seconds / 3600)}时 ${Math.floor(seconds / 60) % 60}分 ${seconds % 60}秒`
})
const ensureAudioSession = async () => {
  const ticket = screenTicket
  const storeId = selectedStoreId.value
  if (!storeId || loadBusy.value || loadFailed.value || apiBusy.value) throw new Error('请等待门店加载完成')
  if (readOnlySession.value) return activeSessionId.value
  apiBusy.value = true
  try {
    const saved = await saveDraftCore(ticket, storeId)
    if (!saved || !isCurrent(ticket, storeId)) throw new Error('当前门店已切换，请重试')
    return saved.id
  } finally {
    if (isCurrent(ticket, storeId)) apiBusy.value = false
  }
}

/* ---------------- 真实场次草稿与门店隔离 ---------------- */
const draftPayload = () => ({
  name: sessionName.value.trim(),
  roomId: roomUrl.value.trim(),
  productIds: productQueue.value.map(product => product.id),
  config: {
    tone: { opening: tone.value.opening, pain: [...tone.value.pain], detail: [...tone.value.detail] },
    urgency: urgency.value,
    antiRepeat: antiRepeat.value,
    replyByCohost: replyByCohost.value,
    aiDisclosure: aiDisclosure.value,
    dailyHours: dailyHours.value,
    persona: { name: persona.value.name, style: persona.value.style },
    voiceRoles: voiceRoleSelection.value.map(voice => ({ id: voice.id, role: voice.role })),
  },
})
const fingerprint = () => JSON.stringify(draftPayload())
const draftDirty = computed(() => !!savedFingerprint.value && fingerprint() !== savedFingerprint.value)
const applyConfig = (input) => {
  const config = normalizeLiveConfig(input)
  tone.value = { opening: config.tone.opening, pain: [...config.tone.pain], detail: [...config.tone.detail] }
  urgency.value = config.urgency
  antiRepeat.value = config.antiRepeat
  replyByCohost.value = config.replyByCohost === true
  aiDisclosure.value = config.aiDisclosure !== false
  dailyHours.value = config.dailyHours
  persona.value = { name: config.persona.name || '', style: config.persona.style }
  const knownVoice = id => /^(builtin:|sample:)/.test(id) && (voiceLibraryStoreId.value !== selectedStoreId.value || voices.value.some(voice => voice.id === id))
  voiceRoleSelection.value = config.voiceRoles.filter(item => knownVoice(item.id))
  voices.value = withRoles(voices.value)
}
const nextSessionName = () => defaultSessionName(new Date(), liveSessions.value.map(session => session.name))
const resetDraft = () => {
  stage.value = 'setup'
  sessionTools.value?.removeAttribute('open')
  activeSessionId.value = null
  currentSession.value = null
  sessionName.value = nextSessionName()
  roomUrl.value = ''
  productQueue.value = []
  sessionQaPairs.value = []
  qaNotice.value = ''
  cancelQaEdit()
  productPickerOpen.value = false
  manualProductOpen.value = false
  applyConfig(defaultLiveConfig())
  draftNotice.value = ''
  draftError.value = ''
  savedFingerprint.value = fingerprint()
}
const rememberSession = (session) => {
  const index = liveSessions.value.findIndex(item => item.id === session.id)
  if (index >= 0) liveSessions.value.splice(index, 1, session)
  else liveSessions.value.unshift(session)
}
const saveDraftCore = async (ticket, storeId) => {
  if (!storeId || !isCurrent(ticket, storeId)) throw new Error('门店已切换，请重新保存')
  if (readOnlySession.value) throw new Error('这一场已经开始，配置不能再改')
  if (activeSessionId.value && !draftDirty.value) return currentSession.value
  const payload = draftPayload()
  if (!payload.name) throw new Error('请填写场次名称')
  const serialized = JSON.stringify(payload)
  const result = activeSessionId.value
    ? await liveApi.update(activeSessionId.value, { ...payload, version: currentSession.value.version })
    : await liveApi.create(storeId, payload)
  if (!isCurrent(ticket, storeId)) return null
  activeSessionId.value = result.id
  currentSession.value = result
  rememberSession(result)
  savedFingerprint.value = serialized
  return result
}
const saveDraft = async () => {
  if (!auth.requireLogin()) return
  if (apiBusy.value || loadBusy.value || readOnlySession.value) return
  const ticket = screenTicket
  const storeId = selectedStoreId.value
  apiBusy.value = true
  draftError.value = ''
  draftNotice.value = ''
  try {
    await saveDraftCore(ticket, storeId)
    if (isCurrent(ticket, storeId)) draftNotice.value = '配置已保存。'
  } catch (error) {
    if (isCurrent(ticket, storeId)) draftError.value = error.message || '保存失败，请重试。'
  } finally {
    if (isCurrent(ticket, storeId)) apiBusy.value = false
  }
}
const transitionSession = async (action) => {
  if (!auth.requireLogin()) return
  if (apiBusy.value || loadBusy.value || !selectedStoreId.value) return
  const ticket = screenTicket
  const storeId = selectedStoreId.value
  // 浏览器只在点击的当下允许出声，所以趁这次点击先把声音解锁，开始后本页就能直接播放。
  if (action === 'start') audioControl.value?.unlockPlayback()
  apiBusy.value = true
  draftError.value = ''
  try {
    let session = currentSession.value
    if (action === 'start') {
      session = await saveDraftCore(ticket, storeId)
      if (!session || !isCurrent(ticket, storeId)) return
    }
    if (!session?.id) throw new Error('请先保存本场配置')
    if (action === 'end' && !window.confirm('确定结束本系统记录的本场吗？这不会关闭抖音直播，请另外在抖音 App 结束直播。结束后本场不能继续播报。')) return
    if (action === 'end') await audioControl.value?.stopForEnd()
    const result = await liveApi[action](session.id)
    if (!isCurrent(ticket, storeId)) return
    currentSession.value = result
    rememberSession(result)
    savedFingerprint.value = fingerprint()
    if (action === 'start') {
      stage.value = 'live'
      audioControl.value?.startPlayback(result.id)
    }
    if (action === 'end') stage.value = 'review'
    draftNotice.value = action === 'start' ? '本场已开始，播报声音会在本页播放；抖音开播仍需在抖音 App 操作。' : action === 'end' ? '本场记录已结束，播报已停止；请确认已在抖音 App 结束直播。' : ''
  } catch (error) {
    if (isCurrent(ticket, storeId)) draftError.value = error.message || '场次操作失败，请重试。'
  } finally {
    if (isCurrent(ticket, storeId)) apiBusy.value = false
  }
}
/* ---------------- 浏览器里点开始本场：保存配置，唤起一方志软件 ---------------- */
const phone = onPhone()
const onMacDesktop = /Macintosh|Mac OS X/.test(globalThis.navigator?.userAgent || '')
const launchingApp = ref(false)
const appPromptOpen = ref(false)
const appDownloads = ref({ windows: '', mac: '', android: '' })
const launchFromBrowser = async () => {
  if (!auth.requireLogin()) return
  if (launchingApp.value) return
  launchingApp.value = true
  draftError.value = ''
  draftNotice.value = ''
  try {
    // 软件里看到的是服务器上保存的配置，所以先存下来。
    await ensureAudioSession()
    // 手机上没有桌面端，安卓 App 也还不能被网页唤起，直接给下载入口。
    const launched = !phone && await launchApp(desktopLink(selectedStoreId.value))
    if (launched) {
      appPromptOpen.value = false
      draftNotice.value = '配置已保存，已在一方志软件里打开这一场，请在软件里开始直播。'
    } else appPromptOpen.value = true
  } catch (error) {
    draftError.value = `配置还没有保存，所以没有打开一方志软件：${error.message || '请重试'}`
  } finally {
    launchingApp.value = false
  }
}
const startSession = () => (hasWorkbench ? transitionSession('start') : launchFromBrowser())
const pauseSession = () => transitionSession('pause')
const resumeSession = () => transitionSession('resume')
const endSession = () => transitionSession('end')
const commentFeed = computed(() => (realtimeFeed.value.items || []).map(toCommentFeedItem))
/* ---------------- 观众问了但没答上的问题 ---------------- */
const unanswered = ref([])
const gapDraft = ref(null)
const gapBusy = ref(false)
const gapError = ref('')
const gapNotice = ref('')
const loadUnanswered = async () => {
  const id = activeSessionId.value
  gapDraft.value = null
  gapError.value = ''
  gapNotice.value = ''
  if (!id || stage.value !== 'review') { unanswered.value = []; return }
  try {
    const rows = await liveApi.unansweredQuestions(id)
    if (id === activeSessionId.value) unanswered.value = rows
  } catch { unanswered.value = [] /* 清单拿不到时复盘的其余内容照常显示 */ }
}
watch(() => [stage.value, activeSessionId.value], loadUnanswered, { immediate: true })
const openGap = (row) => {
  gapError.value = ''
  gapNotice.value = ''
  gapDraft.value = { key: row.text, question: row.text, answer: '', productId: productQueue.value[0]?.id ?? null }
}
const saveGap = async () => {
  const draft = gapDraft.value
  if (!draft || gapBusy.value) return
  if (!draft.productId) { gapError.value = '请选择这条问答属于哪件商品'; return }
  if (!draft.question.trim() || !draft.answer.trim()) { gapError.value = '请填写问题和回答'; return }
  gapBusy.value = true
  gapError.value = ''
  try {
    await productApi.createFaq(draft.productId, { question: draft.question.trim(), answer: draft.answer.trim() })
    unanswered.value = unanswered.value.filter(row => row.text !== draft.key)
    gapDraft.value = null
    gapNotice.value = '已保存为商品问答。下一场选了这件商品会自动带上。'
  } catch (reason) { gapError.value = reason.message || '保存失败，请重试' }
  finally { gapBusy.value = false }
}
const loadRealtime = async () => {
  if (!activeSessionId.value || stage.value !== 'live') return
  try { realtimeFeed.value = await liveApi.realtime(activeSessionId.value) } catch { /* keep the last feed state visible */ }
}
// 后台按小时预占烟火，续不上会自动暂停本场；这里定期核对状态，免得页面还显示在播、主播已经不说话。
const checkSessionStatus = async () => {
  const id = activeSessionId.value
  if (!id || stage.value !== 'live' || currentStatus.value !== 'LIVE' || apiBusy.value) return
  try {
    const [latest] = await Promise.all([liveApi.get(id), refreshBilling()])
    if (id !== activeSessionId.value || currentStatus.value !== 'LIVE') return
    if (latest.status === 'LIVE') { currentSession.value = latest; return }
    currentSession.value = latest
    rememberSession(latest)
    if (latest.status === 'PAUSED') draftError.value = '烟火不足或会员已到期，本场已自动暂停。充值或续订后点「继续」即可恢复播报。'
  } catch { /* 下一轮再核对 */ }
}
watch(() => [stage.value, activeSessionId.value], ([nextStage, id]) => {
  clearInterval(realtimePoll)
  clearInterval(sessionStatusPoll)
  realtimeFeed.value = { status: 'NOT_READY', message: '', items: [] }
  if (nextStage === 'live' && id) {
    loadRealtime(); realtimePoll = setInterval(loadRealtime, 3000)
    sessionStatusPoll = setInterval(checkSessionStatus, 30000)
  }
}, { immediate: true })
onBeforeUnmount(() => { clearInterval(realtimePoll); clearInterval(sessionStatusPoll); clearInterval(billingClock) })
/* ---------------- 本场烟火 ---------------- */
// 后端给的是响应那一刻的在播秒数；在播时页面自己往上加，每 30 秒核对状态时再校准。
const cost = computed(() => liveCostEstimate(account.summary))
const sessionFetchedAt = ref(Date.now())
const clockNow = ref(Date.now())
let billingClock = null
watch(currentSession, () => { sessionFetchedAt.value = Date.now(); clockNow.value = Date.now() })
watch(() => currentSession.value?.billing?.onAir === true, (onAir) => {
  clearInterval(billingClock)
  if (onAir) billingClock = setInterval(() => { clockNow.value = Date.now() }, 15000)
}, { immediate: true })
const sessionCost = computed(() => liveBillingProgress(currentSession.value?.billing,
  (clockNow.value - sessionFetchedAt.value) / 1000, account.summary?.available ?? null))
// 桌面端是用来开播的：只要这一场还没结束，打开就是直播工作台；配置在旁边的页签里。
const stageFor = (status) => (status === 'ENDED' ? 'review' : hasWorkbench && (isActiveSession({ status }) || inDesktop) ? 'live' : 'setup')
const loadSessionCore = async (id, ticket, storeId) => {
  const [session, rows] = await Promise.all([liveApi.get(id), liveApi.listQa(id)])
  if (!isCurrent(ticket, storeId)) return
  if (session.storeId !== storeId) throw new Error('场次不属于当前门店')
  activeSessionId.value = id
  currentSession.value = session
  rememberSession(session)
  sessionName.value = session.name
  roomUrl.value = session.roomId || ''
  productQueue.value = session.productIds.map(productId => libraryProducts.value.find(product => product.id === productId) || { id: productId, name: '已删除或不可用商品', tag: '请移除后保存', price: '—', source: 'library', faqs: [] })
  sessionQaPairs.value = rows.map(row => toSessionQa(row, productQueue.value))
  applyConfig(session.config)
  savedFingerprint.value = fingerprint()
  // 正在进行的直接进工作台，已结束的看复盘，还没开始的继续配置。
  stage.value = stageFor(session.status)
}
const reloadKnowledge = async () => {
  const storeId = selectedStoreId.value
  if (!storeId) return
  const ticket = ++contextTicket
  contextError.value = ''
  try {
    const context = await knowledgeApi.context(storeId)
    if (ticket === contextTicket && storeId === selectedStoreId.value) inheritedKnowledge.value = context.entries
  } catch (error) {
    if (ticket === contextTicket && storeId === selectedStoreId.value) {
      inheritedKnowledge.value = []
      contextError.value = `门店知识加载失败：${error.message}`
    }
  }
}
const loadStore = async () => {
  const ticket = ++screenTicket
  contextTicket++
  const storeId = selectedStoreId.value
  apiBusy.value = false
  loadBusy.value = true
  loadFailed.value = false
  liveSessions.value = []
  libraryProducts.value = []
  inheritedKnowledge.value = []
  knowledgeSets.value = []
  contextError.value = ''
  resetDraft()
  if (!storeId) { loadBusy.value = false; return }
  try {
    const [products, sessions, sets] = await Promise.all([
      productApi.listAll(storeId), liveApi.list(storeId), knowledgeApi.listSets(storeId),
    ])
    if (!isCurrent(ticket, storeId)) return
    libraryProducts.value = products.map(toLiveProduct)
    liveSessions.value = sessions
    knowledgeSets.value = sets.filter(set => set.kind === 'FAQ' && set.status !== 'ARCHIVED')
    // 有正在进行的就看它；否则看还没开始的那一场；都没有时回看最近一场，由用户决定要不要新建。
    const target = pickCurrentSession(sessions) || sessions[0]
    if (target) await loadSessionCore(target.id, ticket, storeId)
    if (!isCurrent(ticket, storeId)) return
    await reloadKnowledge()
  } catch (error) {
    if (isCurrent(ticket, storeId)) { loadFailed.value = true; draftError.value = `加载失败：${error.message}` }
  } finally {
    if (isCurrent(ticket, storeId)) loadBusy.value = false
  }
}
const openSession = async (id) => {
  sessionTools.value?.removeAttribute('open')
  if (!id || id === activeSessionId.value || apiBusy.value || loadBusy.value) return
  if (switchLocked.value && id !== runningSession.value.id) return
  if (draftDirty.value && !window.confirm('当前配置还没保存，确定放弃修改并切换场次吗？')) return
  const ticket = ++screenTicket
  const storeId = selectedStoreId.value
  loadBusy.value = true
  loadFailed.value = false
  resetDraft()
  try {
    await loadSessionCore(id, ticket, storeId)
  } catch (error) {
    if (isCurrent(ticket, storeId)) { loadFailed.value = true; draftError.value = `场次加载失败：${error.message}` }
  } finally {
    if (isCurrent(ticket, storeId)) loadBusy.value = false
  }
}
// 新的一场沿用上一场的音色、话术风格、商品和本场问答；第一次用时没有可沿用的，就从空白开始。
const newSession = async () => {
  if (!auth.requireLogin()) return
  if (apiBusy.value || loadBusy.value || switchLocked.value) return
  if (pendingSession.value) { await openSession(pendingSession.value.id); return }
  const source = currentSession.value?.id ? currentSession.value : liveSessions.value[0]
  if (!source) { screenTicket++; resetDraft(); return }
  const ticket = ++screenTicket
  const storeId = selectedStoreId.value
  loadBusy.value = true
  draftError.value = ''
  try {
    const created = await liveApi.duplicate(source.id, { name: nextSessionName() })
    if (!isCurrent(ticket, storeId)) return
    resetDraft()
    await loadSessionCore(created.id, ticket, storeId)
    if (isCurrent(ticket, storeId)) draftNotice.value = `已按「${source.name}」的配置新建，可以调整后开始。`
  } catch (error) {
    if (isCurrent(ticket, storeId)) draftError.value = `新建场次失败：${error.message}`
  } finally {
    if (isCurrent(ticket, storeId)) loadBusy.value = false
  }
}
const deleteDraft = async () => {
  if (apiBusy.value || loadBusy.value || currentStatus.value !== 'DRAFT') return
  if (!window.confirm(`删除还没开始的场次「${sessionName.value}」？它的配置和本场问答会一起删除，不能恢复。`)) return
  const id = activeSessionId.value
  if (id) {
    apiBusy.value = true
    draftError.value = ''
    try {
      await liveApi.remove(id)
    } catch (error) {
      draftError.value = `删除失败：${error.message}`
      apiBusy.value = false
      return
    }
  }
  await loadStore()
}
// 开始后配置就锁定了，所以缺主播或缺商品时不让开始，并指给用户去哪里补。
const blockers = computed(() => startBlockers({
  name: sessionName.value,
  hostVoice: hostVoice.value,
  products: productQueue.value,
}))
const sessionBadge = computed(() => {
  if (loadBusy.value) return '加载中…'
  if (currentStatus.value !== 'DRAFT') return describeSession(currentSession.value)
  if (apiBusy.value) return '保存中…'
  return `${sessionStatusLabel('DRAFT')} · ${draftDirty.value ? '有未保存的修改' : activeSessionId.value ? '已保存' : '尚未保存'}`
})
watch(selectedStoreId, loadStore, { immediate: true })
/* ---------------- 被网页版唤起（只在桌面端里） ---------------- */
// 在网页版点“打开桌面端”时，链接带着那边正在配置的门店，软件把它交到这里。
// 换到那家门店；已经在那家门店，就把刚在网页上保存的配置重新读一遍。
let leftPage = false
let stopFollowingLinks = null
const followDesktopLink = async () => {
  let link = null
  try { link = await desktopBridge.link.take() } catch { return }
  if (leftPage || !link?.storeId) return
  // 正在直播时什么都不动：换门店和重新读取都会打断播报。
  if (runningSession.value) return
  const target = stores.value.find(store => String(store.id) === String(link.storeId))
  if (!target) {
    draftError.value = '网页版里正在配置的门店，在这里登录的账号下没有找到。请确认两边登录的是同一个账号；如果是刚新建的门店，把软件关掉重新打开后再试。'
    return
  }
  if (String(target.id) !== String(selectedStoreId.value)) { selectedStoreId.value = target.id; return }
  // 正在读取的本来就是最新的；这里有没保存的修改时不去盖掉它。
  if (loadBusy.value || apiBusy.value || draftDirty.value) return
  await loadStore()
}
onMounted(() => {
  refreshBilling()
  if (!inDesktop) findDesktopDownloads().then(found => { appDownloads.value = found })
  if (typeof route.query.prompt === 'string' && route.query.prompt.trim()) {
    manualProduct.value = { points: route.query.prompt }
    manualProductOpen.value = true
  }
  // 老版本的桌面端没有这项能力。
  if (desktopBridge?.link) {
    followDesktopLink()
    stopFollowingLinks = desktopBridge.link.onOpen(followDesktopLink)
  }
})

onBeforeUnmount(() => {
  leftPage = true
  stopFollowingLinks?.()
  screenTicket++
  contextTicket++
})
</script>

<template>
  <div class="ls-page">
    <header class="ls-head">
      <div class="ls-head-title">
        <h1>实景直播</h1>
        <span class="ls-stage-pill" :class="stage"><i class="status-pulse" />{{ sessionStatusLabel(currentStatus) }}</span>
      </div>
      <div class="ls-head-actions" aria-label="场次">
        <details ref="sessionTools" class="ls-session-tools" @keydown.esc="sessionTools?.removeAttribute('open')">
          <summary :title="sessionName"><span>{{ sessionName.trim() || '未命名场次' }}</span><ChevronDown :size="14" aria-hidden="true" /></summary>
          <div class="ls-session-menu">
            <label v-if="currentStatus === 'DRAFT'" class="ls-session-rename"><span>本场名称</span><input v-model="sessionName" aria-label="场次名称" maxlength="160" :disabled="loadBusy || loadFailed || apiBusy || !selectedStoreId" placeholder="为本场直播起个名字"></label>
            <p class="ls-session-menu-label">全部场次</p>
            <p v-if="!liveSessions.length" class="ls-session-hint">还没有保存过场次。</p>
            <ul v-else class="ls-session-list">
              <li v-for="session in liveSessions" :key="session.id">
                <button type="button" :class="{ current: session.id === activeSessionId }" :aria-current="session.id === activeSessionId ? 'true' : undefined" :disabled="loadBusy || apiBusy || (switchLocked && session.id !== runningSession.id)" @click="openSession(session.id)">
                  <strong>{{ session.name }}</strong><small>{{ describeSession(session) }}</small>
                </button>
              </li>
            </ul>
            <p v-if="switchLocked" class="ls-session-hint">直播进行中，结束本场后才能查看其他场次。</p>
            <button v-if="currentStatus === 'DRAFT' && activeSessionId" class="ls-session-delete" type="button" :disabled="loadBusy || apiBusy" @click="deleteDraft">删除这一场</button>
          </div>
        </details>
        <span class="ls-save-state" :class="{ dirty: currentStatus === 'DRAFT' && draftDirty, live: currentStatus === 'LIVE' }" role="status">{{ sessionBadge }}</span>
        <template v-if="currentStatus === 'DRAFT'">
          <button class="ls-ghost compact" type="button" :disabled="loadBusy || loadFailed || apiBusy || !selectedStoreId || (!!activeSessionId && !draftDirty)" @click="saveDraft">保存配置</button>
          <button class="primary-button compact" type="button" :disabled="loadBusy || loadFailed || apiBusy || launchingApp || !selectedStoreId || blockers.length > 0" :title="blockers.length ? '还需要：' + blockers.map(item => item.label).join('、') : ''" @click="startSession">开始本场</button>
        </template>
        <button v-if="hasWorkbench && currentStatus === 'LIVE'" class="ls-ghost compact" type="button" :disabled="loadBusy || apiBusy" @click="pauseSession">暂停本场</button>
        <button v-if="hasWorkbench && currentStatus === 'PAUSED'" class="primary-button compact" type="button" :disabled="loadBusy || apiBusy" @click="resumeSession">继续本场</button>
        <button v-if="['LIVE', 'PAUSED'].includes(currentStatus)" class="ls-danger compact" type="button" :disabled="loadBusy || apiBusy" @click="endSession">结束本场</button>
        <template v-if="currentStatus === 'ENDED'">
          <button v-if="runningSession || pendingSession" class="primary-button compact" type="button" :disabled="loadBusy || apiBusy" @click="openSession((runningSession || pendingSession).id)">{{ runningSession ? '回到直播中的场次' : '回到未开始的场次' }}</button>
          <button v-else class="primary-button compact" type="button" :disabled="loadBusy || loadFailed || apiBusy || !selectedStoreId" @click="newSession">新建场次</button>
        </template>
      </div>
    </header>
    <nav class="ls-stage-nav" aria-label="直播工作流程">
      <button v-for="tab in stageTabs" :key="tab.key" type="button" :class="{ active: stage === tab.key }" :aria-pressed="stage === tab.key" @click="setStage(tab.key)">{{ tab.label }}</button>
    </nav>
    <div v-if="draftError || draftNotice || (readOnlySession && stage === 'setup')" class="ls-head-messages">
      <p v-if="draftError" class="ls-qa-error" role="alert">{{ draftError }}</p>
      <p v-if="draftNotice" class="ls-qa-notice" role="status">{{ draftNotice }}</p>
      <p v-if="readOnlySession && stage === 'setup'" class="ls-section-note">{{ currentStatus === 'ENDED' ? '这一场已结束，配置仅供查看。' : '直播进行中，配置已锁定。需要调整时请先结束本场，再新建场次。' }}</p>
    </div>
    <div class="ls-setup-layout" :class="{ split: stage === 'setup' }">
      <fieldset v-show="stage === 'setup'" class="ls-configuration-fields" :disabled="loadBusy || loadFailed || apiBusy || readOnlySession">
        <VoiceLibraryPanel
          id="ls-step-voice"
          v-model:persona="persona"
          :store-id="selectedStoreId"
          :voice-roles="voiceRoleSelection"
          @loaded="updateVoiceLibrary"
          @change="updateVoiceSelection"
        />

        <!-- 02 商品与讲解方式 -->
        <section id="ls-step-script" class="panel ls-section">
          <div class="ls-card-head"><span class="mono">02</span><h3>商品与讲解方式</h3></div>
          <div class="ls-product-bar">
            <span>本场商品 {{ productQueue.length }} 件 · 按顺序循环讲解</span>
            <div class="ls-product-bar-actions"><button type="button" class="ls-outline-button" @click="openProductPicker">＋ 从商品库添加</button><button type="button" class="ls-outline-button" @click="openManualProduct">＋ 手动添加</button></div>
          </div>
          <p v-if="!productQueue.length" class="ls-product-none">还没有添加商品，请从商品库选择或手动添加。</p>
          <div v-else class="ls-product-rows">
            <div v-for="(product, index) in productQueue" :key="product.id" class="ls-product-item">
              <span class="ls-product-no mono">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="ls-product-copy"><strong>{{ product.name }}</strong><small>{{ product.tag }} · {{ product.price }}</small></span>
              <div class="ls-product-order">
                <button type="button" :disabled="index === 0" aria-label="上移商品" @click="moveProduct(index, -1)">↑</button>
                <button type="button" :disabled="index === productQueue.length - 1" aria-label="下移商品" @click="moveProduct(index, 1)">↓</button>
                <button type="button" aria-label="移除商品" @click="removeProduct(index)">×</button>
              </div>
            </div>
          </div>

          <div v-if="productPickerOpen" class="ls-modal-backdrop" role="presentation" @click.self="closeProductDialogs">
            <section class="ls-modal panel-dark ls-product-modal" role="dialog" aria-modal="true" aria-labelledby="ls-product-picker-title">
              <h3 id="ls-product-picker-title">从商品库选择</h3>
              <p class="ls-modal-copy">可多选商品，加入本场清单后调整讲解顺序。</p>
              <input v-model="productSearch" class="ls-product-search" type="search" placeholder="搜索商品名称、分类或价格…">
              <div class="ls-product-list ls-product-modal-list">
                <label v-for="p in filteredLibraryProducts" :key="p.id" class="ls-product-row" :class="{ on: pickedProductIds.includes(p.id) }">
                  <input type="checkbox" :checked="pickedProductIds.includes(p.id)" @change="toggleLibraryProduct(p)">
                  <span class="ls-radio" />
                  <span class="ls-product-copy"><strong>{{ p.name }}</strong><small>{{ p.tag }}</small></span>
                  <span class="mono ls-product-price">{{ p.price }}</span>
                </label>
                <p v-if="!filteredLibraryProducts.length" class="ls-product-empty">没有匹配的商品。</p>
              </div>
              <div class="ls-modal-actions"><button type="button" class="primary-button compact" @click="closeProductDialogs">完成选择（{{ pickedProductIds.length }}）</button></div>
            </section>
          </div>
          <ProductFormModal v-if="manualProductOpen" :product="manualProduct" :error="manualProductError" :busy="apiBusy || loadBusy" @save="addManualProduct" @close="closeProductDialogs" />

          <div class="ls-tone">
            <div v-for="group in toneGroups" :key="group.key" class="ls-tone-row">
              <span class="ls-tone-label">{{ group.label }}</span>
              <div class="ls-tone-options">
                <button v-for="opt in group.options" :key="opt" type="button" :class="{ on: isToneSelected(group.key, opt) }" @click="selectTone(group.key, opt)">{{ opt }}</button>
              </div>
            </div>
            <div class="ls-tone-row ls-slider-row">
              <span class="ls-tone-label">促单节奏</span>
              <div class="ls-slider">
                <input v-model.number="urgency" type="range" min="1" max="5" step="0.01">
                <span class="ls-slider-value">{{ urgencyLabel }}</span>
              </div>
            </div>
          </div>

          <label class="ls-toggle-box">
            <span class="ls-switch"><input v-model="antiRepeat" type="checkbox"><span /></span>
            <span class="ls-toggle-copy"><strong>避免重复话术</strong><small>开启后，自动讲解会参考刚讲过的内容换一个角度表达。你手动填写的播报内容不会被改写。</small></span>
          </label>
        </section>

        <!-- 03 弹幕问答 -->
        <section id="ls-step-qa" class="panel ls-section">
          <div class="ls-card-head">
            <span class="mono">03</span><h3>弹幕问答</h3>
            <button type="button" class="ls-card-link ls-card-head-side" :aria-expanded="qaHelpOpen" aria-controls="ls-qa-help" @click="qaHelpOpen = !qaHelpOpen">如何回答？</button>
          </div>
          <div v-show="qaHelpOpen" id="ls-qa-help" class="ls-qa-help">
            <p>为本场准备常见问题与指定回复。观众问到这些问题时（问法不同也算），系统会把你写的回复改成口语说出来，意思和数字不变；没有对应问答时依据商品资料回答，资料里没有的不回答。观众说想买时会顺势引导下单；夸奖、闲聊不回，询问主播身份的也一律不回；投诉、说吃了不舒服、要退款这类不会自动回复，会在弹幕流里标出来等你处理。真实抖音弹幕尚未接入。</p>
            <p>门店通用来自知识库已启用规则；本场问答优先于商品问答与门店通用规则。</p>
          </div>
          <label class="ls-toggle-box first">
            <span class="ls-switch"><input v-model="replyByCohost" type="checkbox" :disabled="!cohostVoices.length"><span /></span>
            <span class="ls-toggle-copy">
              <strong>由助播回答弹幕</strong>
              <small v-if="!cohostVoices.length" class="need">需要先在“主播与声音”里设一位助播。不开启时，观众的提问由主播自己回答。</small>
              <small v-else-if="answeringCohost">观众的提问由助播「{{ answeringCohost.name }}」回答，答完交回主播。这位助播不再参与轮流讲解{{ cohostVoices.length > 1 ? '，其余助播照常轮流' : '，讲解全部由主播完成' }}。</small>
              <small v-else>开启后，观众的提问由最先设为助播的那一位回答，主播专心讲解；这位助播不再参与轮流讲解。</small>
            </span>
          </label>
          <p v-if="readOnlySession" class="ls-section-note">以下为场次启动时保存的知识快照。</p>
          <p v-if="contextError" class="ls-qa-error" role="alert">{{ contextError }} <button type="button" class="ls-ghost compact" @click="reloadKnowledge">重试</button></p>
          <div class="ls-qa-filters" role="tablist" aria-label="问答范围">
            <button v-for="filter in qaFilters" :key="filter.value" type="button" role="tab" :aria-selected="qaFilter === filter.value" :class="{ on: qaFilter === filter.value }" @click="qaFilter = filter.value">{{ filter.label }} {{ filter.count }}</button>
          </div>
          <p v-if="!visibleQaPairs.length" class="ls-section-note">暂无问答，可在下方添加。</p>
          <ul v-else class="ls-qa-rows">
            <li v-for="item in visibleQaPairs" :key="item.id" class="ls-qa-row">
              <div v-if="editingQaId === item.id" class="ls-qa-inline-edit">
                <input v-model="newQ" maxlength="500" type="text" aria-label="编辑观众问题" placeholder="观众可能会问…">
                <textarea v-model="newA" maxlength="4000" aria-label="编辑回复话术" rows="2" placeholder="希望如何回答…" @keyup.ctrl.enter="addQa" />
                <div class="ls-qa-inline-actions">
                  <button type="button" class="ls-ghost compact" @click="cancelQaEdit">取消</button>
                  <button type="button" class="primary-button compact" @click="addQa">保存</button>
                </div>
              </div>
              <template v-else>
                <div class="ls-qa-row-copy"><strong>{{ item.q }}</strong><p>{{ item.a }}</p></div>
                <div class="ls-qa-row-side">
                  <span class="ls-qa-tag">{{ item.source }}{{ item.persistMode === 'PRODUCT_FAQ' ? ' · 已存商品库' : item.persistMode === 'STORE_KNOWLEDGE' ? ' · 已存知识库' : '' }}</span>
                  <div v-if="item.sourceType === 'session'" class="ls-qa-row-actions">
                    <button type="button" :aria-label="`编辑 ${item.q}`" @click="editQa(item)">编辑</button>
                    <button type="button" :aria-label="`删除 ${item.q}`" @click="removeQa(item)">删除</button>
                  </div>
                </div>
              </template>
            </li>
          </ul>
          <div v-if="!editingQaId" class="ls-qa-new">
            <strong>添加问答</strong>
            <input v-model="newQ" maxlength="500" type="text" aria-label="观众问题" placeholder="观众可能会问：比如「可以外卖吗」">
            <textarea v-model="newA" maxlength="4000" rows="2" aria-label="回复话术" placeholder="希望如何回答" @keyup.ctrl.enter="addQa" />
            <div class="ls-qa-new-foot">
              <label>保存到
                <AppSelect v-model="qaTarget" aria-label="这条问答保存位置">
                  <option value="session">仅本场</option>
                  <option value="store">门店通用</option>
                  <option v-for="product in productQueue" :key="product.id" :value="product.id">{{ product.name }}</option>
                </AppSelect>
              </label>
              <AppSelect v-if="qaPersistMode === 'STORE_KNOWLEDGE'" v-model="qaKnowledgeSetId" aria-label="目标门店知识集">
                <option value="" disabled>选择问答知识集</option>
                <option v-for="set in knowledgeSets" :key="set.id" :value="set.id">{{ set.name }}</option>
              </AppSelect>
              <button type="button" class="primary-button compact" @click="addQa">添加</button>
            </div>
            <small v-if="qaPersistMode === 'STORE_KNOWLEDGE'">{{ knowledgeSets.length ? '存入后默认停用，到知识库确认并启用后才会用于之后的场次。' : '请先到「资产中心 / 知识库」新建问答集。' }}</small>
          </div>
          <p v-if="qaNotice" class="ls-qa-notice" role="status">{{ qaNotice }}</p>
          <p v-if="qaError" class="ls-qa-error" role="alert">{{ qaError }}</p>
        </section>
      </fieldset>
      <section v-if="stage === 'live'" class="panel ls-live-bar" aria-label="本场准备情况">
        <div class="ls-live-metric ls-live-voice-metric"><p class="eyebrow">主讲音色</p><strong>{{ hostVoice?.name || '未选择' }}</strong><small>当前配置的播报声音</small></div>
        <div class="ls-live-metric"><p class="eyebrow">本场商品</p><strong>{{ productQueue.length }} <small>件</small></strong><small>已选商品与讲解顺序</small></div>
        <div class="ls-live-metric"><p class="eyebrow">互动知识库</p><strong>{{ qaPairs.length }} <small>条</small></strong><small>{{ readOnlySession ? '本场知识快照' : '当前配置的问答' }}</small></div>
        <div class="ls-live-actions"><button type="button" class="ls-ghost compact" @click="editStep('voice')">{{ readOnlySession ? '查看配置' : '调整配置' }}</button><button type="button" class="ls-ghost compact" @click="setStage('review')">查看场次复盘</button></div>
      </section>
      <LiveAudioControl
        ref="audioControl"
        :key="selectedStoreId"
        :view="stage"
        :session-id="activeSessionId"
        :store-id="selectedStoreId"
        :session-status="currentSession?.status"
        :session-error="draftError"
        :blockers="blockers"
        :ensure-session="ensureAudioSession"
        :host-voice="hostVoice"
        :products="productQueue"
        :workbench="hasWorkbench"
        @open-workspace="setStage('live')"
        @start-session="startSession"
        @edit-step="editStep"
      />
      <aside v-if="stage === 'setup'" class="panel ls-precheck" aria-label="开播前检查">
        <h3>开播前检查</h3>
        <ul class="ls-check-list">
          <li v-if="!sessionName.trim()" class="todo"><i aria-hidden="true" /><span>场次名称未填写</span></li>
          <li :class="hostReady ? 'ok' : 'todo'"><i aria-hidden="true"><Check :size="11" :stroke-width="3" /></i><span>主播音色：{{ hostVoice ? hostVoice.name + (hostReady ? '' : '（不可用）') : '未选择' }}</span><button v-if="!hostReady && !readOnlySession" type="button" @click="editStep('voice')">去设置</button></li>
          <li :class="productQueue.length ? 'ok' : 'todo'"><i aria-hidden="true"><Check :size="11" :stroke-width="3" /></i><span>本场商品 {{ productQueue.length }} 件</span><button v-if="!productQueue.length && !readOnlySession" type="button" @click="editStep('script')">去添加</button></li>
          <li v-if="cost && currentStatus === 'DRAFT'" :class="cost.problem ? 'todo' : 'ok'"><i aria-hidden="true"><Check :size="11" :stroke-width="3" /></i><span>{{ cost.problem || `烟火余额 ${cost.available}，约可播 ${cost.playable}` }}</span><RouterLink v-if="cost.problem" :to="account.summary.memberActive ? '/billing?topup=1' : '/billing'">{{ account.summary.memberActive ? '去充值' : '开通会员' }}</RouterLink></li>
          <li :class="{ ok: !!answeringCohost }"><i aria-hidden="true"><Check :size="11" :stroke-width="3" /></i><span>{{ answeringCohost ? `助播回答弹幕：${answeringCohost.name}` : '助播回答弹幕未开启（可选）' }}</span></li>
        </ul>
        <dl class="ls-precheck-facts">
          <div><dt>称呼</dt><dd>{{ persona.name || '未设置' }}</dd></div>
          <div><dt>风格</dt><dd>{{ persona.style }}</dd></div>
          <div><dt>助播</dt><dd>{{ cohostVoices.length ? cohostVoices.map(voice => voice.name).join('、') : '无' }}</dd></div>
          <div><dt>节奏</dt><dd>{{ urgencyLabel.split(' · ')[0] }}</dd></div>
          <div><dt>问答</dt><dd>{{ qaPairs.length }} 条</dd></div>
        </dl>
        <p v-if="cost && currentStatus === 'DRAFT'" class="ls-cost-rule">直播每小时 {{ cost.perHour }} 烟火，按实际在播的分钟算，暂停不计。开播先预占一小时，快播满时再预占下一小时，下播后没用完的退回。</p>
        <button v-if="currentStatus === 'DRAFT'" class="primary-button" type="button" :disabled="loadBusy || loadFailed || apiBusy || launchingApp || !selectedStoreId || blockers.length > 0" @click="startSession">开始本场<span v-if="cost" class="ls-cost-tag"><YanhuoIcon :size="13" />预占 {{ cost.perHour }}</span></button>
        <button v-else-if="hasWorkbench && currentStatus !== 'ENDED'" class="primary-button" type="button" @click="setStage('live')">前往直播工作台</button>
        <p v-else-if="currentStatus !== 'ENDED'" class="ls-precheck-live">本场正在一方志软件里直播。</p>
      </aside>
    </div>

    <section v-if="stage === 'live'" class="ls-live-grid" aria-label="直播工作台">
      <div class="ls-live-main">
        <article class="panel ls-feed-panel">
          <div class="panel-heading"><div><h3>弹幕流与自动回复</h3></div><span class="ls-pending-badge" :class="{ ready: commentFeed.length }">{{ commentFeed.length ? `${commentFeed.length} 条` : '暂无弹幕' }}</span></div>
          <p v-if="inDesktop" class="ls-card-note">连接直播间后，这里显示从直播间读到的弹幕和自动回复。</p>
          <p v-else class="ls-card-note">真实抖音弹幕尚未接入，这里显示的是下方“模拟弹幕互动”发出的问题与回复。</p>
          <div v-if="commentFeed.length" class="ls-realtime-feed"><article v-for="item in commentFeed" :key="item.id" class="ls-realtime-item" :class="{ attention: item.needsPerson }"><div class="ls-realtime-line"><span class="ls-realtime-kind">{{ item.providerLabel || '观众' }}</span><strong>{{ item.question }}</strong><small class="ls-realtime-state" :class="item.tone">{{ item.stateLabel }}</small></div><div v-if="item.answer" class="ls-realtime-line"><span class="ls-realtime-kind reply">回复</span><p>{{ item.answer }}</p><small v-if="item.sourceLabel">{{ item.sourceLabel }}</small></div><p v-else-if="item.note" class="ls-realtime-note">{{ item.note }}</p></article></div><div v-else class="ls-workspace-empty"><MessageSquare :size="32" :stroke-width="1.4" aria-hidden="true" /><strong>还没有弹幕</strong><p>在“开发测试 · 模拟弹幕互动”里发一条问题：与知识库文字一致的直接用原话回复，其余依据本场问答和商品资料自动回答。</p><button type="button" class="ls-ghost compact" @click="editStep('qa')">查看互动知识库</button></div>
        </article>
      </div>
      <aside class="ls-live-side">
        <article class="panel ls-selected-products">
          <div class="panel-heading"><div><h3>本场商品</h3></div><Package :size="20" aria-hidden="true" /></div>
          <ol v-if="productQueue.length" class="ls-workspace-products"><li v-for="(product, index) in productQueue" :key="product.id"><span class="ls-product-index">{{ index + 1 }}</span><span><strong>{{ product.name }}</strong><small>{{ product.tag }} · {{ product.price }}</small></span></li></ol>
          <p v-else class="ls-section-note">还没有选择本场商品。</p><button type="button" class="ls-text-action" @click="editStep('script')">{{ readOnlySession ? '查看商品配置' : '管理本场商品' }}</button>
        </article>
        <article v-if="sessionCost" class="panel ls-session-cost">
          <div class="panel-heading"><h3>本场烟火</h3><YanhuoIcon :size="18" /></div>
          <dl class="ls-precheck-facts">
            <div><dt>已播时长</dt><dd>{{ sessionCost.duration }}{{ currentStatus === 'PAUSED' ? '（已暂停）' : '' }}</dd></div>
            <div><dt>已用</dt><dd>约 {{ sessionCost.used }} 烟火</dd></div>
            <div><dt>已预占</dt><dd>{{ sessionCost.reserved }} 烟火</dd></div>
            <div v-if="account.summary"><dt>账户余额</dt><dd>{{ account.summary.available }} 烟火</dd></div>
          </dl>
          <p v-if="sessionCost.pauseInMinutes != null" class="ls-cost-warning">余额不够续下一小时，约 {{ sessionCost.pauseInMinutes }} 分钟后会自动暂停，在那之前充值会自动续上。<RouterLink to="/billing?topup=1">去充值</RouterLink></p>
          <p v-else class="ls-card-note">每小时 {{ sessionCost.perHour }} 烟火，按分钟计，暂停不计。下播后没用完的退回。</p>
        </article>
        <article class="panel ls-platform-summary"><div class="panel-heading"><h3>直播间数据</h3><span class="ls-pending-badge">未接入</span></div><div class="ls-platform-metrics"><span>在线人数<strong>—</strong></span><span>点赞<strong>—</strong></span><span>新增关注<strong>—</strong></span></div><p class="ls-card-note">等待平台数据接入后展示真实统计。</p></article>
      </aside>
    </section>

    <template v-if="stage === 'review'">
      <section class="ls-review panel">
        <div class="panel-heading"><div><h2>场次复盘</h2></div><BarChart3 :size="24" :stroke-width="1.5" aria-hidden="true" /></div>
        <p class="ls-review-intro">{{ currentSession ? `「${currentSession.name}」的场次概览。` : '保存本场配置后，可在这里查看场次概览。' }}观看、互动与转化数据尚未接入，暂不能生成运营报告。</p>
        <div class="ls-review-grid"><article v-for="metric in ['观看人数', '弹幕互动', '新增关注', '成交转化']" :key="metric" class="ls-review-card"><p class="eyebrow">{{ metric }}</p><strong>—</strong><small>平台数据未接入</small></article></div>
        <div v-if="currentSession" class="ls-review-details">
          <div><h3>场次记录</h3><dl class="ls-fact-list"><div><dt>场次状态</dt><dd>{{ sessionStatusLabel(currentSession.status) }}</dd></div><div><dt>本系统开始时间</dt><dd>{{ formatSessionTime(currentSession.startedAt) }}</dd></div><div><dt>本系统结束时间</dt><dd>{{ formatSessionTime(currentSession.endedAt) }}</dd></div><div><dt>本系统记录时长</dt><dd>{{ recordedDuration }}</dd></div><div v-if="sessionCost && readOnlySession"><dt>在播时长（不含暂停）</dt><dd>{{ sessionCost.duration }}</dd></div><div v-if="sessionCost && sessionCost.reserved > 0 && currentSession.status === 'ENDED'"><dt>本场烟火</dt><dd>扣 {{ sessionCost.used }} · 退回 {{ sessionCost.refunded }} <RouterLink to="/billing">查看账单</RouterLink></dd></div><div><dt>已保存商品</dt><dd>{{ currentSession.productIds?.length || 0 }} 件</dd></div><div><dt>知识快照</dt><dd>{{ readOnlySession ? `${currentSession.knowledge?.length || 0} 条` : '草稿尚未生成快照' }}</dd></div></dl></div>
          <div><h3>播报准备</h3><dl class="ls-fact-list"><div><dt>本场主讲</dt><dd>{{ hostVoice?.name || '未选择' }}</dd></div><div><dt>播报方式</dt><dd>网页直接播放</dd></div></dl><p class="ls-card-note">此处时间来自本系统的场次记录，不等同于抖音实际开播时长。{{ draftDirty ? '当前还有未保存的配置，播报准备以页面当前选择为准。' : '' }}</p></div>
        </div>
        <div v-if="currentSession && readOnlySession" class="ls-gaps">
          <h3>观众问了但没答上的问题<small v-if="unanswered.length"> · {{ unanswered.length }} 个</small></h3>
          <p class="ls-card-note">这些提问在本场资料里找不到答案，未自动回复。补成商品问答后，下一场选了这件商品会自动带上；地址、营业时间这类门店通用的问题请到知识库添加。目前只有模拟弹幕会进这份清单。</p>
          <p v-if="gapNotice" class="ls-gap-notice" role="status">{{ gapNotice }}</p>
          <ul v-if="unanswered.length" class="ls-gap-list">
            <li v-for="row in unanswered" :key="row.text">
              <div class="ls-gap-row"><strong>{{ row.text }}</strong><small v-if="row.count > 1">问了 {{ row.count }} 次</small><button v-if="gapDraft?.key !== row.text" type="button" class="ls-ghost compact" :disabled="!productQueue.length || gapBusy" @click="openGap(row)">补成问答</button></div>
              <form v-if="gapDraft?.key === row.text" class="ls-gap-form" @submit.prevent="saveGap">
                <label><span>问题</span><input v-model="gapDraft.question" maxlength="1000"></label>
                <label><span>回答</span><textarea v-model="gapDraft.answer" rows="2" maxlength="4000" placeholder="像对顾客说话那样写，系统会照这个意思用口语回答" /></label>
                <label><span>属于哪件商品</span><AppSelect v-model="gapDraft.productId"><option v-for="product in productQueue" :key="product.id" :value="product.id">{{ product.name }}</option></AppSelect></label>
                <p v-if="gapError" class="ls-gap-error" role="alert">{{ gapError }}</p>
                <div class="ls-gap-actions"><button type="submit" class="primary-button compact" :disabled="gapBusy">{{ gapBusy ? '保存中…' : '保存为商品问答' }}</button><button type="button" class="ls-ghost compact" :disabled="gapBusy" @click="gapDraft = null">取消</button></div>
              </form>
            </li>
          </ul>
          <p v-else class="ls-section-note">这一场没有答不上的提问。</p>
        </div>
        <div class="ls-review-actions"><button v-if="hasWorkbench && currentStatus !== 'ENDED'" type="button" class="ls-ghost compact" @click="setStage('live')">返回直播工作台</button><button v-if="currentStatus === 'ENDED' && !runningSession && !pendingSession" type="button" class="primary-button compact" :disabled="loadBusy || loadFailed || apiBusy || !selectedStoreId" @click="newSession">新建场次（沿用这一场的配置）</button></div>
      </section>
    </template>

    <div v-if="appPromptOpen" class="ls-modal-backdrop" role="presentation" @click.self="appPromptOpen = false">
      <section class="ls-modal panel-dark ls-app-modal" role="dialog" aria-modal="true" aria-labelledby="ls-app-title">
        <h3 id="ls-app-title">{{ phone ? '用一方志 App 开播' : '没有打开一方志软件' }}</h3>
        <p v-if="phone" class="ls-modal-copy">开播要在一方志 App 里进行，网页读不到直播间弹幕。配置已经保存，装好 App、登录同一个账号，打开就是这一场。目前只有安卓版。</p>
        <p v-else class="ls-modal-copy">开播要在一方志软件里进行，网页读不到直播间弹幕。这台电脑可能还没安装：下载安装、登录同一个账号后，回到这里再点一次「开始本场」。已经装好的话，点「重新打开」。</p>
        <div class="ls-app-downloads">
          <template v-if="!phone">
            <a v-if="appDownloads.windows" :class="onMacDesktop && appDownloads.mac ? 'ls-ghost compact' : 'primary-button compact'" :href="appDownloads.windows" download>下载 Windows 版</a>
            <a v-if="appDownloads.mac" :class="onMacDesktop || !appDownloads.windows ? 'primary-button compact' : 'ls-ghost compact'" :href="appDownloads.mac" download>下载 Mac 版</a>
          </template>
          <a v-if="appDownloads.android" :class="phone ? 'primary-button compact' : 'ls-ghost compact'" href="/app" target="_blank" rel="noopener">安卓 App 下载页</a>
        </div>
        <p v-if="!appDownloads.android && (phone || (!appDownloads.windows && !appDownloads.mac))" class="ls-modal-copy">安装包暂时还没有放出来，请联系我们获取。</p>
        <div class="ls-modal-actions">
          <button type="button" class="ls-ghost compact" @click="appPromptOpen = false">关闭</button>
          <button v-if="!phone" type="button" class="primary-button compact" :disabled="launchingApp" @click="launchFromBrowser">{{ launchingApp ? '正在打开…' : '重新打开' }}</button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.ls-configuration-fields { min-width: 0; margin: 0; padding: 0; border: 0; }
.ls-configuration-fields:disabled { opacity: .72; }
.ls-session-rename { display: grid; gap: 6px; margin-bottom: 14px; padding-bottom: 14px; border-bottom: 1px solid var(--line); color: var(--ink-muted); font-size: 12px; }
.ls-session-rename input { width: 100%; min-width: 0; height: 34px; padding: 0 10px; border: 1px solid var(--line); border-radius: 7px; outline: 0; background: var(--color-bg-surface); color: var(--ink); font: 500 13px var(--font-sans); }
.ls-session-rename input:focus { border-color: var(--cinnabar); }
.ls-session-menu-label { margin: 0 2px 6px; color: var(--ink-muted); font-size: 12px; }
.ls-session-delete { display: block; width: 100%; margin-top: 12px; padding: 10px 2px 0; border: 0; border-top: 1px solid var(--line); background: transparent; color: var(--red); font-size: 12px; text-align: left; }
.ls-session-delete:hover:not(:disabled) { text-decoration: underline; }
.ls-save-state { font-size: 12px; color: var(--ink-muted); white-space: nowrap; }
.ls-save-state.dirty { color: var(--color-primary); }
.ls-save-state.live { color: var(--color-success); }
.ls-session-tools { position: relative; font-size: 12px; }
.ls-session-tools summary { display: flex; align-items: center; gap: 8px; height: 36px; max-width: 240px; padding: 0 10px 0 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--color-bg-surface); color: var(--ink); font-size: 13px; cursor: pointer; list-style: none; }
.ls-session-tools summary > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ls-session-tools summary > svg { flex: none; color: var(--ink-muted); }
.ls-session-tools summary::-webkit-details-marker { display: none; }
.ls-session-tools summary:focus-visible, .ls-stage-nav button:focus-visible, .ls-text-action:focus-visible { outline: 2px solid var(--cinnabar); outline-offset: 3px; }
.ls-session-tools[open] summary svg { transform: rotate(180deg); }
.ls-session-menu { position: absolute; right: 0; top: calc(100% + 8px); z-index: 20; width: min(320px, calc(100vw - 48px)); padding: 18px; border: 1px solid var(--line); border-radius: 12px; background: var(--color-bg-surface); box-shadow: var(--shadow-paper); }
.ls-session-list { display: grid; gap: 4px; max-height: 320px; margin: 0; padding: 0; overflow-y: auto; list-style: none; }
.ls-session-list button { display: grid; gap: 4px; width: 100%; padding: 9px 10px; border: 0; border-radius: 8px; background: transparent; color: var(--ink); text-align: left; cursor: pointer; }
.ls-session-list button:hover:not(:disabled) { background: var(--color-bg-subtle); }
.ls-session-list button.current { background: color-mix(in srgb, var(--color-accent) 7%, transparent); }
.ls-session-list button:disabled { opacity: .5; cursor: not-allowed; }
.ls-session-list strong { overflow: hidden; font-size: 13px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.ls-session-list small { color: var(--ink-muted); font-size: 12px; }
.ls-session-hint { margin: 8px 2px 0; color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
.ls-stage-nav { display: flex; gap: 28px; margin: 0 0 20px; border-bottom: 1px solid var(--line); }
.ls-stage-nav button { display: flex; align-items: center; gap: 8px; padding: 0 2px 12px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--ink-muted); font: 500 14px var(--font-sans); cursor: pointer; white-space: nowrap; }
.ls-stage-nav button.active { color: var(--color-primary); border-bottom-color: var(--cinnabar); }
.ls-client-placeholder { display: grid; place-content: center; flex: 0 0 80px; height: 80px; border: 1px dashed var(--line-strong); border-radius: 10px; text-align: center; color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
.ls-qa-destination :deep(.app-select-button) { min-width: 0; max-width: 100%; border: 1px solid var(--line-strong); border-radius: 8px; padding: 9px 10px; color: var(--ink); background: var(--night-panel); }
.ls-connection-note { margin: 0 0 18px; font-size: 12px; line-height: 1.8; color: var(--ink-muted); }
.ls-qa-destination { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 12px; font-size: 12px; color: var(--ink-muted); }
.ls-qa-destination label { display: flex; align-items: center; gap: 8px; }
.ls-qa-destination small { flex-basis: 100%; }
.ls-live-main { display: grid; gap: 13px; min-width: 0; }
.ls-workspace-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 300px; padding: 32px 24px; color: var(--ink-muted); text-align: center; }
.ls-workspace-empty > svg { margin-bottom: 18px; color: var(--color-primary); opacity: .65; }
.ls-workspace-empty > strong { color: var(--ink-soft); font-size: 15px; font-weight: 500; }
.ls-workspace-empty > p { max-width: 320px; margin: 12px 0 20px; font-size: 13px; line-height: 1.8; }
.ls-pending-badge { display: inline-flex; flex-shrink: 0; align-items: center; padding: 4px 9px; border: 1px solid var(--line); border-radius: 99px; font-size: 12px; line-height: 1.4; color: var(--ink-muted); background: var(--color-bg-subtle); }
.ls-fact-list { margin: 18px 0 0; display: grid; gap: 12px; font-size: 12px; }
.ls-fact-list > div { display: flex; justify-content: space-between; align-items: baseline; gap: 18px; }
.ls-fact-list dt { color: var(--ink-muted); flex-shrink: 0; }
.ls-fact-list dd { margin: 0; color: var(--ink-soft); text-align: right; overflow-wrap: anywhere; }
.ls-card-note { margin: 18px 0 0; color: var(--ink-muted); font-size: 12px; line-height: 1.8; }
.ls-workspace-products { display: grid; gap: 14px; list-style: none; margin: 18px 0 0; padding: 0; max-height: 260px; overflow-y: auto; }
.ls-workspace-products li { display: flex; gap: 10px; align-items: flex-start; }
.ls-workspace-products li > span:last-child { min-width: 0; }
.ls-workspace-products strong { display: block; color: var(--ink-soft); font-size: 13px; font-weight: 500; overflow-wrap: anywhere; }
.ls-workspace-products small { display: block; color: var(--ink-muted); margin-top: 5px; font-size: 12px; }
.ls-text-action { display: inline-flex; margin-top: 20px; padding: 2px 0; border: 0; background: transparent; color: var(--color-primary); font: 12px var(--font-sans); cursor: pointer; }
.ls-platform-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 20px; font-size: 12px; color: var(--ink-muted); }
.ls-platform-metrics strong { display: block; margin-top: 10px; font: 500 22px var(--font-mono); color: var(--ink-soft); }
.ls-review-details { display: grid; grid-template-columns: 1fr 1fr; gap: 36px; margin-top: 28px; padding-top: 24px; border-top: 1px solid var(--line); }
.ls-review-details h3 { font-size: 14px; margin: 0; }
.ls-gaps { margin-top: 28px; padding-top: 24px; border-top: 1px solid var(--line); }
.ls-gaps h3 { font-size: 14px; margin: 0; }
.ls-gaps h3 small { color: var(--ink-muted); font-weight: 400; }
.ls-gap-list { display: grid; gap: 10px; margin: 14px 0 0; padding: 0; list-style: none; }
.ls-gap-list li { padding: 11px 13px; border: 1px solid var(--line); border-radius: 10px; }
.ls-gap-row { display: flex; align-items: center; gap: 12px; min-width: 0; }
.ls-gap-row strong { flex: 1; min-width: 0; font-size: 13px; overflow-wrap: anywhere; }
.ls-gap-row small { flex: none; color: var(--ink-muted); font-size: 12px; }
.ls-gap-form { display: grid; gap: 10px; margin-top: 12px; }
.ls-gap-form label { display: grid; gap: 5px; font-size: 12px; color: var(--ink-muted); }
.ls-gap-form input, .ls-gap-form textarea, .ls-gap-form :deep(.app-select-button) { width: 100%; padding: 8px 10px; border: 1px solid var(--line); border-radius: 7px; background: transparent; color: var(--ink); font: 13px var(--font-sans); }
.ls-gap-actions { display: flex; gap: 10px; }
.ls-gap-error { margin: 0; color: var(--red); font-size: 12px; }
.ls-gap-notice { margin: 12px 0 0; color: var(--green); font-size: 12px; }
.ls-live-bar .ls-live-metric strong { overflow-wrap: anywhere; }
@media (max-width: 700px) {
  .ls-stage-nav { gap: 16px; }
  .ls-stage-nav button { font-size: 13px; }
  .ls-review-details { grid-template-columns: 1fr; gap: 24px; }
  .ls-live-bar { gap: 20px; }
  .ls-live-voice-metric { flex-basis: 100%; }
}

</style>
