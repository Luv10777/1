<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { Archive, ArchiveRestore, ChevronDown, FileText, MessageCircleQuestion, Pencil, Plus, Search, Trash2, Upload, X } from 'lucide-vue-next'
import KnowledgeDocumentPanel from '../components/KnowledgeDocumentPanel.vue'
import { brandApi } from '../services/brandApi'
import { DOCUMENT_TYPES, knowledgeApi } from '../services/knowledgeApi'
import { auth } from '../stores/auth'
import { selectedStoreId, selectedStore, selectedStoreRecord } from '../stores/merchantContext'

// 规则只有一条：问答集没归档、问答是启用的，之后开播的直播就会用它回答观众。
// 问答集挂在本店，或挂在品牌上由品牌下所有门店共用；文档上传后整理成建议，逐条确认才写进去。
// 页面左边选问答集或文档，右边直接处理，不再跳详情页。
const sets = ref([])
const brand = ref(null)
const documents = ref([])
const selectedDocId = ref(null)
const uploadForm = ref(null)
const entriesBySet = ref({})
const selectedSetId = ref(null)
const loading = ref(false)
const loadError = ref('')
const saving = ref(false)
const notice = ref('')
const error = ref('')
const showArchived = ref(false)
const search = ref('')
const entryFilter = ref('all')
// 正在编辑的问答：'new' 表示新增，数字是问答 ID。
const editingEntryId = ref(null)
const entryForm = ref({ question: '', answer: '' })
const setForm = ref(null)
const confirming = ref(null)
let requestVersion = 0

const byUpdated = (a, b) => new Date(b.updatedAt) - new Date(a.updatedAt)
const faqSets = computed(() => sets.value.filter(set => set.kind === 'FAQ').sort(byUpdated))
const activeSets = computed(() => faqSets.value.filter(set => set.status !== 'ARCHIVED'))
const archivedSets = computed(() => faqSets.value.filter(set => set.status === 'ARCHIVED'))
const storeSets = computed(() => activeSets.value.filter(set => set.storeId))
const brandSets = computed(() => activeSets.value.filter(set => set.brandId))
const selectedSet = computed(() => faqSets.value.find(set => set.id === selectedSetId.value) || null)
const isArchived = computed(() => selectedSet.value?.status === 'ARCHIVED')
// 品牌的问答由管理员维护；这里只决定显示哪些按钮，权限以后端为准。
const canEditOwner = (owner) => !owner?.brandId || auth.isOwner
const readOnly = computed(() => isArchived.value || !canEditOwner(selectedSet.value))
const ownerLabel = (owner) => owner?.brandId ? `品牌「${brand.value?.name || ''}」` : `本店「${selectedStore.value || ''}」`
const selectedDoc = computed(() => documents.value.find(doc => doc.id === selectedDocId.value) || null)
const docSets = computed(() => selectedDoc.value?.brandId ? brandSets.value : storeSets.value)
const docStatus = (doc) => doc.status === 'PROCESSING' ? '整理中…' : doc.status === 'FAILED' ? '没整理出来'
  : doc.pendingCount ? `${doc.pendingCount} 条待确认` : '已处理完'
const entriesOf = (setId) => entriesBySet.value[setId] || []
const countOn = (setId) => entriesOf(setId).filter(entry => entry.status === 'ACTIVE').length
const entries = computed(() => entriesOf(selectedSetId.value))
const pendingCount = computed(() => entries.value.filter(entry => entry.source === 'LIVE' && entry.status !== 'ACTIVE').length)
const filters = computed(() => [
  { value: 'all', label: '全部', count: entries.value.length },
  { value: 'ACTIVE', label: '启用中', count: entries.value.filter(entry => entry.status === 'ACTIVE').length },
  { value: 'DISABLED', label: '已停用', count: entries.value.filter(entry => entry.status !== 'ACTIVE').length },
])
const visibleEntries = computed(() => {
  const query = search.value.trim().toLowerCase()
  return entries.value.filter(entry => (entryFilter.value === 'all' || entry.status === entryFilter.value)
    && (!query || `${entry.question} ${entry.answer}`.toLowerCase().includes(query)))
})

function resetSelectionView() {
  search.value = ''
  entryFilter.value = 'all'
  editingEntryId.value = null
}

async function loadKnowledge() {
  const version = ++requestVersion
  const storeId = selectedStoreId.value
  const brandId = selectedStoreRecord.value?.brandId ?? null
  sets.value = []
  entriesBySet.value = {}
  documents.value = []
  brand.value = null
  selectedSetId.value = null
  selectedDocId.value = null
  uploadForm.value = null
  loadError.value = ''
  notice.value = ''
  error.value = ''
  saving.value = false
  setForm.value = null
  confirming.value = null
  resetSelectionView()
  if (!storeId) { loading.value = false; return }
  loading.value = true
  try {
    const [storeLoaded, brandLoaded, brandNames, storeDocs, brandDocs] = await Promise.all([
      knowledgeApi.listSets(storeId),
      brandId ? knowledgeApi.listBrandSets(brandId) : [],
      brandId ? brandApi.names() : [],
      knowledgeApi.listDocuments({ storeId }),
      brandId ? knowledgeApi.listDocuments({ brandId }) : [],
    ])
    const loaded = [...storeLoaded, ...brandLoaded]
    const active = loaded.filter(set => set.kind === 'FAQ' && set.status !== 'ARCHIVED')
    const loadedEntries = await Promise.all(active.map(async set => [set.id, await knowledgeApi.listEntries(set.id)]))
    if (version !== requestVersion || storeId !== selectedStoreId.value) return
    sets.value = loaded
    entriesBySet.value = Object.fromEntries(loadedEntries)
    brand.value = brandId ? (brandNames.find(item => item.id === brandId) || { id: brandId, name: '' }) : null
    documents.value = [...storeDocs, ...brandDocs].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
    selectedSetId.value = activeSets.value[0]?.id ?? null
  } catch (failure) {
    if (version === requestVersion) loadError.value = failure.message || '知识库加载失败，请重试。'
  } finally {
    if (version === requestVersion) loading.value = false
  }
}
watch(selectedStoreId, loadKnowledge, { immediate: true })

