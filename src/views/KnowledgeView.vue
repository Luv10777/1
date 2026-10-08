<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  Archive, ArrowLeft, ArrowUpRight, ChevronDown, FileText, Filter,
  MessageCircleQuestion, Plus, Search, Trash2, Upload, Pencil,
} from 'lucide-vue-next'
import { knowledgeApi } from '../services/knowledgeApi'
import { selectedStoreId, selectedStore } from '../stores/merchantContext'

const activeTab = ref('all')
const searchQuery = ref('')
const filterOpen = ref(false)
const createOpen = ref(false)
const selectedStatus = ref('all')
const faqDetailOpen = ref(false)
const selectedFaqId = ref(null)
const faqPairModalOpen = ref(false)
const faqSetModalOpen = ref(false)
const editingFaqPair = ref(null)
const editingSetId = ref(null)
const faqNotice = ref('')
const loadError = ref('')
const formError = ref('')
const loading = ref(false)
const saving = ref(false)
const faqSetForm = ref({ name: '', description: '' })
const faqForm = ref({ question: '', answer: '', status: 'DRAFT' })
const archiveOpen = ref(false)
const sets = ref([])
const entrySets = ref({})
let requestVersion = 0

const formatDate = (value) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
const visibleEntries = (setId) => (entrySets.value[setId] || []).filter(entry => entry.status !== 'DISABLED')
const statusLabels = { DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用', ARCHIVED: '已归档' }
const allKnowledge = computed(() => [...sets.value].sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt)).map(item => ({
  ...item,
  type: item.kind === 'FAQ' ? 'faq' : 'rag',
  icon: item.kind === 'FAQ' ? MessageCircleQuestion : FileText,
  tone: item.kind === 'FAQ' ? 'cinnabar' : 'blue',
  meta: `KB-${item.id} · 更新于 ${formatDate(item.updatedAt)}`,
  metric: item.kind === 'FAQ' ? `${visibleEntries(item.id).length} 个问答对` : '文档解析未开放',
  statusLabel: statusLabels[item.status] || item.status,
  sync: item.status === 'PUBLISHED' ? `已发布 · 第 ${item.currentVersion} 版` : statusLabels[item.status],
})))
const currentKnowledge = computed(() => allKnowledge.value.filter(item => item.status !== 'ARCHIVED'))
const archivedKnowledge = computed(() => allKnowledge.value.filter(item => item.status === 'ARCHIVED'))
const selectedFaq = computed(() => allKnowledge.value.find(item => item.id === selectedFaqId.value))
const faqPairs = computed(() => visibleEntries(selectedFaqId.value))
const faqSets = computed(() => currentKnowledge.value.filter(item => item.kind === 'FAQ'))
const faqTotal = computed(() => faqSets.value.reduce((sum, item) => sum + visibleEntries(item.id).length, 0))
const publishedTotal = computed(() => faqSets.value.filter(item => item.status === 'PUBLISHED').length)
const tabs = computed(() => [
  { id: 'all', label: '全部', count: currentKnowledge.value.length },
  { id: 'rag', label: '文档资料', suffix: 'RAG', count: currentKnowledge.value.filter(item => item.kind === 'DOCUMENT').length },
  { id: 'faq', label: '常见问答', suffix: 'FAQ', count: faqSets.value.length },
])
const filteredKnowledge = computed(() => currentKnowledge.value.filter((item) => {
  const query = searchQuery.value.trim().toLowerCase()
  return (activeTab.value === 'all' || item.type === activeTab.value)
    && (!query || [item.name, item.description, item.meta].join(' ').toLowerCase().includes(query))
    && (selectedStatus.value === 'all' || item.status === selectedStatus.value)
}))
const statusClass = (status) => ({ PUBLISHED: 'status-active', DISABLED: 'status-draft', DRAFT: 'status-draft' }[status])
const toneClass = (tone) => `tone-${tone}`

function closePopovers() {
  filterOpen.value = false
  createOpen.value = false
}
onMounted(() => document.addEventListener('click', closePopovers))
onBeforeUnmount(() => {
  requestVersion += 1
  document.removeEventListener('click', closePopovers)
})

async function loadKnowledge() {
  const version = ++requestVersion
  const storeId = selectedStoreId.value
  sets.value = []
  entrySets.value = {}
  loadError.value = ''
  faqNotice.value = ''
  formError.value = ''
  saving.value = false
  closeFaqDetail()
  archiveOpen.value = false
  closePopovers()
  if (!storeId) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const loadedSets = await knowledgeApi.listSets(storeId)
    const loadedEntries = await Promise.all(loadedSets
      .filter(item => item.kind === 'FAQ' && item.status !== 'ARCHIVED')
      .map(async item => [item.id, await knowledgeApi.listEntries(item.id)]))
    if (version !== requestVersion || storeId !== selectedStoreId.value) return
    sets.value = loadedSets
    entrySets.value = Object.fromEntries(loadedEntries)
  } catch (error) {
    if (version === requestVersion) loadError.value = error.message || '知识库加载失败，请重试。'
  } finally {
    if (version === requestVersion) loading.value = false
  }
}
watch(selectedStoreId, loadKnowledge, { immediate: true })

// A request that finishes after a store switch must never alter the new store's UI.
async function mutate(task, applyResult, notice) {
  if (saving.value || !selectedStoreId.value || loading.value) return
  const version = requestVersion
  const storeId = selectedStoreId.value
  saving.value = true
  formError.value = ''
  faqNotice.value = ''
  try {
    const result = await task()
    if (version !== requestVersion || storeId !== selectedStoreId.value) return
    applyResult(result)
    faqNotice.value = notice
  } catch (error) {
    if (version === requestVersion) formError.value = error.message || '保存失败，请重试。'
  } finally {
    if (version === requestVersion) saving.value = false
  }
}