// A request that finishes after a store switch must never alter the new store's page.
async function mutate(task, apply, message) {
  if (saving.value || loading.value || !selectedStoreId.value) return false
  const version = requestVersion
  const storeId = selectedStoreId.value
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await task()
    if (version !== requestVersion || storeId !== selectedStoreId.value) return false
    apply(result)
    notice.value = typeof message === 'function' ? message(result) : message
    return true
  } catch (failure) {
    if (version === requestVersion) error.value = failure.message || '保存失败，请重试。'
    return false
  } finally {
    if (version === requestVersion) saving.value = false
  }
}

function replaceSet(set) {
  const index = sets.value.findIndex(item => item.id === set.id)
  if (index >= 0) sets.value.splice(index, 1, set)
  else sets.value.unshift(set)
}

async function selectSet(set) {
  if (saving.value || set.id === selectedSetId.value) return
  selectedSetId.value = set.id
  selectedDocId.value = null
  resetSelectionView()
  notice.value = ''
  error.value = ''
  if (entriesBySet.value[set.id]) return
  const version = requestVersion
  try {
    const loaded = await knowledgeApi.listEntries(set.id)
    if (version === requestVersion) entriesBySet.value = { ...entriesBySet.value, [set.id]: loaded }
  } catch (failure) {
    if (version === requestVersion) error.value = failure.message || '问答读取失败，请重试。'
  }
}

// owner：新建时放在本店还是品牌；fromDocument 表示从文档页过来，建好后回到文档继续采纳。
function openSetForm(set = null, { owner = 'store', fromDocument = false } = {}) {
  if (saving.value) return
  error.value = ''
  setForm.value = { id: set?.id ?? null, name: set?.name || '', description: set?.description || '', owner, fromDocument }
}
async function saveSet() {
  const { id, name, description, owner, fromDocument } = setForm.value
  if (!name.trim()) { error.value = '请填写问答集名称。'; return }
  const body = { name: name.trim(), description: description.trim() }
  const create = () => owner === 'brand' && brand.value
    ? knowledgeApi.createBrandSet(brand.value.id, { ...body, kind: 'FAQ' })
    : knowledgeApi.createSet(selectedStoreId.value, { ...body, kind: 'FAQ' })
  const saved = await mutate(
    () => id ? knowledgeApi.updateSet(id, body) : create(),
    set => {
      replaceSet(set)
      if (!entriesBySet.value[set.id]) entriesBySet.value = { ...entriesBySet.value, [set.id]: [] }
      setForm.value = null
      if (!id && !fromDocument) {
        selectedSetId.value = set.id
        selectedDocId.value = null
        resetSelectionView()
      }
    },
    id ? '问答集已保存。' : fromDocument ? '问答集已建好，可以继续采纳问答了。' : '问答集已建好，写下第一条问答吧。',
  )
  // A new set goes straight to writing its first entry.
  if (saved && !id && !fromDocument) startEntry()
}

function selectDocument(doc) {
  if (saving.value) return
  selectedDocId.value = doc.id
  selectedSetId.value = null
  resetSelectionView()
  notice.value = ''
  error.value = ''
}
function openUpload() {
  if (saving.value) return
  error.value = ''
  uploadForm.value = { owner: 'store', file: null }
}
function pickFile(event) {
  uploadForm.value.file = event.target.files?.[0] || null
}
async function submitUpload() {
  const { owner, file } = uploadForm.value
  if (!file) { error.value = '请选择要上传的文档。'; return }
  const target = owner === 'brand' && brand.value ? { brandId: brand.value.id } : { storeId: selectedStoreId.value }
  await mutate(
    () => knowledgeApi.uploadDocument(target, file),
    doc => {
      documents.value = [doc, ...documents.value]
      uploadForm.value = null
      selectedDocId.value = doc.id
      selectedSetId.value = null
      resetSelectionView()
    },
    '已上传，正在阅读和整理。',
  )
}
// 文档页里采纳、忽略之后，刷新左侧的待确认数量；加了问答的集重新读一遍。
async function refreshDocuments() {
  const version = requestVersion
  const storeId = selectedStoreId.value
  try {
    const [storeDocs, brandDocs] = await Promise.all([
      knowledgeApi.listDocuments({ storeId }),
      brand.value ? knowledgeApi.listDocuments({ brandId: brand.value.id }) : [],
    ])
    if (version === requestVersion && storeId === selectedStoreId.value) {
      documents.value = [...storeDocs, ...brandDocs].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
    }
  } catch {
    // The list refreshes again on the next change; the panel itself already shows the result.
  }
}
async function reloadEntries(setId) {
  refreshDocuments()
  const version = requestVersion
  try {
    const loaded = await knowledgeApi.listEntries(setId)
    if (version === requestVersion) entriesBySet.value = { ...entriesBySet.value, [setId]: loaded }
  } catch {
    // Counts catch up the next time the set is opened.
  }
}
function documentDeleted() {
  documents.value = documents.value.filter(doc => doc.id !== selectedDocId.value)
  selectedDocId.value = null
  selectedSetId.value = activeSets.value[0]?.id ?? null
}

async function startEntry(entry = null) {
  if (saving.value || readOnly.value) return
  error.value = ''
  editingEntryId.value = entry ? entry.id : 'new'
  entryForm.value = { question: entry?.question || '', answer: entry?.answer || '' }
  if (!entry) { entryFilter.value = 'all'; search.value = '' }
  await nextTick()
  document.querySelector('.kb-entry-form textarea')?.focus()
}
function cancelEntry() {
  if (!saving.value) editingEntryId.value = null
}
async function saveEntry() {
  const question = entryForm.value.question.trim()
  const answer = entryForm.value.answer.trim()
  if (!question || !answer) { error.value = '问题和回答都要填写。'; return }
  const setId = selectedSetId.value
  const editingId = editingEntryId.value
  await mutate(
    () => editingId === 'new'
      ? knowledgeApi.createEntry(setId, { question, answer, source: 'MANUAL', status: 'ACTIVE' })
      : knowledgeApi.updateEntry(editingId, { question, answer }),
    saved => {
      const list = entriesOf(setId)
      entriesBySet.value = {
        ...entriesBySet.value,
        [setId]: editingId === 'new' ? [saved, ...list] : list.map(entry => entry.id === saved.id ? saved : entry),
      }
      editingEntryId.value = null
    },
    saved => editingId === 'new' ? '已添加，之后开播的直播会用到。' : (saved.status === 'ACTIVE' ? '已保存。' : '已保存，这条还在停用中。'),
  )
}
async function toggleEntry(entry) {
  if (readOnly.value) return
  const status = entry.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  await mutate(
    () => knowledgeApi.updateEntry(entry.id, { status }),
    saved => {
      entriesBySet.value = {
        ...entriesBySet.value,
        [entry.knowledgeSetId]: entriesOf(entry.knowledgeSetId).map(item => item.id === saved.id ? saved : item),
      }
    },
    status === 'ACTIVE' ? '已启用，之后开播的直播会用到。' : '已停用，之后的直播不再使用。',
  )
}

function askDelete(entry) {
  confirming.value = {
    title: '删除这条问答？',
    message: `「${entry.question}」删除后无法找回。只是暂时不用的话，可以把它停用。`,
    confirmText: '删除',
    danger: true,
    run: () => mutate(
      () => knowledgeApi.deleteEntry(entry.id),
      () => {
        entriesBySet.value = {
          ...entriesBySet.value,
          [entry.knowledgeSetId]: entriesOf(entry.knowledgeSetId).filter(item => item.id !== entry.id),
        }
      },
      '已删除。',
    ),
  }
}
function askArchive(set) {
  confirming.value = {
    title: '归档这个问答集？',
    message: `「${set.name}」里的问答将不再用于直播。之后可以在左侧「已归档」里恢复，恢复后一切照旧。`,
    confirmText: '归档',
    danger: false,
    run: () => mutate(
      () => knowledgeApi.archive(set.id),
      archived => {
        replaceSet(archived)
        selectedSetId.value = activeSets.value[0]?.id ?? null
        resetSelectionView()
      },
      '已归档，可以在左侧「已归档」里恢复。',
    ),
  }
}
async function confirmAction() {
  const action = confirming.value
  if (!action || saving.value) return
  const done = await action.run()
  if (done) confirming.value = null
}
async function restoreSet(set) {
  await mutate(
    async () => {
      const restored = await knowledgeApi.restore(set.id)
      return { restored, loaded: entriesBySet.value[set.id] || await knowledgeApi.listEntries(set.id) }
    },
    ({ restored, loaded }) => {
      replaceSet(restored)
      entriesBySet.value = { ...entriesBySet.value, [restored.id]: loaded }
      selectedSetId.value = restored.id
    },
    '已恢复，里面的问答保持归档前的启用状态。',
  )
}
</script>