function replaceSet(item) {
  const index = sets.value.findIndex(set => set.id === item.id)
  if (index >= 0) sets.value.splice(index, 1, item)
  else sets.value.unshift(item)
}
function chooseCreate(type) {
  createOpen.value = false
  if (type !== 'faq') return
  editingSetId.value = null
  faqSetForm.value = { name: '', description: '' }
  formError.value = ''
  faqSetModalOpen.value = true
}
function openSetEditor(item) {
  if (saving.value) return
  editingSetId.value = item.id
  faqSetForm.value = { name: item.name, description: item.description || '' }
  formError.value = ''
  faqSetModalOpen.value = true
}
function openFaqDetail(item) {
  if (!item || saving.value) return
  selectedFaqId.value = item.id
  faqDetailOpen.value = true
  faqNotice.value = ''
  formError.value = ''
}
function closeFaqDetail() {
  if (saving.value) return
  faqDetailOpen.value = false
  selectedFaqId.value = null
  faqPairModalOpen.value = false
  faqSetModalOpen.value = false
  editingFaqPair.value = null
}
function closeEditor(kind) {
  if (saving.value) return
  if (kind === 'set') faqSetModalOpen.value = false
  else faqPairModalOpen.value = false
  formError.value = ''
}
async function saveFaqSet() {
  const name = faqSetForm.value.name.trim()
  const description = faqSetForm.value.description.trim()
  if (!name) { formError.value = '请填写知识集名称。'; return }
  const setId = editingSetId.value
  await mutate(
    () => setId ? knowledgeApi.updateSet(setId, { name, description }) : knowledgeApi.createSet(selectedStoreId.value, { name, description, kind: 'FAQ' }),
    item => {
      replaceSet(item)
      entrySets.value[item.id] ||= []
      faqSetModalOpen.value = false
      selectedFaqId.value = item.kind === 'FAQ' ? item.id : null
      faqDetailOpen.value = item.kind === 'FAQ'
    },
    setId ? '知识集已保存。' : '知识集已创建，添加问答后即可发布。',
  )
}
function openFaqPairEditor(pair = null) {
  if (saving.value) return
  editingFaqPair.value = pair
  faqForm.value = pair ? { question: pair.question, answer: pair.answer, status: pair.status } : { question: '', answer: '', status: 'DRAFT' }
  faqPairModalOpen.value = true
  formError.value = ''
}
async function saveFaqPair() {
  const question = faqForm.value.question.trim()
  const answer = faqForm.value.answer.trim()
  if (!question || !answer) { formError.value = '请先填写问题和标准答案。'; return }
  const setId = selectedFaqId.value
  const pairId = editingFaqPair.value?.id
  const body = { question, answer, status: faqForm.value.status }
  await mutate(
    () => pairId ? knowledgeApi.updateEntry(pairId, body) : knowledgeApi.createEntry(setId, { ...body, scope: 'STORE', source: 'MANUAL' }),
    pair => {
      const entries = entrySets.value[setId] || []
      entrySets.value[setId] = [pair, ...entries.filter(entry => entry.id !== pair.id)]
      faqPairModalOpen.value = false
      editingFaqPair.value = null
    },
    '问答已保存；已发布知识集中的已启用问答会供后续直播使用。',
  )
}
async function toggleFaqPair(pair) {
  await mutate(
    () => knowledgeApi.updateEntry(pair.id, { status: pair.status === 'ACTIVE' ? 'DRAFT' : 'ACTIVE' }),
    updated => { entrySets.value[pair.knowledgeSetId] = entrySets.value[pair.knowledgeSetId].map(entry => entry.id === pair.id ? updated : entry) },
    pair.status === 'ACTIVE' ? '问答已转为草稿。' : '问答已启用；知识集发布后生效。',
  )
}
async function removeFaqPair(pair) {
  if (saving.value || !window.confirm(`确定删除“${pair.question}”吗？`)) return
  await mutate(
    () => knowledgeApi.deleteEntry(pair.id),
    () => { entrySets.value[pair.knowledgeSetId] = entrySets.value[pair.knowledgeSetId].filter(entry => entry.id !== pair.id) },
    '问答已删除。',
  )
}
async function changeSetStatus(item, action) {
  if (saving.value) return
  if (action === 'archive' && !window.confirm(`确定归档“${item.name}”吗？归档后将不再用于新的直播。`)) return
  await mutate(
    () => knowledgeApi[action](item.id),
    updated => {
      replaceSet(updated)
      if (action === 'publish') {
        entrySets.value[item.id] = (entrySets.value[item.id] || []).map(entry => entry.status === 'DRAFT' ? { ...entry, status: 'ACTIVE' } : entry)
      }
      if (action === 'archive' && selectedFaqId.value === item.id) {
        faqDetailOpen.value = false
        selectedFaqId.value = null
      }
    },
    { publish: '知识集已发布，草稿问答已启用。', disable: '知识集已停用。', archive: '知识集已归档。' }[action],
  )
}
</script>
<template>
  <div class="knowledge-page">
    <header v-if="!faqDetailOpen" class="knowledge-header">
      <div class="header-copy"><h1>知识库</h1><p>管理 {{ selectedStore || '当前门店' }} 的 AI 专属知识上下文</p></div>
      <div class="header-tools">
        <label class="knowledge-search"><Search :size="16" stroke-width="1.8" /><input v-model="searchQuery" type="search" placeholder="搜索知识名称、ID 或说明" aria-label="搜索知识库" /></label>
        <div class="filter-wrap">
          <button class="ghost-button" type="button" :class="{ selected: selectedStatus !== 'all' }" @click.stop="filterOpen = !filterOpen; createOpen = false"><Filter :size="15" /> 筛选 <span v-if="selectedStatus !== 'all'" class="filter-count">1</span></button>
          <div v-if="filterOpen" class="popover filter-popover" @click.stop>
            <p>状态</p>
            <button v-for="option in [{ value: 'all', label: '全部状态' }, { value: 'PUBLISHED', label: '已发布' }, { value: 'DISABLED', label: '已停用' }, { value: 'DRAFT', label: '草稿' }]" :key="option.value" type="button" :class="{ checked: selectedStatus === option.value }" @click="selectedStatus = option.value; filterOpen = false"><span>{{ option.label }}</span><span v-if="selectedStatus === option.value">✓</span></button>
          </div>
        </div>
        <div class="create-wrap">
          <button class="create-button" type="button" :disabled="loading || saving || !selectedStoreId || !!loadError" @click.stop="createOpen = !createOpen; filterOpen = false"><Plus :size="17" stroke-width="2.2" /> 新建知识 <ChevronDown :size="15" :class="{ rotate: createOpen }" /></button>
          <div v-if="createOpen" class="popover create-popover" @click.stop>
            <button type="button" disabled><Upload :size="16" /><span><strong>上传文档</strong><small>文档解析暂未开放</small></span></button>
            <button type="button" @click="chooseCreate('faq')"><MessageCircleQuestion :size="16" /><span><strong>新建问答集</strong><small>沉淀客服与售前经验</small></span></button>
          </div>
        </div>
      </div>
    </header>

    <div v-if="loadError" class="faq-notice error-notice" role="alert">{{ loadError }}<button type="button" class="ghost-button" @click="loadKnowledge">重新加载</button></div>
    <div v-if="!faqPairModalOpen && !faqSetModalOpen && (formError || faqNotice)" class="faq-notice" :class="{ 'error-notice': formError }" :role="formError ? 'alert' : 'status'">{{ formError || faqNotice }}</div>

    <nav v-if="!faqDetailOpen" class="knowledge-tabs" aria-label="知识类型">
      <button v-for="tab in tabs" :key="tab.id" type="button" class="knowledge-tab" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id"><span>{{ tab.label }}</span><span v-if="tab.suffix" class="tab-suffix">{{ tab.suffix }}</span><span class="tab-count">{{ tab.count }}</span><span v-if="activeTab === tab.id" class="tab-indicator" /></button>
    </nav>

    <main v-if="!faqDetailOpen">
      <section class="faq-feature-card">
        <div class="faq-feature-mark"><MessageCircleQuestion :size="22" stroke-width="1.8" /></div>
        <div class="faq-feature-copy"><p class="eyebrow">FAQ KNOWLEDGE SET</p><h2>常见问答集</h2><p>把门店经验整理成可复用的问答对，发布后自动用于 AI 实景直播的门店通用知识。</p><div class="faq-feature-meta"><span>{{ faqSets.length }} 个问答集</span><i /><span>{{ faqTotal }} 个问答对</span><i /><span>{{ publishedTotal }} 个已发布</span></div></div>
        <button type="button" class="faq-feature-action" :disabled="loading || saving || !selectedStoreId || !!loadError" @click="activeTab = 'faq'; selectedStatus = 'all'; searchQuery = ''">管理问答集 <ArrowUpRight :size="15" /></button>
      </section>
      <div class="list-toolbar"><div><strong>{{ filteredKnowledge.length }}</strong> 个知识集 <span>·</span> 最近更新优先</div><button class="view-button" type="button" :disabled="loading" @click="archiveOpen = true"><Archive :size="14" /> 归档管理 <span v-if="archivedKnowledge.length" class="archive-count">{{ archivedKnowledge.length }}</span></button></div>
      <div v-if="loading" class="empty-state" role="status"><strong>正在加载知识库…</strong></div>
      <div v-else-if="activeTab === 'rag' && !filteredKnowledge.length && !loadError" class="empty-state"><FileText :size="22" /><strong>文档知识库暂未开放</strong><span>可以先创建常见问答集，积累门店通用知识。</span></div>
      <TransitionGroup name="knowledge-list" tag="section" class="knowledge-list" aria-live="polite">
        <article v-for="(item, index) in filteredKnowledge" :key="item.id" class="knowledge-row" :class="{ clickable: item.type === 'faq' }" :style="{ '--stagger': `${index * 55}ms` }" @click="item.type === 'faq' && openFaqDetail(item)">
          <div class="row-main"><div class="type-icon" :class="toneClass(item.tone)"><component :is="item.icon" :size="20" stroke-width="1.8" /></div><div class="row-title"><h2><button v-if="item.type === 'faq'" type="button" class="row-title-button" :disabled="saving" @click.stop="openFaqDetail(item)">{{ item.name }}</button><span v-else>{{ item.name }}</span></h2><p>{{ item.meta }}</p></div></div>
          <div class="row-description">{{ item.description || '暂无说明' }}</div>
          <div class="row-metric"><strong>{{ item.metric }}</strong><span>{{ item.sync }}</span></div>
          <div class="row-apps"><span class="app-tag">{{ item.kind === 'FAQ' ? '门店通用' : '文档资料' }}</span></div>
          <span class="status-badge" :class="statusClass(item.status)"><span class="status-dot" />{{ item.statusLabel }}</span>
          <div class="row-actions" @click.stop>
            <button v-if="item.kind === 'FAQ'" type="button" :disabled="saving" @click="changeSetStatus(item, item.status === 'PUBLISHED' ? 'disable' : 'publish')">{{ item.status === 'PUBLISHED' ? '停用' : '发布' }}</button>
            <button type="button" title="编辑知识集" :disabled="saving" @click="openSetEditor(item)"><Pencil :size="15" /></button>
            <button type="button" title="归档知识集" :disabled="saving" @click="changeSetStatus(item, 'archive')"><Archive :size="15" /></button>
          </div>
        </article>
      </TransitionGroup>
      <div v-if="!loading && !loadError && !filteredKnowledge.length && activeTab !== 'rag'" class="empty-state"><Search :size="22" /><strong>{{ currentKnowledge.length ? '没有匹配的知识集' : '还没有知识集' }}</strong><span>{{ currentKnowledge.length ? '试试其他关键词或清除筛选条件' : '新建问答集，整理门店营业、预约、售后等常见问题。' }}</span><button v-if="!currentKnowledge.length && selectedStoreId" type="button" class="create-button" @click="chooseCreate('faq')"><Plus :size="15" /> 新建问答集</button></div>
    </main>

    <main v-else-if="selectedFaq" class="faq-detail-page">
      <div class="faq-detail-header">
        <button type="button" class="back-button" :disabled="saving" @click="closeFaqDetail"><ArrowLeft :size="16" /> 返回知识库</button>
        <div class="faq-detail-heading"><div class="faq-detail-icon"><MessageCircleQuestion :size="21" /></div><div><p class="eyebrow">FAQ KNOWLEDGE SET · {{ selectedFaq.statusLabel }}</p><h1>{{ selectedFaq.name }}</h1><p>{{ selectedFaq.description || '维护门店可复用的标准问答。' }}</p></div></div>
        <div class="faq-detail-actions">
          <button type="button" class="ghost-button" :disabled="saving" @click="openSetEditor(selectedFaq)"><Pencil :size="15" /> 编辑说明</button>
          <button type="button" class="ghost-button" :disabled="saving" @click="openFaqPairEditor()"><Plus :size="15" /> 添加问答对</button>
          <button v-if="selectedFaq.status === 'PUBLISHED'" type="button" class="ghost-button" :disabled="saving" @click="changeSetStatus(selectedFaq, 'disable')">停用</button>
          <button type="button" class="create-button" :disabled="saving" @click="changeSetStatus(selectedFaq, 'publish')">{{ selectedFaq.status === 'PUBLISHED' ? '发布更新' : '发布知识集' }}</button>
        </div>
      </div>
      <p class="knowledge-scope-hint">已发布知识集中的已启用问答，将作为本门店后续直播的通用知识；发布会启用集内全部草稿问答。</p>
      <div class="faq-stat-grid">
        <div><span>总问答数</span><strong>{{ faqPairs.length }}</strong><small>当前知识集</small></div>
        <div><span>已启用</span><strong>{{ faqPairs.filter(pair => pair.status === 'ACTIVE').length }}</strong><small>{{ selectedFaq.status === 'PUBLISHED' ? '供后续直播使用' : '知识集发布后生效' }}</small></div>
        <div><span>草稿</span><strong>{{ faqPairs.filter(pair => pair.status === 'DRAFT').length }}</strong><small>待启用</small></div>
        <div><span>累计命中</span><strong>{{ faqPairs.reduce((sum, pair) => sum + Number(pair.hits || 0), 0) }}</strong><small>后台累计记录</small></div>
      </div>
      <section class="faq-pair-list">
        <div class="faq-list-head"><div><p class="eyebrow">CURATED RESPONSES</p><h2>问答对配置</h2></div><span>{{ faqPairs.length }} 条记录</span></div>
        <article v-for="(pair, index) in faqPairs" :key="pair.id" class="faq-pair-card" :style="{ '--stagger': `${index * 55}ms` }">
          <div class="faq-pair-index">{{ String(index + 1).padStart(2, '0') }}</div>
          <div class="faq-pair-content"><div class="faq-question"><span>问</span><h3>{{ pair.question }}</h3></div><div class="faq-answer"><span>答</span><p>{{ pair.answer }}</p></div><div class="faq-pair-meta"><span class="hit-count">命中 {{ pair.hits || 0 }} 次</span><span class="faq-status" :class="pair.status === 'ACTIVE' ? 'active' : 'draft'"><i />{{ pair.status === 'ACTIVE' ? '已启用' : '草稿' }}</span><span v-if="pair.scope === 'PRODUCT'" class="hit-count">商品专属</span></div></div>
          <div class="faq-pair-actions"><button type="button" title="编辑" :disabled="saving" @click="openFaqPairEditor(pair)"><Pencil :size="15" /></button><button type="button" :title="pair.status === 'ACTIVE' ? '转为草稿' : '启用'" :disabled="saving" @click="toggleFaqPair(pair)"><Archive :size="15" /></button><button type="button" class="danger" title="删除" :disabled="saving" @click="removeFaqPair(pair)"><Trash2 :size="15" /></button></div>
        </article>
        <div v-if="!faqPairs.length" class="faq-empty"><MessageCircleQuestion :size="24" /><strong>还没有问答对</strong><span>添加第一条问答，积累可复用的回答。</span><button type="button" class="create-button" :disabled="saving" @click="openFaqPairEditor()"><Plus :size="15" /> 添加问答对</button></div>
      </section>
    </main>

    <Transition name="dialog-fade">
      <div v-if="faqSetModalOpen" class="modal-overlay" @click.self="closeEditor('set')">
        <section class="faq-pair-modal faq-set-modal" role="dialog" aria-modal="true" aria-labelledby="faq-set-title">
          <header class="modal-header"><div><p class="eyebrow">FAQ KNOWLEDGE SET</p><h2 id="faq-set-title">{{ editingSetId ? '编辑知识集' : '新建常见问答集' }}</h2></div><button type="button" class="close-button" aria-label="关闭" :disabled="saving" @click="closeEditor('set')">×</button></header>
          <form class="faq-pair-form" @submit.prevent="saveFaqSet">
            <label class="faq-field"><span>知识集名称</span><input v-model="faqSetForm.name" :disabled="saving" required maxlength="120" placeholder="例如：门店会员服务问答" autofocus /></label>
            <label class="faq-field"><span>知识集说明 <em>选填</em></span><textarea v-model="faqSetForm.description" :disabled="saving" maxlength="500" rows="4" placeholder="说明这组问答主要服务的业务场景" /></label>
            <p v-if="formError" class="faq-form-notice" role="alert">{{ formError }}</p>
            <footer class="faq-form-footer"><button type="button" class="ghost-button" :disabled="saving" @click="closeEditor('set')">取消</button><button type="submit" class="create-button" :disabled="saving">{{ saving ? '保存中…' : editingSetId ? '保存知识集' : '创建并添加问答' }}</button></footer>
          </form>
        </section>
      </div>
    </Transition>
    <Transition name="dialog-fade">
      <div v-if="faqPairModalOpen" class="modal-overlay" @click.self="closeEditor('pair')">
        <section class="faq-pair-modal" role="dialog" aria-modal="true" aria-labelledby="faq-pair-title">
          <header class="modal-header"><div><p class="eyebrow">FAQ RESPONSE</p><h2 id="faq-pair-title">{{ editingFaqPair ? '编辑问答对' : '添加问答对' }}</h2></div><button type="button" class="close-button" aria-label="关闭" :disabled="saving" @click="closeEditor('pair')">×</button></header>
          <form class="faq-pair-form" @submit.prevent="saveFaqPair">
            <label class="faq-field"><span>用户问题</span><input v-model="faqForm.question" :disabled="saving" required maxlength="500" placeholder="例如：你们支持哪些配送方式？" /></label>
            <label class="faq-field"><span>标准答案</span><textarea v-model="faqForm.answer" :disabled="saving" required rows="6" placeholder="输入 AI 应该采用的准确、完整回答" /></label>
            <label class="faq-field"><span>状态</span><select v-model="faqForm.status" :disabled="saving"><option value="ACTIVE">已启用</option><option value="DRAFT">草稿</option></select></label>
            <p v-if="formError" class="faq-form-notice" role="alert">{{ formError }}</p>
            <footer class="faq-form-footer"><button type="button" class="ghost-button" :disabled="saving" @click="closeEditor('pair')">取消</button><button type="submit" class="create-button" :disabled="saving">{{ saving ? '保存中…' : '保存问答对' }}</button></footer>
          </form>
        </section>
      </div>
    </Transition>
    <Transition name="dialog-fade">
      <div v-if="archiveOpen" class="modal-overlay" @click.self="archiveOpen = false">
        <section class="archive-modal" role="dialog" aria-modal="true" aria-labelledby="archive-title">
          <header class="modal-header"><div><p class="eyebrow">KNOWLEDGE ARCHIVE</p><h2 id="archive-title">归档管理</h2><p class="archive-subtitle">归档知识集保留在这里，不再用于新的直播。</p></div><button type="button" class="close-button" aria-label="关闭" @click="archiveOpen = false">×</button></header>
          <div class="archive-body"><div v-if="archivedKnowledge.length" class="archive-list"><article v-for="item in archivedKnowledge" :key="item.id" class="archive-row"><div class="archive-icon"><Archive :size="17" /></div><div class="archive-copy"><h3>{{ item.name }}</h3><div><span>{{ item.kind === 'FAQ' ? '常见问答' : '文档资料' }}</span><i /><span>归档于 {{ formatDate(item.updatedAt) }}</span></div><p>{{ item.description || '暂无说明' }}</p></div><span class="status-badge status-draft">已归档</span></article></div><div v-else class="archive-empty"><Archive :size="25" /><strong>暂无归档知识集</strong><span>在知识列表中归档的内容会显示在这里。</span></div></div>
          <footer class="archive-footer"><span>归档内容不会被 AI 检索</span><button type="button" class="ghost-button" @click="archiveOpen = false">完成</button></footer>
        </section>
      </div>
    </Transition>
    <footer v-if="!faqDetailOpen && !loading && !loadError && selectedStoreId" class="knowledge-footer"><span><span class="footer-pulse" />当前门店：{{ selectedStore }}</span><span>已连接知识库</span></footer>
  </div>