<template>
  <section class="kb-page">
    <header class="kb-header">
      <div>
        <h1>知识库</h1>
        <p>直播时，系统用这里的问答回答观众。放整家店通用的问题，比如营业时间、停车、会员、售后；品牌下各门店都一样的，放在品牌里写一次就行。某件商品的问题请写在<RouterLink to="/assets/products">商品</RouterLink>对应的商品里。</p>
      </div>
      <span v-if="selectedStore" class="kb-store">{{ selectedStore }}</span>
    </header>

    <p v-if="notice" class="kb-notice" role="status">{{ notice }}</p>
    <p v-if="error && !setForm && !uploadForm && !confirming" class="kb-notice kb-error" role="alert">{{ error }}</p>

    <p v-if="!selectedStoreId" class="kb-empty-line">请先在顶部选择或创建门店。</p>
    <p v-else-if="loading" class="kb-empty-line" role="status">正在读取知识库…</p>
    <div v-else-if="loadError" class="kb-notice kb-error" role="alert">{{ loadError }} <button type="button" class="kb-button" @click="loadKnowledge">重新加载</button></div>

    <section v-else-if="!faqSets.length && !documents.length" class="kb-welcome">
      <MessageCircleQuestion :size="28" aria-hidden="true" />
      <h2>先建一个问答集</h2>
      <p>问答集就是一组问答，按主题分就好，比如「营业与停车」「会员与售后」。只建一个也完全可以。</p>
      <ol>
        <li><strong>建问答集</strong>起个名字</li>
        <li><strong>写问答</strong>一个问题配一个标准回答，写完就生效</li>
        <li><strong>开播</strong>观众问到时，直播按你写的回答</li>
      </ol>
      <div class="kb-welcome-actions">
        <button type="button" class="kb-button kb-primary" :disabled="saving" @click="openSetForm()"><Plus :size="16" />新建问答集</button>
        <button type="button" class="kb-button" :disabled="saving" @click="openUpload"><Upload :size="16" />手里有资料？上传让 AI 整理</button>
      </div>
    </section>

    <div v-else class="kb-layout">
      <aside class="kb-sets" aria-label="问答集与文档">
        <div class="kb-sets-head">
          <h2>本店问答</h2>
          <button type="button" class="kb-icon-button" title="新建本店问答集" aria-label="新建本店问答集" :disabled="saving" @click="openSetForm(null, { owner: 'store' })"><Plus :size="17" /></button>
        </div>
        <ul>
          <li v-for="set in storeSets" :key="set.id">
            <button type="button" class="kb-set" :class="{ selected: set.id === selectedSetId }" :aria-current="set.id === selectedSetId" @click="selectSet(set)">
              <strong>{{ set.name }}</strong>
              <span>{{ entriesOf(set.id).length }} 条 · {{ countOn(set.id) }} 条启用</span>
            </button>
          </li>
        </ul>
        <p v-if="!storeSets.length" class="kb-sets-empty">这家店还没有自己的问答集。</p>

        <template v-if="brand">
          <div class="kb-sets-head kb-sets-group">
            <h2>品牌问答 <small>{{ brand.name }} · 各门店共用</small></h2>
            <button v-if="auth.isOwner" type="button" class="kb-icon-button" title="新建品牌问答集" aria-label="新建品牌问答集" :disabled="saving" @click="openSetForm(null, { owner: 'brand' })"><Plus :size="17" /></button>
          </div>
          <ul>
            <li v-for="set in brandSets" :key="set.id">
              <button type="button" class="kb-set" :class="{ selected: set.id === selectedSetId }" :aria-current="set.id === selectedSetId" @click="selectSet(set)">
                <strong>{{ set.name }}</strong>
                <span>{{ entriesOf(set.id).length }} 条 · {{ countOn(set.id) }} 条启用</span>
              </button>
            </li>
          </ul>
          <p v-if="!brandSets.length" class="kb-sets-empty">{{ auth.isOwner ? '品牌通用的问题写在这里，各门店都能用。' : '品牌还没有问答，由管理员维护。' }}</p>
        </template>

        <div class="kb-sets-head kb-sets-group">
          <h2>文档 <small>上传后由 AI 整理，你来确认</small></h2>
          <button type="button" class="kb-icon-button" title="上传文档" aria-label="上传文档" :disabled="saving" @click="openUpload"><Upload :size="16" /></button>
        </div>
        <ul v-if="documents.length">
          <li v-for="doc in documents" :key="doc.id">
            <button type="button" class="kb-set" :class="{ selected: doc.id === selectedDocId }" :aria-current="doc.id === selectedDocId" @click="selectDocument(doc)">
              <strong><FileText :size="13" aria-hidden="true" /> {{ doc.name }}</strong>
              <span :class="{ 'kb-attention': doc.pendingCount && doc.status === 'READY' }">{{ doc.brandId ? '品牌' : '本店' }} · {{ docStatus(doc) }}</span>
            </button>
          </li>
        </ul>
        <p v-else class="kb-sets-empty">品牌手册、菜单、店铺介绍都可以，AI 会整理出资料和问答，你逐条确认。</p>
        <template v-if="archivedSets.length">
          <button type="button" class="kb-archived-toggle" :aria-expanded="showArchived" @click="showArchived = !showArchived">
            <Archive :size="14" />已归档 {{ archivedSets.length }}<ChevronDown :size="14" :class="{ open: showArchived }" />
          </button>
          <ul v-if="showArchived">
            <li v-for="set in archivedSets" :key="set.id">
              <button type="button" class="kb-set archived" :class="{ selected: set.id === selectedSetId }" :aria-current="set.id === selectedSetId" @click="selectSet(set)">
                <strong>{{ set.name }}</strong><span>已归档，不会用于直播</span>
              </button>
            </li>
          </ul>
        </template>
      </aside>

      <KnowledgeDocumentPanel
        v-if="selectedDoc"
        :key="selectedDoc.id"
        :document-id="selectedDoc.id"
        :owner-label="ownerLabel(selectedDoc)"
        :sets="docSets"
        :can-edit="canEditOwner(selectedDoc)"
        @changed="refreshDocuments"
        @entries-added="reloadEntries"
        @deleted="documentDeleted"
        @create-set="openSetForm(null, { owner: selectedDoc.brandId ? 'brand' : 'store', fromDocument: true })"
      />

      <main v-else-if="selectedSet" class="kb-main">
        <div class="kb-set-head">
          <div>
            <p class="kb-owner">{{ selectedSet.brandId ? `${ownerLabel(selectedSet)} · 各门店共用` : ownerLabel(selectedSet) }}</p>
            <h2>{{ selectedSet.name }}</h2>
            <p v-if="selectedSet.description">{{ selectedSet.description }}</p>
          </div>
          <div v-if="!readOnly" class="kb-set-actions">
            <button type="button" class="kb-button" :disabled="saving" @click="openSetForm(selectedSet)"><Pencil :size="14" />编辑</button>
            <button type="button" class="kb-button" :disabled="saving" @click="askArchive(selectedSet)"><Archive :size="14" />归档</button>
          </div>
        </div>

        <div v-if="isArchived" class="kb-archived-banner">
          <span>这个问答集已归档，里面的问答不会用于直播，也不能修改。</span>
          <button v-if="canEditOwner(selectedSet)" type="button" class="kb-button kb-primary" :disabled="saving" @click="restoreSet(selectedSet)"><ArchiveRestore :size="15" />恢复</button>
        </div>
        <div v-else-if="readOnly" class="kb-archived-banner"><span>品牌问答由管理员维护，这里可以查看。</span></div>

        <div class="kb-toolbar">
          <div class="kb-filters" role="group" aria-label="按状态筛选">
            <button v-for="filter in filters" :key="filter.value" type="button" :class="{ active: entryFilter === filter.value }" :aria-pressed="entryFilter === filter.value" @click="entryFilter = filter.value">{{ filter.label }} {{ filter.count }}</button>
          </div>
          <label class="kb-search"><Search :size="15" aria-hidden="true" /><input v-model="search" type="search" placeholder="搜索问题或回答" aria-label="搜索问题或回答" /></label>
          <button v-if="!readOnly" type="button" class="kb-button kb-primary" :disabled="saving || editingEntryId === 'new'" @click="startEntry()"><Plus :size="16" />添加问答</button>
        </div>

        <p v-if="pendingCount && !readOnly" class="kb-pending">有 {{ pendingCount }} 条问答是直播中顺手存进来的，默认停用。看一下回答没问题，打开开关就会生效。</p>

        <form v-if="editingEntryId === 'new'" class="kb-entry kb-entry-form" @submit.prevent="saveEntry">
          <label><span>观众会怎么问</span><textarea v-model="entryForm.question" rows="2" maxlength="500" required placeholder="例如：附近好停车吗？" :disabled="saving" /></label>
          <label><span>标准回答</span><textarea v-model="entryForm.answer" rows="3" required placeholder="例如：店门口有 20 个免费车位，周末建议早点来。" :disabled="saving" /></label>
          <div class="kb-form-actions">
            <button type="button" class="kb-button" :disabled="saving" @click="cancelEntry">取消</button>
            <button type="submit" class="kb-button kb-primary" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
          </div>
        </form>

        <ul class="kb-entries">
          <li v-for="entry in visibleEntries" :key="entry.id">
            <form v-if="editingEntryId === entry.id" class="kb-entry kb-entry-form" @submit.prevent="saveEntry">
              <label><span>观众会怎么问</span><textarea v-model="entryForm.question" rows="2" maxlength="500" required :disabled="saving" /></label>
              <label><span>标准回答</span><textarea v-model="entryForm.answer" rows="3" required :disabled="saving" /></label>
              <div class="kb-form-actions">
                <button type="button" class="kb-button" :disabled="saving" @click="cancelEntry">取消</button>
                <button type="submit" class="kb-button kb-primary" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
              </div>
            </form>
            <article v-else class="kb-entry" :class="{ off: entry.status !== 'ACTIVE' }">
              <div class="kb-entry-text">
                <h3>{{ entry.question }}</h3>
                <p>{{ entry.answer }}</p>
                <span v-if="entry.source === 'LIVE'" class="kb-tag">直播中保存</span>
                <span v-else-if="entry.source === 'DOCUMENT'" class="kb-tag kb-tag-doc">从文档整理</span>
              </div>
              <div class="kb-entry-side">
                <button type="button" role="switch" class="kb-switch" :aria-checked="entry.status === 'ACTIVE'" :aria-label="`${entry.question}：${entry.status === 'ACTIVE' ? '启用中' : '已停用'}`" :disabled="saving || readOnly" @click="toggleEntry(entry)">
                  <span class="kb-switch-track"><span class="kb-switch-knob" /></span>{{ entry.status === 'ACTIVE' ? '启用' : '停用' }}
                </button>
                <div v-if="!readOnly" class="kb-entry-actions">
                  <button type="button" class="kb-icon-button" title="修改" :aria-label="`修改：${entry.question}`" :disabled="saving" @click="startEntry(entry)"><Pencil :size="15" /></button>
                  <button type="button" class="kb-icon-button danger" title="删除" :aria-label="`删除：${entry.question}`" :disabled="saving" @click="askDelete(entry)"><Trash2 :size="15" /></button>
                </div>
              </div>
            </article>
          </li>
        </ul>

        <div v-if="!entries.length && editingEntryId !== 'new'" class="kb-empty">
          <p>{{ readOnly ? '这个问答集是空的。' : '还没有问答。写下观众常问的问题和你的标准回答。' }}</p>
          <button v-if="!readOnly" type="button" class="kb-button kb-primary" :disabled="saving" @click="startEntry()"><Plus :size="16" />添加第一条问答</button>
        </div>
        <p v-else-if="entries.length && !visibleEntries.length" class="kb-empty-line">没有符合条件的问答。</p>
      </main>
      <main v-else class="kb-main kb-empty"><p>在左侧选一个问答集或文档，或者新建一个问答集。</p></main>
    </div>

    <div v-if="setForm" class="kb-overlay" @click.self="setForm = saving ? setForm : null">
      <form class="kb-dialog" role="dialog" aria-modal="true" aria-labelledby="kb-set-title" @submit.prevent="saveSet">
        <header><h2 id="kb-set-title">{{ setForm.id ? '修改问答集' : '新建问答集' }}</h2><button type="button" class="kb-icon-button" aria-label="关闭" :disabled="saving" @click="setForm = null"><X :size="17" /></button></header>
        <fieldset v-if="!setForm.id && brand && auth.isOwner && !setForm.fromDocument" class="kb-owner-choice">
          <legend>放在哪里</legend>
          <label><input v-model="setForm.owner" type="radio" value="store" :disabled="saving" /><span><strong>本店</strong>只用于「{{ selectedStore }}」</span></label>
          <label><input v-model="setForm.owner" type="radio" value="brand" :disabled="saving" /><span><strong>品牌</strong>「{{ brand.name }}」下所有门店共用</span></label>
        </fieldset>
        <label><span>名称</span><input v-model="setForm.name" maxlength="120" required placeholder="例如：营业与停车" :disabled="saving" autofocus /></label>
        <label><span>说明 <em>选填</em></span><textarea v-model="setForm.description" maxlength="500" rows="3" placeholder="这组问答主要回答哪类问题" :disabled="saving" /></label>
        <p v-if="error" class="kb-notice kb-error" role="alert">{{ error }}</p>
        <footer><button type="button" class="kb-button" :disabled="saving" @click="setForm = null">取消</button><button type="submit" class="kb-button kb-primary" :disabled="saving">{{ saving ? '保存中…' : setForm.id ? '保存' : '建好了，去写问答' }}</button></footer>
      </form>
    </div>

    <div v-if="uploadForm" class="kb-overlay" @click.self="uploadForm = saving ? uploadForm : null">
      <form class="kb-dialog" role="dialog" aria-modal="true" aria-labelledby="kb-upload-title" @submit.prevent="submitUpload">
        <header><h2 id="kb-upload-title">上传文档</h2><button type="button" class="kb-icon-button" aria-label="关闭" :disabled="saving" @click="uploadForm = null"><X :size="17" /></button></header>
        <p>AI 会读一遍文档，整理出{{ brand ? '品牌或门店资料' : '门店资料' }}的建议和常见问答。每一条都要你确认后才会生效，不会直接改动任何东西。</p>
        <fieldset v-if="brand && auth.isOwner" class="kb-owner-choice">
          <legend>这是谁的资料</legend>
          <label><input v-model="uploadForm.owner" type="radio" value="store" :disabled="saving" /><span><strong>本店</strong>整理成「{{ selectedStore }}」的地址、营业时间、交通和本店问答</span></label>
          <label><input v-model="uploadForm.owner" type="radio" value="brand" :disabled="saving" /><span><strong>品牌</strong>整理成「{{ brand.name }}」的品牌故事、定位、文化和品牌问答</span></label>
        </fieldset>
        <label class="kb-file"><span>文件 <em>PDF、Word（.docx）、TXT、Markdown，10MB 以内</em></span><input type="file" :accept="DOCUMENT_TYPES" :disabled="saving" @change="pickFile" /></label>
        <p v-if="error" class="kb-notice kb-error" role="alert">{{ error }}</p>
        <footer><button type="button" class="kb-button" :disabled="saving" @click="uploadForm = null">取消</button><button type="submit" class="kb-button kb-primary" :disabled="saving || !uploadForm.file">{{ saving ? '上传中…' : '上传并整理' }}</button></footer>
      </form>
    </div>

    <div v-if="confirming" class="kb-overlay" @click.self="confirming = saving ? confirming : null">
      <section class="kb-dialog" role="alertdialog" aria-modal="true" aria-labelledby="kb-confirm-title" aria-describedby="kb-confirm-message">
        <header><h2 id="kb-confirm-title">{{ confirming.title }}</h2></header>
        <p id="kb-confirm-message">{{ confirming.message }}</p>
        <p v-if="error" class="kb-notice kb-error" role="alert">{{ error }}</p>
        <footer><button type="button" class="kb-button" :disabled="saving" @click="confirming = null">取消</button><button type="button" class="kb-button" :class="confirming.danger ? 'kb-danger' : 'kb-primary'" :disabled="saving" @click="confirmAction">{{ saving ? '处理中…' : confirming.confirmText }}</button></footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.kb-page{max-width:1240px;margin:0 auto;padding:14px clamp(16px,3vw,44px) 32px;color:var(--color-primary);font-family:var(--font-sans)}
.kb-header{display:flex;align-items:flex-start;justify-content:space-between;gap:24px;margin-bottom:20px}
.kb-header h1{margin:8px 0 8px;font:700 clamp(26px,3vw,34px)/1.15 var(--font-serif)}
.kb-header p{max-width:720px;margin:0;color:var(--color-text-muted);font-size:13px;line-height:1.7}
.kb-header a{color:var(--color-accent-text);text-decoration:underline;text-underline-offset:3px}
.kb-store{flex:none;margin-top:12px;padding:5px 10px;border:1px solid var(--color-border-subtle);border-radius:4px;color:var(--color-text-muted);background:var(--color-bg-surface);font-size:12px}

.kb-notice{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:0 0 14px;padding:10px 13px;border-radius:6px;color:var(--color-primary);background:var(--color-bg-subtle);font-size:13px}
.kb-error{color:var(--color-error);background:color-mix(in srgb,var(--color-error) 8%,var(--color-bg-surface))}
.kb-empty-line{margin:24px 0;color:var(--color-text-muted);font-size:13px;text-align:center}

.kb-button{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-height:36px;padding:0 13px;border:1px solid var(--color-border-subtle);border-radius:4px;color:var(--color-primary);background:var(--color-bg-surface);font-size:13px;white-space:nowrap;cursor:pointer;transition:border-color .15s ease,background .15s ease}
.kb-button:hover:not(:disabled){border-color:var(--color-border-strong)}
.kb-button:disabled{cursor:not-allowed;opacity:.55}
.kb-primary{border-color:var(--color-accent);color:var(--color-on-accent);background:var(--color-accent);font-weight:600}
.kb-primary:hover:not(:disabled){border-color:var(--color-accent-hover);background:var(--color-accent-hover)}
.kb-danger{border-color:var(--color-error);color:var(--color-on-accent);background:var(--color-error);font-weight:600}
.kb-icon-button{display:inline-grid;place-items:center;width:32px;height:32px;padding:0;border:1px solid transparent;border-radius:4px;color:var(--color-text-muted);background:transparent;cursor:pointer}
.kb-icon-button:hover:not(:disabled){border-color:var(--color-border-subtle);color:var(--color-primary);background:var(--color-bg-surface)}
.kb-icon-button.danger:hover:not(:disabled){color:var(--color-error)}
.kb-icon-button:disabled{cursor:not-allowed;opacity:.5}
.kb-button:focus-visible,.kb-icon-button:focus-visible,.kb-set:focus-visible,.kb-switch:focus-visible,.kb-filters button:focus-visible,.kb-archived-toggle:focus-visible{outline:2px solid var(--color-accent);outline-offset:2px}