</template>

<style scoped>
.knowledge-page{--page-ink:var(--color-primary);--page-muted:var(--color-text-muted);--page-line:var(--color-border-subtle);--page-soft:var(--color-bg-canvas);--page-accent:var(--color-accent-text);--page-accent-soft:var(--color-bg-subtle);max-width:1420px;margin:0 auto;padding:14px clamp(22px,3.2vw,52px) 28px;color:var(--page-ink);font-family:var(--font-sans)}
.knowledge-header{display:flex;align-items:flex-start;justify-content:space-between;gap:32px;margin-bottom:25px}.header-copy h1{margin:10px 0 7px;font:700 clamp(28px,3vw,38px)/1.15 var(--font-serif);letter-spacing:.01em}.header-copy p{margin:0;color:var(--page-muted);font-size:13px}.eyebrow-row{display:flex;align-items:center;gap:8px}.eyebrow{color:var(--color-text-muted);font:600 10px var(--font-mono);letter-spacing:.14em}.eyebrow-dot{width:7px;height:7px;border-radius:50%;background:var(--page-accent);box-shadow:var(--shadow-paper)}
.header-tools{display:flex;align-items:center;gap:9px;margin-top:4px}.knowledge-search{display:flex;align-items:center;gap:9px;width:min(290px,28vw);height:38px;padding:0 11px;border:1px solid var(--page-line);border-radius:8px;background:var(--color-bg-surface);color:var(--color-text-muted);transition:border-color .18s ease,box-shadow .18s ease}.knowledge-search:focus-within{border-color:var(--color-accent);box-shadow:var(--shadow-paper)}.knowledge-search input{width:100%;min-width:0;border:0;outline:0;color:var(--page-ink);background:transparent;font-size:12px}.knowledge-search input::placeholder{color:var(--color-text-muted)}.knowledge-search kbd{padding:3px 5px;border:1px solid var(--page-line);border-radius:5px;color:var(--color-text-muted);background:var(--color-bg-surface);font:10px var(--font-mono);white-space:nowrap}.ghost-button,.view-button{display:inline-flex;align-items:center;gap:7px;height:38px;padding:0 12px;border:1px solid var(--page-line);border-radius:4px;color:var(--color-text-muted);background:var(--color-bg-surface);font-size:12px;transition:all .18s ease}.ghost-button:hover,.ghost-button.selected,.view-button:hover{border-color:var(--color-accent);color:var(--page-accent);background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}.filter-count{display:grid;place-items:center;width:16px;height:16px;border-radius:50%;color:var(--color-text-muted);background:var(--page-accent);font-size:10px}.create-button{display:inline-flex;align-items:center;gap:7px;height:38px;padding:0 13px;border:1px solid var(--color-accent);border-radius:4px;color:var(--color-on-accent);background:var(--page-accent);font-size:12px;font-weight:600;box-shadow:none;transition:all .18s ease}.create-button:hover{background:var(--color-accent-hover);transform:translateY(-1px);box-shadow:none}.create-button .rotate{transform:rotate(180deg)}
.filter-wrap,.create-wrap{position:relative}.popover{position:absolute;z-index:10;top:calc(100% + 8px);right:0;border:1px solid var(--page-line);border-radius:8px;background:var(--color-bg-surface);box-shadow:var(--shadow-paper);animation:popover-in .16s ease-out}.filter-popover{width:150px;padding:7px}.filter-popover p{margin:5px 8px 6px;color:var(--color-text-muted);font:600 10px var(--font-mono);letter-spacing:.08em}.filter-popover button{display:flex;align-items:center;justify-content:space-between;width:100%;padding:8px;border:0;border-radius:7px;color:var(--color-text-muted);background:transparent;font-size:12px;text-align:left}.filter-popover button:hover,.filter-popover button.checked{color:var(--page-accent);background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}.create-popover{width:250px;padding:6px}.create-popover button{display:flex;align-items:flex-start;gap:10px;width:100%;padding:10px;border:0;border-radius:4px;color:var(--page-accent);background:transparent;text-align:left}.create-popover button:hover{background:var(--color-bg-subtle)}.create-popover button svg{margin-top:2px}.create-popover span{display:grid;gap:3px}.create-popover strong{color:var(--page-ink);font-size:12px;font-weight:600}.create-popover small{color:var(--color-text-muted);font-size:10px}@keyframes popover-in{from{opacity:0;transform:translateY(-4px)}to{opacity:1;transform:none}}
.knowledge-tabs{display:flex;gap:25px;border-bottom:1px solid var(--page-line);margin-bottom:18px}.knowledge-tab{position:relative;display:inline-flex;align-items:center;gap:7px;height:43px;padding:0 2px;border:0;color:var(--color-text-muted);background:transparent;font-size:12px;cursor:pointer}.knowledge-tab:hover{color:var(--color-primary)}.knowledge-tab.active{color:var(--page-ink);font-weight:700}.tab-suffix{color:var(--color-text-muted);font:10px var(--font-mono)}.tab-count{min-width:18px;padding:2px 5px;border-radius:4px;color:var(--color-text-muted);background:var(--color-bg-surface);font:500 10px var(--font-mono);text-align:center}.knowledge-tab.active .tab-count{color:var(--page-accent);background:var(--page-accent-soft)}.tab-indicator{position:absolute;right:0;bottom:-1px;left:0;height:2px;border-radius:3px 3px 0 0;background:var(--page-accent);animation:tab-in .22s ease-out}@keyframes tab-in{from{transform:scaleX(.25);opacity:.3}to{transform:scaleX(1);opacity:1}}
.list-toolbar{display:flex;align-items:center;justify-content:space-between;margin:0 3px 10px;color:var(--color-text-muted);font-size:11px}.list-toolbar strong{color:var(--color-text-muted);font-weight:600}.list-toolbar>div span{padding:0 5px;color:var(--color-text-muted)}.view-button{height:30px;padding:0 9px;border-color:transparent;color:var(--color-text-muted);background:transparent;font-size:11px}.knowledge-list{overflow:hidden;border:1px solid var(--page-line);border-radius:8px;background:var(--color-bg-surface);box-shadow:var(--shadow-paper)}.knowledge-row{position:relative;display:grid;grid-template-columns:minmax(255px,1.35fr) minmax(180px,1fr) 145px minmax(145px,.8fr) 95px 118px 32px;align-items:center;gap:18px;min-height:84px;padding:14px 17px;border-top:1px solid var(--page-line);transition:background .18s ease}.knowledge-row:first-child{border-top:0}.knowledge-row:hover{background:var(--color-bg-subtle)}.row-main{display:flex;align-items:center;gap:12px;min-width:0}.type-icon{display:grid;place-items:center;width:40px;height:40px;flex:0 0 auto;border-radius:8px}.tone-blue{color:#4d74d8;background:#edf3ff}.tone-cinnabar{color:#bf7663;background:#f9f6f5}.tone-orange{color:#d78338;background:#fff3e7}.row-title{min-width:0}.row-title h2{overflow:hidden;margin:0 0 4px;color:var(--color-primary);font-size:13px;font-weight:650;text-overflow:ellipsis;white-space:nowrap}.row-title p{margin:0;overflow:hidden;color:var(--color-text-muted);font:10px var(--font-mono);text-overflow:ellipsis;white-space:nowrap}.row-description{overflow:hidden;color:var(--color-text-muted);font-size:11px;text-overflow:ellipsis;white-space:nowrap}.row-metric{display:grid;gap:4px}.row-metric strong{color:var(--color-primary);font-size:12px;font-weight:600}.row-metric span{color:var(--color-text-muted);font-size:10px}.row-apps{display:flex;flex-wrap:wrap;gap:4px}.app-tag{padding:4px 7px;border:1px solid var(--color-border-subtle);border-radius:5px;color:var(--color-text-muted);background:var(--color-bg-surface);font-size:10px;white-space:nowrap}.status-badge{display:inline-flex;align-items:center;justify-content:center;gap:5px;width:max-content;padding:4px 8px;border-radius:4px;font-size:10px;font-weight:600;white-space:nowrap}.status-dot{width:6px;height:6px;border-radius:50%;background:currentColor}.status-active{color:var(--color-success);background:color-mix(in srgb, var(--color-success) 7%, var(--color-bg-surface))}.status-syncing{color:var(--color-success);background:color-mix(in srgb, var(--color-success) 7%, var(--color-bg-surface))}.status-draft{color:var(--color-text-muted);background:var(--color-bg-surface)}.row-actions{display:flex;align-items:center;justify-content:flex-end;gap:4px;opacity:0;transform:translateX(5px);transition:opacity .18s ease,transform .18s ease}.knowledge-row:hover .row-actions{opacity:1;transform:none}.row-actions button,.row-more{display:inline-flex;align-items:center;justify-content:center;gap:5px;width:29px;height:29px;padding:0;border:1px solid transparent;border-radius:7px;color:var(--color-text-muted);background:transparent;font-size:10px}.row-actions button:first-child{width:auto;padding:0 7px}.row-actions button:hover,.row-more:hover{border-color:var(--color-border-subtle);color:var(--page-accent);background:var(--color-bg-subtle)}.row-actions .danger:hover{border-color:var(--color-error);color:var(--color-error);background:color-mix(in srgb, var(--color-error) 7%, var(--color-bg-surface))}.row-more{display:none}.knowledge-list-enter-active{animation:row-in .4s cubic-bezier(.22,1,.36,1) both;animation-delay:var(--stagger)}.knowledge-list-leave-active{transition:opacity .16s ease,transform .16s ease}.knowledge-list-enter-from,.knowledge-list-leave-to{opacity:0;transform:translateY(7px)}@keyframes row-in{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:none}}
.empty-state{display:grid;place-items:center;gap:8px;min-height:240px;border:1px dashed var(--page-line);border-radius:8px;color:var(--color-text-muted);background:var(--color-bg-surface)}.empty-state strong{color:var(--color-text-muted);font-size:13px}.empty-state span{font-size:11px}.knowledge-footer{display:flex;align-items:center;justify-content:space-between;margin-top:16px;color:var(--color-text-muted);font-size:10px}.knowledge-footer>span:first-child{display:inline-flex;align-items:center;gap:7px}.footer-pulse{width:6px;height:6px;border-radius:50%;background:var(--color-bg-surface);box-shadow:var(--shadow-paper)}.mono{font:10px var(--font-mono);letter-spacing:.04em}
@media(max-width:1180px){.knowledge-row{grid-template-columns:minmax(230px,1.3fr) minmax(150px,1fr) 125px minmax(120px,.8fr) 90px 32px}.row-actions{display:none}.row-more{display:inline-flex}.knowledge-search{width:230px}.header-tools{gap:7px}}
@media(max-width:860px){.knowledge-page{padding:17px 17px 26px}.knowledge-header{display:grid;gap:18px}.header-tools{flex-wrap:wrap}.knowledge-search{width:100%;order:3}.knowledge-tabs{gap:17px;overflow-x:auto}.knowledge-tab{white-space:nowrap}.knowledge-list{overflow:visible;border:0;background:transparent;box-shadow:var(--shadow-paper)}.knowledge-row{grid-template-columns:1fr auto;gap:12px;padding:15px 0;border:1px solid var(--page-line)!important;border-radius:8px;background:var(--color-bg-surface);margin-bottom:8px;box-shadow:var(--shadow-paper)}.row-description{grid-column:1/-1;order:3;padding-left:52px}.row-metric{grid-column:1;order:4;padding-left:52px}.row-apps{grid-column:1/-1;order:5;padding-left:52px}.status-badge{grid-column:2;grid-row:1;order:2}.row-more{grid-column:2;grid-row:4;order:6}.knowledge-footer{display:grid;gap:8px}.knowledge-footer .mono{font-size:9px}}
@media(max-width:520px){.header-tools{display:grid;grid-template-columns:1fr auto;align-items:center}.knowledge-search{grid-column:1/-1}.create-button{padding-inline:11px}.ghost-button{padding-inline:10px}.knowledge-tabs{margin-right:-17px;padding-right:17px}.tab-suffix{display:none}.row-title h2{font-size:12px}.row-description{font-size:10px}.row-actions{display:none}}

.faq-feature-card{display:flex;align-items:center;gap:18px;margin:0 0 18px;padding:18px 20px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface);box-shadow:var(--shadow-paper)}.faq-feature-mark{display:grid;place-items:center;width:46px;height:46px;flex:0 0 auto;border-radius:8px;color:var(--color-text-muted);background:var(--color-bg-surface)}.faq-feature-copy{min-width:0;flex:1}.faq-feature-copy .eyebrow{margin:0 0 4px;color:var(--color-text-muted)}.faq-feature-copy h2{margin:0 0 5px;color:var(--color-primary);font-size:16px;letter-spacing:.01em}.faq-feature-copy>p:not(.eyebrow){margin:0;color:var(--color-text-muted);font-size:11px;line-height:1.6}.faq-feature-meta{display:flex;align-items:center;gap:9px;margin-top:10px;color:var(--color-text-muted);font-size:10px}.faq-feature-meta i{width:3px;height:3px;border-radius:50%;background:var(--color-bg-surface)}.faq-feature-action{display:inline-flex;align-items:center;gap:7px;padding:9px 11px;border:1px solid var(--color-accent);border-radius:8px;color:var(--color-on-accent);background:var(--color-accent);font-size:11px;font-weight:600;white-space:nowrap;transition:.18s}.faq-feature-action:hover{border-color:var(--color-accent);background:var(--color-accent-hover);transform:translateY(-1px)}
.faq-detail-page{padding-bottom:18px}.modal-overlay{position:fixed;inset:0;z-index:1000;display:grid;place-items:center;padding:20px;background:rgba(24,42,42,.42);backdrop-filter:blur(5px)}.faq-detail-header{display:grid;grid-template-columns:1fr auto;gap:18px;margin-bottom:20px}.back-button{grid-column:1/-1;display:inline-flex;align-items:center;gap:6px;width:max-content;padding:0;border:0;color:var(--color-text-muted);background:transparent;font-size:11px}.back-button:hover{color:var(--page-accent)}.faq-detail-heading{display:flex;align-items:center;gap:13px}.faq-detail-icon{display:grid;place-items:center;width:42px;height:42px;border-radius:8px;color:var(--color-text-muted);background:var(--color-bg-surface)}.faq-detail-heading .eyebrow{margin:0 0 4px}.faq-detail-heading h1{margin:0 0 4px;color:var(--color-primary);font-size:22px;letter-spacing:.01em}.faq-detail-heading p:not(.eyebrow){margin:0;color:var(--color-text-muted);font-size:11px}.faq-detail-actions{display:flex;align-items:center;gap:8px}.faq-detail-actions .ghost-button,.faq-detail-actions .create-button{height:34px}.faq-stat-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:18px}.faq-stat-grid>div{display:grid;gap:4px;padding:13px 15px;border:1px solid var(--page-line);border-radius:8px;background:var(--color-bg-surface)}.faq-stat-grid span{color:var(--color-text-muted);font-size:10px}.faq-stat-grid strong{color:var(--color-primary);font:700 21px var(--font-sans);letter-spacing:.01em}.faq-stat-grid small{color:var(--color-text-muted);font-size:10px}.faq-notice,.faq-form-notice{padding:10px 12px;border-radius:8px;color:var(--color-error);background:color-mix(in srgb, var(--color-error) 7%, var(--color-bg-surface));font-size:11px}.faq-notice{margin-bottom:12px}.faq-list-head{display:flex;align-items:flex-end;justify-content:space-between;margin:0 2px 10px}.faq-list-head .eyebrow{margin-bottom:4px}.faq-list-head h2{margin:0;color:var(--color-primary);font-size:15px}.faq-list-head>span{color:var(--color-text-muted);font-size:10px}.faq-pair-list{padding:17px;border:1px solid var(--page-line);border-radius:8px;background:var(--color-bg-surface);box-shadow:var(--shadow-paper)}.faq-pair-card{display:grid;grid-template-columns:34px 1fr auto;gap:13px;padding:16px 0;border-top:1px solid var(--page-line);animation:row-in .4s cubic-bezier(.22,1,.36,1) both;animation-delay:var(--stagger)}.faq-pair-card:first-of-type{border-top:0}.faq-pair-index{color:var(--color-text-muted);font:600 11px var(--font-mono);padding-top:3px}.faq-pair-content{min-width:0}.faq-question,.faq-answer{display:flex;align-items:flex-start;gap:9px}.faq-question>span,.faq-answer>span{display:grid;place-items:center;width:20px;height:20px;flex:0 0 auto;border-radius:5px;font-size:10px;font-weight:700}.faq-question>span{color:var(--color-text-muted);background:var(--color-bg-surface)}.faq-answer>span{color:var(--color-text-muted);background:var(--color-bg-surface)}.faq-question h3{margin:0;color:var(--color-accent-text);font-size:13px;font-weight:650;line-height:1.5}.faq-answer{margin-top:8px}.faq-answer p{margin:0;color:var(--color-text-muted);font-size:11px;line-height:1.65}.faq-pair-meta{display:flex;align-items:center;gap:10px;margin-top:10px;padding-left:29px}.hit-count{color:var(--color-text-muted);font-size:10px}.faq-status{display:inline-flex;align-items:center;gap:5px;padding:3px 7px;border-radius:4px;font-size:10px;font-weight:600}.faq-status i{width:5px;height:5px;border-radius:50%;background:currentColor}.faq-status.active{color:var(--color-success);background:color-mix(in srgb, var(--color-success) 7%, var(--color-bg-surface))}.faq-status.draft{color:var(--color-text-muted);background:var(--color-bg-surface)}.faq-pair-actions{display:flex;align-items:center;gap:3px;opacity:0;transition:opacity .18s}.faq-pair-card:hover .faq-pair-actions{opacity:1}.faq-pair-actions button{display:grid;place-items:center;width:29px;height:29px;border:1px solid transparent;border-radius:7px;color:var(--color-text-muted);background:transparent}.faq-pair-actions button:hover{border-color:var(--color-border-subtle);color:var(--page-accent);background:var(--color-bg-subtle)}.faq-pair-actions .danger:hover{border-color:var(--color-error);color:var(--color-error);background:color-mix(in srgb, var(--color-error) 7%, var(--color-bg-surface))}.faq-empty{display:grid;place-items:center;gap:8px;min-height:220px;color:var(--color-text-muted);text-align:center}.faq-empty strong{color:var(--color-text-muted);font-size:13px}.faq-empty span{font-size:11px}.faq-empty .create-button{margin-top:6px}.dialog-fade-enter-active,.dialog-fade-leave-active{transition:opacity .18s ease}.dialog-fade-enter-active .faq-pair-modal,.dialog-fade-leave-active .faq-pair-modal{transition:transform .32s cubic-bezier(.22,1,.36,1),opacity .22s ease}.dialog-fade-enter-from .faq-pair-modal{opacity:0;transform:translateY(42px)}.dialog-fade-leave-to .faq-pair-modal{opacity:0;transform:translateY(24px)}.faq-pair-modal{width:min(560px,100%);border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface);box-shadow:var(--shadow-paper)}.faq-pair-modal .modal-header{display:flex;align-items:center;justify-content:space-between;padding:19px 22px 16px;border-bottom:1px solid var(--color-border-subtle);background:var(--color-bg-surface)}.faq-pair-modal .modal-header h2{margin:5px 0 0;font-size:19px}.close-button{display:grid;place-items:center;width:30px;height:30px;border:0;border-radius:4px;color:var(--color-text-muted);background:transparent;font-size:24px;line-height:1;cursor:pointer}.close-button:hover{background:var(--color-bg-subtle);color:var(--color-primary)}.faq-pair-form{display:grid;gap:15px;padding:20px 22px 22px}.faq-field{display:grid;gap:7px}.faq-field>span{color:var(--color-text-muted);font-size:12px;font-weight:600}.faq-field input,.faq-field textarea,.faq-field select{width:100%;box-sizing:border-box;padding:10px 11px;border:1px solid var(--color-border-subtle);border-radius:4px;outline:0;color:var(--color-primary);background:var(--color-bg-surface);font:12px/1.6 var(--font-sans);transition:border-color .18s,box-shadow .18s}.faq-field textarea{resize:vertical;min-height:110px}.faq-field input:focus,.faq-field textarea:focus,.faq-field select:focus{border-color:var(--color-accent);box-shadow:var(--shadow-paper)}.faq-form-footer{display:flex;justify-content:flex-end;gap:8px;padding-top:2px}.faq-form-footer .ghost-button,.faq-form-footer .create-button{height:34px}
@media(max-width:760px){.faq-feature-card{align-items:flex-start;flex-wrap:wrap}.faq-feature-action{margin-left:64px}.faq-detail-header{grid-template-columns:1fr}.faq-detail-actions{justify-content:flex-start}.faq-stat-grid{grid-template-columns:repeat(2,1fr)}.faq-pair-card{grid-template-columns:26px 1fr}.faq-pair-actions{grid-column:2;justify-content:flex-end;opacity:1}.faq-pair-meta{padding-left:0}}
@media(max-width:520px){.faq-feature-card{padding:15px}.faq-feature-copy>p:not(.eyebrow){font-size:10px}.faq-feature-meta{flex-wrap:wrap}.faq-feature-action{margin-left:0}.faq-detail-heading h1{font-size:19px}.faq-stat-grid strong{font-size:18px}.faq-pair-list{padding:13px}.faq-pair-card{gap:8px}}
.knowledge-row.clickable{cursor:pointer}.knowledge-row.clickable:hover{background:var(--color-bg-subtle)}.archive-modal{width:min(700px,100%);overflow:hidden;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface);box-shadow:var(--shadow-paper)}.archive-modal .modal-header{display:flex;align-items:flex-start;justify-content:space-between;padding:20px 22px 17px;border-bottom:1px solid var(--color-border-subtle);background:var(--color-bg-surface)}.archive-modal .modal-header h2{margin:5px 0 5px;font-size:20px}.archive-subtitle{margin:0;color:var(--color-text-muted);font-size:11px}.archive-body{max-height:52vh;overflow:auto;padding:5px 22px}.archive-row{display:grid;grid-template-columns:34px 1fr auto;align-items:center;gap:12px;padding:15px 0;border-bottom:1px solid var(--page-line)}.archive-row:last-child{border-bottom:0}.archive-icon{display:grid;place-items:center;width:32px;height:32px;border-radius:8px;color:var(--color-text-muted);background:var(--color-bg-surface)}.archive-copy{min-width:0}.archive-copy h3{margin:0 0 5px;color:var(--color-primary);font-size:13px;font-weight:700}.archive-copy>div{display:flex;align-items:center;gap:7px;color:var(--color-text-muted);font-size:10px}.archive-copy>div i{width:3px;height:3px;border-radius:50%;background:var(--color-bg-surface)}.archive-copy p{margin:6px 0 0;color:var(--color-text-muted);font-size:10px}.archive-actions{display:flex;align-items:center;gap:5px}.restore-button{height:29px;padding:0 10px;border:1px solid var(--color-accent);border-radius:7px;color:var(--color-on-accent);background:var(--color-accent);font-size:11px;font-weight:600}.restore-button:hover{background:var(--color-accent-hover)}.archive-delete{display:grid;place-items:center;width:29px;height:29px;border:1px solid transparent;border-radius:7px;color:var(--color-error);background:transparent}.archive-delete:hover{border-color:var(--color-error);color:var(--color-error);background:color-mix(in srgb, var(--color-error) 7%, var(--color-bg-surface))}.archive-empty{display:grid;place-items:center;gap:8px;min-height:210px;color:var(--color-text-muted);text-align:center}.archive-empty strong{color:var(--color-text-muted);font-size:13px}.archive-empty span{font-size:10px}.archive-footer{display:flex;align-items:center;justify-content:space-between;padding:13px 22px;border-top:1px solid var(--color-border-subtle);color:var(--color-text-muted);font-size:10px}.archive-footer .ghost-button{height:31px;padding:0 11px}
.archive-count{display:inline-grid;place-items:center;min-width:16px;height:16px;padding:0 4px;border-radius:8px;color:var(--color-text-muted);background:var(--color-bg-surface);font-size:9px;font-weight:650}