.kb-welcome{display:grid;justify-items:center;gap:12px;max-width:560px;margin:28px auto;padding:34px 28px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface);text-align:center;color:var(--color-accent-text)}
.kb-welcome h2{margin:0;color:var(--color-primary);font:700 22px var(--font-serif)}
.kb-welcome p{margin:0;color:var(--color-text-muted);font-size:13px;line-height:1.7}
.kb-welcome ol{display:grid;gap:8px;width:100%;margin:6px 0 8px;padding:0;list-style:none;counter-reset:step;text-align:left}
.kb-welcome li{display:flex;align-items:baseline;gap:10px;padding:10px 12px;border-radius:6px;background:var(--color-bg-canvas);color:var(--color-text-muted);font-size:13px;counter-increment:step}
.kb-welcome li::before{content:counter(step);flex:none;display:grid;place-items:center;width:20px;height:20px;border-radius:50%;color:var(--color-on-accent);background:var(--color-accent);font-size:12px;font-weight:700}
.kb-welcome li strong{flex:none;width:70px;color:var(--color-primary)}

.kb-layout{display:grid;grid-template-columns:250px minmax(0,1fr);gap:20px;align-items:start}
.kb-sets{position:sticky;top:12px;display:grid;gap:6px;padding:12px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface)}
.kb-sets-head{display:flex;align-items:center;justify-content:space-between;padding:0 2px 4px}
.kb-sets-head h2{margin:0;font-size:13px;font-weight:700}
.kb-sets ul{display:grid;gap:4px;margin:0;padding:0;list-style:none}
.kb-set{display:grid;gap:3px;width:100%;padding:9px 10px;border:1px solid transparent;border-radius:6px;color:var(--color-primary);background:transparent;text-align:left;cursor:pointer}
.kb-set strong{overflow:hidden;font-size:13px;font-weight:600;text-overflow:ellipsis;white-space:nowrap}
.kb-set span{color:var(--color-text-muted);font-size:12px}
.kb-set:hover{background:var(--color-bg-canvas)}
.kb-set.selected{border-color:color-mix(in srgb,var(--color-accent) 40%,transparent);background:color-mix(in srgb,var(--color-accent) 8%,var(--color-bg-surface))}
.kb-set.archived strong{color:var(--color-text-muted)}
.kb-sets-empty{margin:4px 2px;color:var(--color-text-muted);font-size:12px}
.kb-archived-toggle{display:flex;align-items:center;gap:6px;margin-top:6px;padding:8px 4px 4px;border:0;border-top:1px solid var(--color-border-subtle);color:var(--color-text-muted);background:transparent;font-size:12px;cursor:pointer}
.kb-archived-toggle svg:last-child{margin-left:auto;transition:transform .15s ease}
.kb-archived-toggle svg.open{transform:rotate(180deg)}
.kb-sets-group{margin-top:10px;padding-top:12px;border-top:1px solid var(--color-border-subtle)}
.kb-sets-head h2 small{display:block;margin-top:2px;color:var(--color-text-muted);font-size:12px;font-weight:400}
.kb-set strong svg{vertical-align:-2px}
.kb-attention{color:var(--color-accent-text)!important;font-weight:600}
.kb-owner{margin:0 0 4px;color:var(--color-text-muted);font-size:12px}
.kb-welcome-actions{display:flex;flex-wrap:wrap;justify-content:center;gap:10px}
.kb-tag-doc{color:var(--color-info);background:color-mix(in srgb,var(--color-info) 10%,var(--color-bg-surface))}
.kb-owner-choice{display:grid;gap:8px;margin:0;padding:0;border:0}
.kb-owner-choice legend{margin-bottom:6px;color:var(--color-text-muted);font-size:12px;font-weight:600}
.kb-owner-choice label{display:flex!important;align-items:flex-start;gap:9px;padding:10px 12px;border:1px solid var(--color-border-subtle);border-radius:6px;cursor:pointer}
.kb-owner-choice label:has(input:checked){border-color:var(--color-accent);background:color-mix(in srgb,var(--color-accent) 6%,var(--color-bg-surface))}
.kb-owner-choice input{flex:none;width:auto;margin-top:3px;padding:0;accent-color:var(--color-accent)}
.kb-owner-choice span{display:grid;gap:2px;color:var(--color-text-muted);font-size:12px;font-weight:400!important}
.kb-owner-choice strong{color:var(--color-primary);font-size:13px}
.kb-file input{padding:8px;border:1px dashed var(--color-border-subtle);border-radius:6px;color:var(--color-text-muted);font-size:13px}

.kb-main{min-width:0;padding:18px 20px 22px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface)}
.kb-set-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:14px}
.kb-set-head h2{margin:0;font:700 20px/1.3 var(--font-serif);overflow-wrap:anywhere}
.kb-set-head p{margin:5px 0 0;color:var(--color-text-muted);font-size:13px;line-height:1.6}
.kb-set-actions{display:flex;gap:8px;flex:none}
.kb-archived-banner{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px;padding:11px 13px;border-radius:6px;background:var(--color-bg-canvas);color:var(--color-text-muted);font-size:13px}

.kb-toolbar{display:flex;flex-wrap:wrap;align-items:center;gap:10px;padding:12px 0;border-top:1px solid var(--color-border-subtle)}
.kb-filters{display:flex;gap:4px;padding:3px;border-radius:6px;background:var(--color-bg-canvas)}
.kb-filters button{min-height:30px;padding:0 10px;border:0;border-radius:4px;color:var(--color-text-muted);background:transparent;font-size:12px;cursor:pointer}
.kb-filters button.active{color:var(--color-primary);background:var(--color-bg-surface);box-shadow:var(--shadow-paper);font-weight:600}
.kb-search{display:flex;flex:1 1 180px;align-items:center;gap:8px;min-height:36px;padding:0 10px;border:1px solid var(--color-border-subtle);border-radius:4px;color:var(--color-text-muted);background:var(--color-bg-surface)}
.kb-search:focus-within{border-color:var(--color-accent)}
.kb-search input{width:100%;min-width:0;border:0;outline:0;color:var(--color-primary);background:transparent;font-size:13px}
.kb-pending{margin:0 0 12px;padding:10px 12px;border-left:3px solid var(--color-warning);border-radius:4px;background:color-mix(in srgb,var(--color-warning) 9%,var(--color-bg-surface));color:var(--color-primary);font-size:13px;line-height:1.6}