/* Bundled brand typography for headings and knowledge records. */
.knowledge-page{font-family:var(--font-sans);-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility;letter-spacing:.005em}
.knowledge-page h1,.knowledge-page h2,.knowledge-page h3,.knowledge-page strong,.knowledge-page button{font-family:var(--font-serif)}
.knowledge-page h1,.knowledge-page h2,.knowledge-page h3{font-feature-settings:'kern' 1,'liga' 1}
.header-copy h1{font-size:clamp(30px,3vw,40px);font-weight:780;line-height:1.18;letter-spacing:.01em;color:var(--color-primary)}
.header-copy p{font-size:13px;font-weight:500;line-height:1.7;color:var(--color-text-muted)}
.knowledge-search input,.ghost-button,.create-button,.view-button,.knowledge-tab,.row-description,.row-metric,.row-apps,.status-badge,.empty-state,.faq-feature-copy>p,.faq-feature-meta,.faq-detail-heading p,.faq-stat-grid span,.faq-stat-grid small,.faq-pair-card,.faq-field,.faq-form-footer{font-family:var(--font-sans)}
.knowledge-search input{font-size:12px;font-weight:500;letter-spacing:.01em}.knowledge-search input::placeholder{color:var(--color-text-muted);opacity:1}
.knowledge-tab{font-size:13px;font-weight:550;letter-spacing:.01em}.knowledge-tab.active{font-weight:720}.tab-suffix,.tab-count,.row-title p,.mono{font-family:var(--font-mono);font-variant-numeric:tabular-nums}
.tab-suffix{font-size:10px}.tab-count{font-size:10px;font-weight:650}.list-toolbar{font-size:12px;font-weight:500}.list-toolbar strong{font-weight:750;color:var(--color-accent-text)}
.row-title h2{font-size:14px;font-weight:720;letter-spacing:.01em;color:var(--color-accent-text)}.row-title p{font-size:10px;letter-spacing:.015em;color:var(--color-text-muted)}.row-description{font-size:12px;font-weight:500;line-height:1.5;color:var(--color-text-muted)}.row-metric strong{font-size:13px;font-weight:720;letter-spacing:.01em;color:var(--color-accent-text)}.row-metric span{font-size:10px;color:var(--color-text-muted)}.app-tag{font-size:10px;font-weight:550}.status-badge{font-size:10px;font-weight:700;letter-spacing:.01em}
.faq-feature-copy h2{font-size:18px;font-weight:760;letter-spacing:.01em}.faq-feature-copy>p:not(.eyebrow){font-size:12px;line-height:1.7;color:var(--color-text-muted)}.faq-feature-meta{font-size:11px;font-weight:550;color:var(--color-text-muted)}.faq-feature-action{font-size:12px;font-weight:700}
.faq-detail-heading h1{font-size:24px;font-weight:760}.faq-detail-heading p:not(.eyebrow){font-size:12px;line-height:1.6}.faq-list-head h2{font-size:17px;font-weight:720}.faq-list-head>span{font-size:11px}.faq-stat-grid span{font-size:11px;font-weight:550}.faq-stat-grid strong{font-size:23px;font-weight:780}.faq-stat-grid small{font-size:10px}.faq-question h3{font-size:14px;font-weight:700;line-height:1.55}.faq-answer p{font-size:12px;line-height:1.75}.hit-count,.faq-status{font-size:10px}.faq-field>span{font-size:13px;font-weight:650}.faq-field input,.faq-field textarea,.faq-field select{font-family:var(--font-sans);font-size:13px;line-height:1.65}
.knowledge-footer{font-size:11px}.knowledge-footer .mono{font-size:10px}
:global(.sidebar),:global(.topbar),:global(.topbar button),:global(.page-scroll){font-family:var(--font-sans);-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility}
:global(.brand-name){font-family:var(--font-sans);font-weight:780;letter-spacing:.01em}
:global(.nav-group-label),:global(.brand-caption),:global(.topbar .mono){font-family:var(--font-mono)}
:global(.nav-item){font-size:13px;font-weight:550;letter-spacing:.005em}:global(.nav-item.active){font-weight:700}:global(.breadcrumb){font-size:12px;font-weight:550}
.knowledge-page button:disabled{opacity:.5;cursor:not-allowed;transform:none}.knowledge-row{grid-template-columns:minmax(220px,1.4fr) minmax(130px,1fr) 125px 85px 80px 110px;gap:14px}.row-actions{opacity:1;transform:none}.row-title-button{display:block;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;padding:0;border:0;background:transparent;color:inherit;font:inherit;text-align:left;cursor:pointer}.row-title-button:focus-visible{outline:2px solid var(--page-accent);outline-offset:3px}.faq-notice{display:flex;align-items:center;justify-content:space-between;gap:12px;color:var(--color-primary);background:var(--color-bg-subtle)}.faq-notice.error-notice{color:var(--color-error);background:color-mix(in srgb,var(--color-error) 7%,var(--color-bg-surface))}.knowledge-scope-hint{margin:-4px 0 18px;color:var(--color-text-muted);font-size:12px;line-height:1.7}.faq-detail-actions{flex-wrap:wrap}.faq-pair-card:focus-within .faq-pair-actions{opacity:1}.faq-pair-modal{max-height:calc(100dvh - 40px);overflow-y:auto}.faq-answer p,.faq-question h3{white-space:pre-wrap;overflow-wrap:anywhere}.knowledge-list:empty{border:0;box-shadow:none}
@media(max-width:1180px){.knowledge-row{grid-template-columns:minmax(210px,1.4fr) minmax(115px,1fr) 115px 75px 105px}.row-apps{display:none}.row-actions{display:flex}}
@media(max-width:860px){.knowledge-row{grid-template-columns:minmax(0,1fr) auto;padding:15px;gap:12px}.row-actions{grid-column:2;grid-row:4;display:flex;order:6}.row-apps{display:flex}.row-description{padding-left:52px}.faq-detail-actions{gap:6px}.archive-row>.status-badge{grid-column:auto;grid-row:auto}.archive-copy>div{flex-wrap:wrap}.faq-pair-actions{opacity:1}}
</style>