.kb-entries{display:grid;gap:8px;margin:0;padding:0;list-style:none}
.kb-entry{display:flex;justify-content:space-between;gap:16px;padding:13px 14px;border:1px solid var(--color-border-subtle);border-radius:6px;background:var(--color-bg-surface)}
.kb-entry.off{background:var(--color-bg-canvas)}
.kb-entry.off .kb-entry-text{opacity:.62}
.kb-entry-text{min-width:0}
.kb-entry-text h3{margin:0 0 5px;font-size:14px;font-weight:600;line-height:1.5;overflow-wrap:anywhere;white-space:pre-wrap}
.kb-entry-text p{margin:0;color:var(--color-text-muted);font-size:13px;line-height:1.7;overflow-wrap:anywhere;white-space:pre-wrap}
.kb-tag{display:inline-block;margin-top:7px;padding:2px 7px;border-radius:4px;color:var(--color-warning);background:color-mix(in srgb,var(--color-warning) 10%,var(--color-bg-surface));font-size:12px}
.kb-entry-side{display:flex;flex:none;flex-direction:column;align-items:flex-end;gap:6px}
.kb-entry-actions{display:flex;gap:2px}
.kb-switch{display:inline-flex;align-items:center;gap:7px;min-height:32px;padding:0 4px;border:0;color:var(--color-text-muted);background:transparent;font-size:12px;cursor:pointer}
.kb-switch[aria-checked="true"]{color:var(--color-success);font-weight:600}
.kb-switch:disabled{cursor:not-allowed;opacity:.6}
.kb-switch-track{position:relative;width:34px;height:20px;border-radius:10px;background:color-mix(in srgb,var(--color-text-muted) 38%,var(--color-bg-surface));transition:background .15s ease}
.kb-switch-knob{position:absolute;top:2px;left:2px;width:16px;height:16px;border-radius:50%;background:var(--color-bg-surface);box-shadow:0 1px 2px rgba(0,0,0,.2);transition:transform .15s ease}
.kb-switch[aria-checked="true"] .kb-switch-track{background:var(--color-success)}
.kb-switch[aria-checked="true"] .kb-switch-knob{transform:translateX(14px)}

.kb-entry-form{display:grid;grid-template-columns:minmax(0,1fr);gap:10px;margin-bottom:8px;border-color:var(--color-accent);background:var(--color-bg-surface)}
.kb-entry-form label,.kb-dialog label{display:grid;gap:6px}
.kb-entry-form label span,.kb-dialog label span{color:var(--color-text-muted);font-size:12px;font-weight:600}
.kb-dialog label em{font-style:normal;font-weight:400}
.kb-entry-form textarea,.kb-dialog input:not([type=radio]):not([type=file]),.kb-dialog textarea{width:100%;padding:9px 10px;border:1px solid var(--color-border-subtle);border-radius:4px;color:var(--color-primary);background:var(--color-bg-surface);font:13px/1.6 var(--font-sans);resize:vertical}
.kb-entry-form textarea:focus,.kb-dialog input:not([type=radio]):focus,.kb-dialog textarea:focus{border-color:var(--color-accent);outline:0}
.kb-form-actions{display:flex;justify-content:flex-end;gap:8px}

.kb-empty{display:grid;justify-items:center;gap:12px;padding:30px 12px;color:var(--color-text-muted);font-size:13px;text-align:center}
.kb-empty p{margin:0}

.kb-overlay{position:fixed;inset:0;z-index:50;display:grid;place-items:center;padding:16px;background:rgba(20,16,14,.42)}
.kb-dialog{display:grid;gap:14px;width:min(460px,100%);padding:20px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface);box-shadow:var(--shadow-paper-elevated)}
.kb-dialog header{display:flex;align-items:center;justify-content:space-between;gap:12px}
.kb-dialog h2{margin:0;font:700 18px var(--font-serif)}
.kb-dialog>p{margin:0;color:var(--color-text-muted);font-size:13px;line-height:1.7}
.kb-dialog footer{display:flex;justify-content:flex-end;gap:8px}

@media (max-width:860px){
  .kb-header{flex-direction:column;gap:8px}
  .kb-store{margin-top:0}
  .kb-layout{grid-template-columns:1fr}
  .kb-sets{position:static}
  .kb-main{padding:14px}
  .kb-set-head{flex-direction:column}
  .kb-entry{flex-direction:column;gap:10px}
  .kb-entry-side{flex-direction:row;align-items:center;justify-content:space-between}
}
</style>
