<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  Archive,
  ArrowLeft,
  ArrowUpRight,
  BookOpen,
  ChevronDown,
  FileText,
  Filter,
  MessageCircleQuestion,
  MoreHorizontal,
  Plus,
  Search,
  Trash2,
  Upload,
  Pencil,
  Target,
} from 'lucide-vue-next'

const activeTab = ref('all')
const searchQuery = ref('')
const filterOpen = ref(false)
const createOpen = ref(false)
const selectedStatus = ref('all')
const faqDetailOpen = ref(false)
const selectedFaq = ref(null)
const faqPairModalOpen = ref(false)
const faqSetModalOpen = ref(false)
const editingFaqPair = ref(null)
const faqNotice = ref('')
const faqSetForm = ref({ name: '', description: '' })
const archiveOpen = ref(false)
const archivedKnowledge = ref([
  { id: 'archive_01', name: '2023 年旧版产品手册', type: '文档资料', archivedAt: '2024-08-12', reason: '内容已由新版手册替代', metric: '86 个切片' },
  { id: 'archive_02', name: '节日活动 FAQ · 端午', type: '常见问答', archivedAt: '2024-07-02', reason: '活动已结束', metric: '12 个问答对' },
])

function closePopovers() {
  filterOpen.value = false
  createOpen.value = false
}

onMounted(() => document.addEventListener('click', closePopovers))
onBeforeUnmount(() => document.removeEventListener('click', closePopovers))

const tabs = [
  { id: 'all', label: '全部', count: 4 },
  { id: 'rag', label: '文档资料', suffix: 'RAG', count: 2 },
  { id: 'faq', label: '常见问答', suffix: 'FAQ', count: 2 },
]

const knowledge = [
  {
    id: 'kb_20240820_01',
    name: '门店服务流程标准',
    type: 'rag',
    icon: FileText,
    tone: 'blue',
    meta: 'KB-1724580001 · 更新于 08 月 20 日',
    metric: '42 个切片',
    description: '门店接待、售后及会员服务的标准化流程',
    apps: ['客服 Agent', '员工助手'],
    status: 'active',
    statusLabel: '已发布',
    sync: '刚刚同步',
  },
  {
    id: 'kb_20240821_02',
    name: '产品知识手册 · 2024 夏季版',
    type: 'rag',
    icon: BookOpen,
    tone: 'blue',
    meta: 'KB-1724666402 · 更新于 08 月 21 日',
    metric: '128 个切片',
    description: '材质、卖点、规格与搭配建议，覆盖 86 款商品',
    apps: ['客服 Agent', '内容创作'],
    status: 'syncing',
    statusLabel: '向量化中',
    sync: '已完成 72%',
  },
  {
    id: 'kb_20240824_03',
    name: '高频售前问题集',
    type: 'faq',
    icon: MessageCircleQuestion,
    tone: 'cinnabar',
    meta: 'KB-1724922003 · 更新于 08 月 24 日',
    metric: '15 个问答对',
    description: '从近 30 天会话中沉淀的高频购买疑问',
    apps: ['客服 Agent'],
    status: 'active',
    statusLabel: '已发布',
    sync: '命中率 94.2%',
  },
  {
    id: 'kb_20240825_04',
    name: '售后政策与退换货',
    type: 'faq',
    icon: MessageCircleQuestion,
    tone: 'cinnabar',
    meta: 'KB-1725008404 · 更新于 08 月 25 日',
    metric: '28 个问答对',
    description: '七天无理由、安装破损与保修政策问答',
    apps: ['客服 Agent', '售后 Copilot'],
    status: 'draft',
    statusLabel: '草稿',
    sync: '待发布',
  },
]

const faqForm = ref({ question: '', answer: '', status: 'active' })
const knowledgeFaqItem = knowledge.find((item) => item.type === 'faq' && item.status === 'active')
const customKnowledge = ref([])
const allKnowledge = computed(() => [...knowledge, ...customKnowledge.value])
const faqHubItem = { id: 'faq-hub', name: '常见问答集', description: '统一管理客服、售前与售后场景的标准问答。' }
const faqPairSets = ref({
  'faq-hub': [
    { id: 'hub-pair-01', question: '如何联系在线客服？', answer: '进入订单详情后点击“联系在线客服”，也可以在工作时间拨打服务热线获得帮助。', status: 'active', hits: 512 },
    { id: 'hub-pair-02', question: '门店营业时间是几点？', answer: '常规营业时间为每日 10:00–22:00，不同门店可能略有差异，请以门店页信息为准。', status: 'active', hits: 386 },
  ],
  kb_20240824_03: [
    { id: 'sale-pair-01', question: '你们支持哪些配送方式？', answer: '目前支持门店自提、同城配送和全国物流配送。下单时可根据收货地址选择对应方式。', status: 'active', hits: 328 },
    { id: 'sale-pair-02', question: '购买后可以无理由退换吗？', answer: '商品签收后 7 天内，在保持商品完好、配件齐全的情况下支持无理由退换。', status: 'active', hits: 241 },
    { id: 'sale-pair-03', question: '优惠券可以和会员折扣一起使用吗？', answer: '大部分优惠券可与会员折扣叠加，结算页会自动展示当前可用的最优优惠组合。', status: 'draft', hits: 174 },
  ],
  kb_20240825_04: [
    { id: 'after-pair-01', question: '商品出现破损应该如何处理？', answer: '请在签收后 48 小时内拍照联系客服，我们会优先安排补发或退换处理。', status: 'active', hits: 205 },
    { id: 'after-pair-02', question: '保修期是多久？', answer: '常规商品提供 12 个月质保，特殊品类以商品详情页标注的服务承诺为准。', status: 'draft', hits: 118 },
  ],
})
const faqPairs = computed({
  get: () => faqPairSets.value[selectedFaq.value?.id] || [],
  set: (value) => { if (selectedFaq.value) faqPairSets.value[selectedFaq.value.id] = value },
})

const filteredKnowledge = computed(() => allKnowledge.value.filter((item) => {
  const matchesTab = activeTab.value === 'all' || item.type === activeTab.value
  const query = searchQuery.value.trim().toLowerCase()
  const matchesSearch = !query || [item.name, item.description, item.meta, ...item.apps].join(' ').toLowerCase().includes(query)
  const matchesStatus = selectedStatus.value === 'all' || item.status === selectedStatus.value
  return matchesTab && matchesSearch && matchesStatus
}))

const statusClass = (status) => ({
  active: 'status-active',
  syncing: 'status-syncing',
  draft: 'status-draft',
}[status])

const toneClass = (tone) => `tone-${tone}`

function chooseCreate(type) {
  createOpen.value = false
  if (type === 'faq') {
    faqSetForm.value = { name: '', description: '' }
    faqSetModalOpen.value = true
    return
  }
  window.dispatchEvent(new CustomEvent('knowledge-create', { detail: type }))
}

function openFaqDetail(item = knowledgeFaqItem) {
  selectedFaq.value = item
  faqDetailOpen.value = true
  faqNotice.value = ''
}


function closeFaqDetail() {
  faqDetailOpen.value = false
  selectedFaq.value = null
  faqPairModalOpen.value = false
  faqSetModalOpen.value = false
  editingFaqPair.value = null
}


function saveFaqSet() {
  const name = faqSetForm.value.name.trim()
  const description = faqSetForm.value.description.trim()
  if (!name) {
    faqNotice.value = '请填写知识集名称。'
    return
  }
  const id = `kb_faq_${Date.now()}`
  const item = { id, name, type: 'faq', icon: MessageCircleQuestion, tone: 'cinnabar', meta: `KB-${Date.now()} · 刚刚创建`, metric: '0 个问答对', description: description || '自定义 FAQ 知识集', apps: ['客服 Agent'], status: 'draft', statusLabel: '草稿', sync: '待添加问答' }
  customKnowledge.value.unshift(item)
  faqPairSets.value[id] = []
  faqSetModalOpen.value = false
  faqNotice.value = ''
  openFaqDetail(item)
}

function openFaqPairEditor(pair = null) {
  editingFaqPair.value = pair
  faqForm.value = pair ? { question: pair.question, answer: pair.answer, status: pair.status } : { question: '', answer: '', status: 'active' }
  faqPairModalOpen.value = true
  faqNotice.value = ''
}

function saveFaqPair() {
  const question = faqForm.value.question.trim()
  const answer = faqForm.value.answer.trim()
  if (!question || !answer) {
    faqNotice.value = '请先填写问题和标准答案。'
    return
  }
  if (editingFaqPair.value) {
    Object.assign(editingFaqPair.value, { question, answer, status: faqForm.value.status })
  } else {
    faqPairs.value.unshift({ id: `faq-pair-${Date.now()}`, question, answer, status: faqForm.value.status, hits: 0 })
  }
  faqPairModalOpen.value = false
  editingFaqPair.value = null
  faqNotice.value = '问答对已保存（模拟）'
}

function toggleFaqPair(pair) {
  pair.status = pair.status === 'active' ? 'draft' : 'active'
  faqNotice.value = pair.status === 'active' ? '问答对已启用（模拟）' : '问答对已移至草稿（模拟）'
}

function removeFaqPair(pair) {
  if (!window.confirm(`确定删除“${pair.question}”吗？`)) return
  const index = faqPairs.value.findIndex((item) => item.id === pair.id)
  if (index >= 0) faqPairs.value.splice(index, 1)
  faqNotice.value = '问答对已删除（模拟）'
}

function restoreArchived(item) {
  archivedKnowledge.value = archivedKnowledge.value.filter((archived) => archived.id !== item.id)
  customKnowledge.value.unshift({ id: item.id, name: item.name, type: item.type === '常见问答' ? 'faq' : 'rag', icon: item.type === '常见问答' ? MessageCircleQuestion : FileText, tone: item.type === '常见问答' ? 'cinnabar' : 'blue', meta: `KB-${item.id} · 已恢复`, metric: item.metric, description: '从归档中恢复的知识集', apps: ['客服 Agent'], status: 'draft', statusLabel: '草稿', sync: '已恢复，待发布' })
  faqNotice.value = `“${item.name}”已恢复到知识库（模拟）`
}

function deleteArchived(item) {
  if (!window.confirm(`确定永久删除“${item.name}”吗？此操作不可撤销。`)) return
  archivedKnowledge.value = archivedKnowledge.value.filter((archived) => archived.id !== item.id)
  faqNotice.value = '归档知识集已永久删除（模拟）'
}
</script>

<template>
  <div class="knowledge-page">
    <header v-if="!faqDetailOpen" class="knowledge-header">
      <div class="header-copy">
        <h1>知识库</h1>
        <p>管理客服与内容创作的 AI 专属知识上下文</p>
      </div>

      <div class="header-tools">
        <label class="knowledge-search">
          <Search :size="16" stroke-width="1.8" />
          <input v-model="searchQuery" type="search" placeholder="搜索知识名称、ID 或标签" aria-label="搜索知识库" />
          <kbd>⌘ K</kbd>
        </label>
        <div class="filter-wrap">
          <button class="ghost-button" type="button" :class="{ selected: selectedStatus !== 'all' }" @click.stop="filterOpen = !filterOpen; createOpen = false">
            <Filter :size="15" /> 筛选 <span v-if="selectedStatus !== 'all'" class="filter-count">1</span>
          </button>
          <div v-if="filterOpen" class="popover filter-popover" @click.stop>
            <p>状态</p>
            <button v-for="option in [{ value: 'all', label: '全部状态' }, { value: 'active', label: '已发布' }, { value: 'syncing', label: '向量化中' }, { value: 'draft', label: '草稿' }]" :key="option.value" type="button" :class="{ checked: selectedStatus === option.value }" @click="selectedStatus = option.value; filterOpen = false">
              <span>{{ option.label }}</span><span v-if="selectedStatus === option.value">✓</span>
            </button>
          </div>
        </div>
        <div class="create-wrap">
          <button class="create-button" type="button" @click.stop="createOpen = !createOpen; filterOpen = false">
            <Plus :size="17" stroke-width="2.2" /> 新建知识 <ChevronDown :size="15" :class="{ rotate: createOpen }" />
          </button>
          <div v-if="createOpen" class="popover create-popover" @click.stop>
            <button type="button" @click="chooseCreate('document')"><Upload :size="16" /><span><strong>上传文档</strong><small>PDF、DOCX、TXT、Markdown</small></span></button>
            <button type="button" @click="chooseCreate('faq')"><MessageCircleQuestion :size="16" /><span><strong>添加问答对</strong><small>沉淀客服与售前经验</small></span></button>
          </div>
        </div>
      </div>
    </header>

    <nav v-if="!faqDetailOpen" class="knowledge-tabs" aria-label="知识类型">
      <button v-for="tab in tabs" :key="tab.id" type="button" class="knowledge-tab" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">
        <span>{{ tab.label }}</span><span v-if="tab.suffix" class="tab-suffix">{{ tab.suffix }}</span><span class="tab-count">{{ tab.count }}</span>
        <span v-if="activeTab === tab.id" class="tab-indicator" />
      </button>
    </nav>

    <main v-if="!faqDetailOpen">
      <section class="faq-feature-card">
        <div class="faq-feature-mark"><MessageCircleQuestion :size="22" stroke-width="1.8" /></div>
        <div class="faq-feature-copy">
          <p class="eyebrow">FAQ KNOWLEDGE SET</p>
          <h2>常见问答集</h2>
          <p>把客服经验整理成可直接复用的问答对，让 AI 在每次对话中给出更准确、更一致的回答。</p>
          <div class="faq-feature-meta"><span>2 个问答集</span><i /> <span>43 个问答对</span><i /> <span>平均命中率 94.2%</span></div>
        </div>
        <button type="button" class="faq-feature-action" @click="openFaqDetail(faqHubItem)">管理问答对 <ArrowUpRight :size="15" /></button>
      </section>

      <div class="list-toolbar"><div><strong>{{ filteredKnowledge.length }}</strong> 个知识集 <span>·</span> 最近更新优先</div><button class="view-button" type="button" @click="archiveOpen = true"><Archive :size="14" /> 归档管理 <span v-if="archivedKnowledge.length" class="archive-count">{{ archivedKnowledge.length }}</span></button></div>

      <TransitionGroup name="knowledge-list" tag="section" class="knowledge-list" aria-live="polite">
        <article v-for="(item, index) in filteredKnowledge" :key="item.id" class="knowledge-row" :class="{ clickable: item.type === 'faq' }" :style="{ '--stagger': `${index * 55}ms` }" @click="item.type === 'faq' && openFaqDetail(item)">
          <div class="row-main">
            <div class="type-icon" :class="toneClass(item.tone)"><component :is="item.icon" :size="20" stroke-width="1.8" /></div>
            <div class="row-title"><h2>{{ item.name }}</h2><p>{{ item.meta }}</p></div>
          </div>
          <div class="row-description">{{ item.description }}</div>
          <div class="row-metric"><strong>{{ item.metric }}</strong><span>{{ item.sync }}</span></div>
          <div class="row-apps"><span v-for="app in item.apps" :key="app" class="app-tag">{{ app }}</span></div>
          <span class="status-badge" :class="statusClass(item.status)"><span class="status-dot" />{{ item.statusLabel }}</span>
          <div class="row-actions" @click.stop><button type="button" title="测试命中率"><Target :size="15" /> <span>测试命中率</span></button><button type="button" title="编辑"><Pencil :size="15" /></button><button type="button" title="删除" class="danger"><Trash2 :size="15" /></button></div>
          <button class="row-more" type="button" aria-label="更多操作"><MoreHorizontal :size="18" /></button>
        </article>
      </TransitionGroup>

      <div v-if="filteredKnowledge.length === 0" class="empty-state"><Search :size="22" /><strong>没有匹配的知识集</strong><span>试试其他关键词或清除筛选条件</span></div>
    </main>

    <main v-else-if="faqDetailOpen" class="faq-detail-page">
      <div class="faq-detail-header">
        <button type="button" class="back-button" @click="closeFaqDetail"><ArrowLeft :size="16" /> 返回知识库</button>
        <div class="faq-detail-heading">
          <div class="faq-detail-icon"><MessageCircleQuestion :size="21" /></div>
          <div><p class="eyebrow">FAQ KNOWLEDGE SET</p><h1>{{ selectedFaq?.name || '常见问答集' }}</h1><p>{{ selectedFaq?.description || '维护客服与售前可复用的标准问答。' }}</p></div>
        </div>
        <div class="faq-detail-actions"><button type="button" class="ghost-button" @click="openFaqPairEditor()"><Plus :size="15" /> 添加问答对</button><button type="button" class="create-button" @click="openFaqPairEditor()"><Upload :size="15" /> 批量导入</button></div>
      </div>

      <div class="faq-stat-grid">
        <div><span>总问答数</span><strong>{{ faqPairs.length }}</strong><small>当前知识集</small></div>
        <div><span>已启用</span><strong>{{ faqPairs.filter(pair => pair.status === 'active').length }}</strong><small>可被 AI 检索</small></div>
        <div><span>草稿</span><strong>{{ faqPairs.filter(pair => pair.status === 'draft').length }}</strong><small>待发布</small></div>
        <div><span>平均命中率</span><strong>94.2%</strong><small>近 30 天</small></div>
      </div>

      <div v-if="faqNotice && !faqPairModalOpen" class="faq-notice">{{ faqNotice }}</div>
      <section class="faq-pair-list">
        <div class="faq-list-head"><div><p class="eyebrow">CURATED RESPONSES</p><h2>问答对配置</h2></div><span>{{ faqPairs.length }} 条记录</span></div>
        <article v-for="(pair, index) in faqPairs" :key="pair.id" class="faq-pair-card" :style="{ '--stagger': `${index * 55}ms` }">
          <div class="faq-pair-index">{{ String(index + 1).padStart(2, '0') }}</div>
          <div class="faq-pair-content"><div class="faq-question"><span>问</span><h3>{{ pair.question }}</h3></div><div class="faq-answer"><span>答</span><p>{{ pair.answer }}</p></div><div class="faq-pair-meta"><span class="hit-count">命中 {{ pair.hits }} 次</span><span class="faq-status" :class="pair.status === 'active' ? 'active' : 'draft'"><i />{{ pair.status === 'active' ? '已启用' : '草稿' }}</span></div></div>
          <div class="faq-pair-actions"><button type="button" title="编辑" @click="openFaqPairEditor(pair)"><Pencil :size="15" /></button><button type="button" :title="pair.status === 'active' ? '停用' : '启用'" @click="toggleFaqPair(pair)"><Archive :size="15" /></button><button type="button" class="danger" title="删除" @click="removeFaqPair(pair)"><Trash2 :size="15" /></button></div>
        </article>
        <div v-if="!faqPairs.length" class="faq-empty"><MessageCircleQuestion :size="24" /><strong>还没有问答对</strong><span>添加第一条问答，让 AI 开始积累可复用的回答。</span><button type="button" class="create-button" @click="openFaqPairEditor()"><Plus :size="15" /> 添加问答对</button></div>
      </section>
    </main>

    <Transition name="dialog-fade"><div v-if="faqSetModalOpen" class="modal-overlay" @click.self="faqSetModalOpen = false"><section class="faq-pair-modal faq-set-modal"><header class="modal-header"><div><p class="eyebrow">NEW FAQ KNOWLEDGE SET</p><h2>新建常见问答集</h2></div><button type="button" class="close-button" aria-label="关闭" @click="faqSetModalOpen = false">×</button></header><form class="faq-pair-form" @submit.prevent="saveFaqSet"><label class="faq-field"><span>知识集名称</span><input v-model="faqSetForm.name" placeholder="例如：门店会员服务问答" autofocus /></label><label class="faq-field"><span>知识集说明 <em>选填</em></span><textarea v-model="faqSetForm.description" rows="4" placeholder="说明这组问答主要服务的业务场景" /></label><p v-if="faqNotice && faqSetModalOpen" class="faq-form-notice">{{ faqNotice }}</p><footer class="faq-form-footer"><button type="button" class="ghost-button" @click="faqSetModalOpen = false">取消</button><button type="submit" class="create-button"><Plus :size="15" /> 创建并添加问答</button></footer></form></section></div></Transition>

    <Transition name="dialog-fade"><div v-if="faqPairModalOpen" class="modal-overlay" @click.self="faqPairModalOpen = false"><section class="faq-pair-modal"><header class="modal-header"><div><p class="eyebrow">FAQ RESPONSE</p><h2>{{ editingFaqPair ? '编辑问答对' : '添加问答对' }}</h2></div><button type="button" class="close-button" aria-label="关闭" @click="faqPairModalOpen = false">×</button></header><form class="faq-pair-form" @submit.prevent="saveFaqPair"><label class="faq-field"><span>用户问题</span><input v-model="faqForm.question" placeholder="例如：你们支持哪些配送方式？" /></label><label class="faq-field"><span>标准答案</span><textarea v-model="faqForm.answer" rows="6" placeholder="输入 AI 应该采用的准确、完整回答" /></label><label class="faq-field"><span>状态</span><select v-model="faqForm.status"><option value="active">已启用</option><option value="draft">草稿</option></select></label><p v-if="faqNotice && faqPairModalOpen" class="faq-form-notice">{{ faqNotice }}</p><footer class="faq-form-footer"><button type="button" class="ghost-button" @click="faqPairModalOpen = false">取消</button><button type="submit" class="create-button">保存问答对</button></footer></form></section></div></Transition>

    <Transition name="dialog-fade"><div v-if="archiveOpen" class="modal-overlay" @click.self="archiveOpen = false"><section class="archive-modal"><header class="modal-header"><div><p class="eyebrow">KNOWLEDGE ARCHIVE</p><h2>归档管理</h2><p class="archive-subtitle">暂时移出工作区的知识集会保留在这里，可随时恢复。</p></div><button type="button" class="close-button" aria-label="关闭" @click="archiveOpen = false">×</button></header><div class="archive-body"><div v-if="archivedKnowledge.length" class="archive-list"><article v-for="item in archivedKnowledge" :key="item.id" class="archive-row"><div class="archive-icon"><Archive :size="17" /></div><div class="archive-copy"><h3>{{ item.name }}</h3><div><span>{{ item.type }}</span><i /> <span>{{ item.metric }}</span><i /> <span>归档于 {{ item.archivedAt }}</span></div><p>{{ item.reason }}</p></div><div class="archive-actions"><button type="button" class="restore-button" @click="restoreArchived(item)">恢复</button><button type="button" class="archive-delete" @click="deleteArchived(item)"><Trash2 :size="14" /></button></div></article></div><div v-else class="archive-empty"><Archive :size="25" /><strong>暂无归档知识集</strong><span>在知识列表中归档的内容会显示在这里。</span></div></div><footer class="archive-footer"><span>归档内容不会被 AI 检索</span><button type="button" class="ghost-button" @click="archiveOpen = false">完成</button></footer></section></div></Transition>



    <footer v-if="!faqDetailOpen" class="knowledge-footer"><span><span class="footer-pulse" />知识上下文服务正常</span><span class="mono">SYNC ENGINE · 99.98% UPTIME</span></footer>
  </div>
</template>

<style scoped>
.knowledge-page{--page-ink:#172033;--page-muted:#7c8799;--page-line:#e7eaf0;--page-soft:#fbf8f7;--page-accent:#c78776;--page-accent-soft:#f9f5f4;max-width:1420px;margin:0 auto;padding:14px clamp(22px,3.2vw,52px) 28px;color:var(--page-ink);font-family:'Noto Sans SC',sans-serif}
.knowledge-header{display:flex;align-items:flex-start;justify-content:space-between;gap:32px;margin-bottom:25px}.header-copy h1{margin:10px 0 7px;font:700 clamp(28px,3vw,38px)/1.15 Manrope,'Noto Sans SC',sans-serif;letter-spacing:-.045em}.header-copy p{margin:0;color:var(--page-muted);font-size:13px}.eyebrow-row{display:flex;align-items:center;gap:8px}.eyebrow{color:#9aa3b2;font:600 10px 'IBM Plex Mono',monospace;letter-spacing:.14em}.eyebrow-dot{width:7px;height:7px;border-radius:50%;background:var(--page-accent);box-shadow:0 0 0 4px #f9f5f4}
.header-tools{display:flex;align-items:center;gap:9px;margin-top:4px}.knowledge-search{display:flex;align-items:center;gap:9px;width:min(290px,28vw);height:38px;padding:0 11px;border:1px solid var(--page-line);border-radius:9px;background:#fff;color:#9aa3b2;transition:border-color .18s ease,box-shadow .18s ease}.knowledge-search:focus-within{border-color:#dccac5;box-shadow:0 0 0 3px #f9f6f6}.knowledge-search input{width:100%;min-width:0;border:0;outline:0;color:var(--page-ink);background:transparent;font-size:12px}.knowledge-search input::placeholder{color:#a3adbb}.knowledge-search kbd{padding:3px 5px;border:1px solid var(--page-line);border-radius:5px;color:#a5adbb;background:#fafbfc;font:10px 'IBM Plex Mono',monospace;white-space:nowrap}.ghost-button,.view-button{display:inline-flex;align-items:center;gap:7px;height:38px;padding:0 12px;border:1px solid var(--page-line);border-radius:9px;color:#566174;background:#fff;font-size:12px;transition:all .18s ease}.ghost-button:hover,.ghost-button.selected,.view-button:hover{border-color:#e6d8d4;color:var(--page-accent);background:#fdfcfc}.filter-count{display:grid;place-items:center;width:16px;height:16px;border-radius:50%;color:#fff;background:var(--page-accent);font-size:10px}.create-button{display:inline-flex;align-items:center;gap:7px;height:38px;padding:0 13px;border:1px solid #bd725e;border-radius:9px;color:#fff;background:var(--page-accent);font-size:12px;font-weight:600;box-shadow:0 7px 16px rgba(187,142,130,.17);transition:all .18s ease}.create-button:hover{background:#bd725e;transform:translateY(-1px);box-shadow:0 10px 20px rgba(187,142,130,.22)}.create-button .rotate{transform:rotate(180deg)}
.filter-wrap,.create-wrap{position:relative}.popover{position:absolute;z-index:10;top:calc(100% + 8px);right:0;border:1px solid var(--page-line);border-radius:11px;background:#fff;box-shadow:0 16px 35px rgba(60,38,32,.13);animation:popover-in .16s ease-out}.filter-popover{width:150px;padding:7px}.filter-popover p{margin:5px 8px 6px;color:#a0a8b6;font:600 10px 'IBM Plex Mono',monospace;letter-spacing:.08em}.filter-popover button{display:flex;align-items:center;justify-content:space-between;width:100%;padding:8px;border:0;border-radius:7px;color:#667184;background:transparent;font-size:12px;text-align:left}.filter-popover button:hover,.filter-popover button.checked{color:var(--page-accent);background:#fbf9f8}.create-popover{width:250px;padding:6px}.create-popover button{display:flex;align-items:flex-start;gap:10px;width:100%;padding:10px;border:0;border-radius:8px;color:var(--page-accent);background:transparent;text-align:left}.create-popover button:hover{background:#fcfaf9}.create-popover button svg{margin-top:2px}.create-popover span{display:grid;gap:3px}.create-popover strong{color:var(--page-ink);font-size:12px;font-weight:600}.create-popover small{color:#9aa3b2;font-size:10px}@keyframes popover-in{from{opacity:0;transform:translateY(-4px)}to{opacity:1;transform:none}}
.knowledge-tabs{display:flex;gap:25px;border-bottom:1px solid var(--page-line);margin-bottom:18px}.knowledge-tab{position:relative;display:inline-flex;align-items:center;gap:7px;height:43px;padding:0 2px;border:0;color:#8a94a5;background:transparent;font-size:12px;cursor:pointer}.knowledge-tab:hover{color:#4c586b}.knowledge-tab.active{color:var(--page-ink);font-weight:700}.tab-suffix{color:#aeb5c0;font:10px 'IBM Plex Mono',monospace}.tab-count{min-width:18px;padding:2px 5px;border-radius:99px;color:#9ba4b1;background:#f6f3f2;font:500 10px 'IBM Plex Mono',monospace;text-align:center}.knowledge-tab.active .tab-count{color:var(--page-accent);background:var(--page-accent-soft)}.tab-indicator{position:absolute;right:0;bottom:-1px;left:0;height:2px;border-radius:3px 3px 0 0;background:var(--page-accent);animation:tab-in .22s ease-out}@keyframes tab-in{from{transform:scaleX(.25);opacity:.3}to{transform:scaleX(1);opacity:1}}
.list-toolbar{display:flex;align-items:center;justify-content:space-between;margin:0 3px 10px;color:#9aa3b2;font-size:11px}.list-toolbar strong{color:#4f5b6e;font-weight:600}.list-toolbar>div span{padding:0 5px;color:#c7cdd6}.view-button{height:30px;padding:0 9px;border-color:transparent;color:#8b95a4;background:transparent;font-size:11px}.knowledge-list{overflow:hidden;border:1px solid var(--page-line);border-radius:12px;background:#fff;box-shadow:0 7px 20px rgba(34,44,68,.035)}.knowledge-row{position:relative;display:grid;grid-template-columns:minmax(255px,1.35fr) minmax(180px,1fr) 145px minmax(145px,.8fr) 95px 118px 32px;align-items:center;gap:18px;min-height:84px;padding:14px 17px;border-top:1px solid var(--page-line);transition:background .18s ease}.knowledge-row:first-child{border-top:0}.knowledge-row:hover{background:#fdfcfc}.row-main{display:flex;align-items:center;gap:12px;min-width:0}.type-icon{display:grid;place-items:center;width:40px;height:40px;flex:0 0 auto;border-radius:11px}.tone-blue{color:#4d74d8;background:#edf3ff}.tone-cinnabar{color:#bf7663;background:#f9f6f5}.tone-orange{color:#d78338;background:#fff3e7}.row-title{min-width:0}.row-title h2{overflow:hidden;margin:0 0 4px;color:#263247;font-size:13px;font-weight:650;text-overflow:ellipsis;white-space:nowrap}.row-title p{margin:0;overflow:hidden;color:#a1aab8;font:10px 'IBM Plex Mono',monospace;text-overflow:ellipsis;white-space:nowrap}.row-description{overflow:hidden;color:#7e8999;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.row-metric{display:grid;gap:4px}.row-metric strong{color:#465267;font-size:12px;font-weight:600}.row-metric span{color:#a6afbb;font-size:10px}.row-apps{display:flex;flex-wrap:wrap;gap:4px}.app-tag{padding:4px 7px;border:1px solid #f2eae9;border-radius:5px;color:#66728a;background:#fdfcfb;font-size:10px;white-space:nowrap}.status-badge{display:inline-flex;align-items:center;justify-content:center;gap:5px;width:max-content;padding:4px 8px;border-radius:999px;font-size:10px;font-weight:600;white-space:nowrap}.status-dot{width:6px;height:6px;border-radius:50%;background:currentColor}.status-active{color:#1d9365;background:#ecfaf3}.status-syncing{color:#b17818;background:#fff8e8}.status-draft{color:#778397;background:#f1f3f6}.row-actions{display:flex;align-items:center;justify-content:flex-end;gap:4px;opacity:0;transform:translateX(5px);transition:opacity .18s ease,transform .18s ease}.knowledge-row:hover .row-actions{opacity:1;transform:none}.row-actions button,.row-more{display:inline-flex;align-items:center;justify-content:center;gap:5px;width:29px;height:29px;padding:0;border:1px solid transparent;border-radius:7px;color:#7e899a;background:transparent;font-size:10px}.row-actions button:first-child{width:auto;padding:0 7px}.row-actions button:hover,.row-more:hover{border-color:#f0e8e6;color:var(--page-accent);background:#fcfafa}.row-actions .danger:hover{border-color:#f5d9df;color:#c95769;background:#fff6f7}.row-more{display:none}.knowledge-list-enter-active{animation:row-in .4s cubic-bezier(.22,1,.36,1) both;animation-delay:var(--stagger)}.knowledge-list-leave-active{transition:opacity .16s ease,transform .16s ease}.knowledge-list-enter-from,.knowledge-list-leave-to{opacity:0;transform:translateY(7px)}@keyframes row-in{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:none}}
.empty-state{display:grid;place-items:center;gap:8px;min-height:240px;border:1px dashed var(--page-line);border-radius:12px;color:#9ba4b1;background:#fff}.empty-state strong{color:#657184;font-size:13px}.empty-state span{font-size:11px}.knowledge-footer{display:flex;align-items:center;justify-content:space-between;margin-top:16px;color:#9aa3b2;font-size:10px}.knowledge-footer>span:first-child{display:inline-flex;align-items:center;gap:7px}.footer-pulse{width:6px;height:6px;border-radius:50%;background:#36b981;box-shadow:0 0 0 4px #e8f8f0}.mono{font:10px 'IBM Plex Mono',monospace;letter-spacing:.04em}
@media(max-width:1180px){.knowledge-row{grid-template-columns:minmax(230px,1.3fr) minmax(150px,1fr) 125px minmax(120px,.8fr) 90px 32px}.row-actions{display:none}.row-more{display:inline-flex}.knowledge-search{width:230px}.header-tools{gap:7px}}
@media(max-width:860px){.knowledge-page{padding:17px 17px 26px}.knowledge-header{display:grid;gap:18px}.header-tools{flex-wrap:wrap}.knowledge-search{width:100%;order:3}.knowledge-tabs{gap:17px;overflow-x:auto}.knowledge-tab{white-space:nowrap}.knowledge-list{overflow:visible;border:0;background:transparent;box-shadow:none}.knowledge-row{grid-template-columns:1fr auto;gap:12px;padding:15px 0;border:1px solid var(--page-line)!important;border-radius:11px;background:#fff;margin-bottom:8px;box-shadow:0 4px 12px rgba(34,44,68,.025)}.row-description{grid-column:1/-1;order:3;padding-left:52px}.row-metric{grid-column:1;order:4;padding-left:52px}.row-apps{grid-column:1/-1;order:5;padding-left:52px}.status-badge{grid-column:2;grid-row:1;order:2}.row-more{grid-column:2;grid-row:4;order:6}.knowledge-footer{display:grid;gap:8px}.knowledge-footer .mono{font-size:9px}}
@media(max-width:520px){.header-tools{display:grid;grid-template-columns:1fr auto;align-items:center}.knowledge-search{grid-column:1/-1}.create-button{padding-inline:11px}.ghost-button{padding-inline:10px}.knowledge-tabs{margin-right:-17px;padding-right:17px}.tab-suffix{display:none}.row-title h2{font-size:12px}.row-description{font-size:10px}.row-actions{display:none}}

.faq-feature-card{display:flex;align-items:center;gap:18px;margin:0 0 18px;padding:18px 20px;border:1px solid #efe6e4;border-radius:13px;background:linear-gradient(100deg,#fdfcfc 0%,#fff 66%);box-shadow:0 7px 20px rgba(34,44,68,.035)}.faq-feature-mark{display:grid;place-items:center;width:46px;height:46px;flex:0 0 auto;border-radius:13px;color:#c4806e;background:#f9f6f5}.faq-feature-copy{min-width:0;flex:1}.faq-feature-copy .eyebrow{margin:0 0 4px;color:#bf978c}.faq-feature-copy h2{margin:0 0 5px;color:#27314a;font-size:16px;letter-spacing:-.02em}.faq-feature-copy>p:not(.eyebrow){margin:0;color:#7c8799;font-size:11px;line-height:1.6}.faq-feature-meta{display:flex;align-items:center;gap:9px;margin-top:10px;color:#717b91;font-size:10px}.faq-feature-meta i{width:3px;height:3px;border-radius:50%;background:#c2c7d2}.faq-feature-action{display:inline-flex;align-items:center;gap:7px;padding:9px 11px;border:1px solid #ede3e0;border-radius:8px;color:#bb6e5a;background:#fff;font-size:11px;font-weight:600;white-space:nowrap;transition:.18s}.faq-feature-action:hover{border-color:#e0bcb3;background:#fcfbfb;transform:translateY(-1px)}
.faq-detail-page{padding-bottom:18px}.modal-overlay{position:fixed;inset:0;z-index:1000;display:grid;place-items:center;padding:20px;background:rgba(24,42,42,.42);backdrop-filter:blur(5px)}.faq-detail-header{display:grid;grid-template-columns:1fr auto;gap:18px;margin-bottom:20px}.back-button{grid-column:1/-1;display:inline-flex;align-items:center;gap:6px;width:max-content;padding:0;border:0;color:#7a8495;background:transparent;font-size:11px}.back-button:hover{color:var(--page-accent)}.faq-detail-heading{display:flex;align-items:center;gap:13px}.faq-detail-icon{display:grid;place-items:center;width:42px;height:42px;border-radius:11px;color:#bf7663;background:#f9f6f5}.faq-detail-heading .eyebrow{margin:0 0 4px}.faq-detail-heading h1{margin:0 0 4px;color:#263247;font-size:22px;letter-spacing:-.035em}.faq-detail-heading p:not(.eyebrow){margin:0;color:#7f8998;font-size:11px}.faq-detail-actions{display:flex;align-items:center;gap:8px}.faq-detail-actions .ghost-button,.faq-detail-actions .create-button{height:34px}.faq-stat-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:18px}.faq-stat-grid>div{display:grid;gap:4px;padding:13px 15px;border:1px solid var(--page-line);border-radius:10px;background:#fff}.faq-stat-grid span{color:#8993a2;font-size:10px}.faq-stat-grid strong{color:#354158;font:700 21px Manrope,'Noto Sans SC',sans-serif;letter-spacing:-.03em}.faq-stat-grid small{color:#a8afba;font-size:10px}.faq-notice,.faq-form-notice{padding:10px 12px;border-radius:8px;color:#477566;background:#eef9f4;font-size:11px}.faq-notice{margin-bottom:12px}.faq-list-head{display:flex;align-items:flex-end;justify-content:space-between;margin:0 2px 10px}.faq-list-head .eyebrow{margin-bottom:4px}.faq-list-head h2{margin:0;color:#364158;font-size:15px}.faq-list-head>span{color:#9aa3b2;font-size:10px}.faq-pair-list{padding:17px;border:1px solid var(--page-line);border-radius:12px;background:#fff;box-shadow:0 7px 20px rgba(34,44,68,.035)}.faq-pair-card{display:grid;grid-template-columns:34px 1fr auto;gap:13px;padding:16px 0;border-top:1px solid var(--page-line);animation:row-in .4s cubic-bezier(.22,1,.36,1) both;animation-delay:var(--stagger)}.faq-pair-card:first-of-type{border-top:0}.faq-pair-index{color:#b0b6c2;font:600 11px 'IBM Plex Mono',monospace;padding-top:3px}.faq-pair-content{min-width:0}.faq-question,.faq-answer{display:flex;align-items:flex-start;gap:9px}.faq-question>span,.faq-answer>span{display:grid;place-items:center;width:20px;height:20px;flex:0 0 auto;border-radius:5px;font-size:10px;font-weight:700}.faq-question>span{color:#c37e6c;background:#f9f6f5}.faq-answer>span{color:#238c70;background:#eaf8f2}.faq-question h3{margin:0;color:#334057;font-size:13px;font-weight:650;line-height:1.5}.faq-answer{margin-top:8px}.faq-answer p{margin:0;color:#788497;font-size:11px;line-height:1.65}.faq-pair-meta{display:flex;align-items:center;gap:10px;margin-top:10px;padding-left:29px}.hit-count{color:#a2abb8;font-size:10px}.faq-status{display:inline-flex;align-items:center;gap:5px;padding:3px 7px;border-radius:99px;font-size:10px;font-weight:600}.faq-status i{width:5px;height:5px;border-radius:50%;background:currentColor}.faq-status.active{color:#1d9365;background:#ecfaf3}.faq-status.draft{color:#778397;background:#f1f3f6}.faq-pair-actions{display:flex;align-items:center;gap:3px;opacity:0;transition:opacity .18s}.faq-pair-card:hover .faq-pair-actions{opacity:1}.faq-pair-actions button{display:grid;place-items:center;width:29px;height:29px;border:1px solid transparent;border-radius:7px;color:#7e899a;background:transparent}.faq-pair-actions button:hover{border-color:#f0e8e6;color:var(--page-accent);background:#fcfafa}.faq-pair-actions .danger:hover{border-color:#f5d9df;color:#c95769;background:#fff6f7}.faq-empty{display:grid;place-items:center;gap:8px;min-height:220px;color:#9ba4b1;text-align:center}.faq-empty strong{color:#657184;font-size:13px}.faq-empty span{font-size:11px}.faq-empty .create-button{margin-top:6px}.dialog-fade-enter-active,.dialog-fade-leave-active{transition:opacity .18s ease}.dialog-fade-enter-active .faq-pair-modal,.dialog-fade-leave-active .faq-pair-modal{transition:transform .32s cubic-bezier(.22,1,.36,1),opacity .22s ease}.dialog-fade-enter-from .faq-pair-modal{opacity:0;transform:translateY(42px)}.dialog-fade-leave-to .faq-pair-modal{opacity:0;transform:translateY(24px)}.faq-pair-modal{width:min(560px,100%);border:1px solid #dbe1eb;border-radius:15px;background:#fff;box-shadow:0 22px 60px rgba(60,38,32,.2)}.faq-pair-modal .modal-header{display:flex;align-items:center;justify-content:space-between;padding:19px 22px 16px;border-bottom:1px solid #e7eaf0;background:#fff}.faq-pair-modal .modal-header h2{margin:5px 0 0;font-size:19px}.close-button{display:grid;place-items:center;width:30px;height:30px;border:0;border-radius:8px;color:#748092;background:transparent;font-size:24px;line-height:1;cursor:pointer}.close-button:hover{background:#f1f3f8;color:#354158}.faq-pair-form{display:grid;gap:15px;padding:20px 22px 22px}.faq-field{display:grid;gap:7px}.faq-field>span{color:#4f5b6e;font-size:12px;font-weight:600}.faq-field input,.faq-field textarea,.faq-field select{width:100%;box-sizing:border-box;padding:10px 11px;border:1px solid #dfe3eb;border-radius:8px;outline:0;color:#273247;background:#fff;font:12px/1.6 'Noto Sans SC',sans-serif;transition:border-color .18s,box-shadow .18s}.faq-field textarea{resize:vertical;min-height:110px}.faq-field input:focus,.faq-field textarea:focus,.faq-field select:focus{border-color:#dccac5;box-shadow:0 0 0 3px #f9f6f6}.faq-form-footer{display:flex;justify-content:flex-end;gap:8px;padding-top:2px}.faq-form-footer .ghost-button,.faq-form-footer .create-button{height:34px}
:global([data-theme='dark'] .faq-feature-card),:global([data-theme='dark'] .faq-stat-grid>div),:global([data-theme='dark'] .faq-pair-list),:global([data-theme='dark'] .faq-pair-modal){border-color:#2b4149;background:#141f29;box-shadow:0 8px 24px rgba(0,0,0,.18)}:global([data-theme='dark'] .faq-feature-card){background:linear-gradient(100deg,#15222f,#141f29)}:global([data-theme='dark'] .faq-feature-copy h2),:global([data-theme='dark'] .faq-detail-heading h1),:global([data-theme='dark'] .faq-list-head h2),:global([data-theme='dark'] .faq-question h3),:global([data-theme='dark'] .faq-stat-grid strong),:global([data-theme='dark'] .faq-field>span){color:#dce9e7}:global([data-theme='dark'] .faq-feature-copy>p:not(.eyebrow)),:global([data-theme='dark'] .faq-detail-heading p:not(.eyebrow)),:global([data-theme='dark'] .faq-answer p){color:#91aaa7}:global([data-theme='dark'] .faq-feature-mark),:global([data-theme='dark'] .faq-question>span){background:#243447;color:#dfbbb1}:global([data-theme='dark'] .faq-feature-action){border-color:#38505a;color:#dfbbb2;background:#182630}:global([data-theme='dark'] .faq-pair-card){border-color:#2b4149}:global([data-theme='dark'] .faq-field input),:global([data-theme='dark'] .faq-field textarea),:global([data-theme='dark'] .faq-field select){color:#e0ecea;background:#111b24;border-color:#38505a}:global([data-theme='dark'] .faq-field input:focus),:global([data-theme='dark'] .faq-field textarea:focus),:global([data-theme='dark'] .faq-field select:focus){border-color:#54b9aa;box-shadow:0 0 0 3px rgba(42,157,143,.18)}
@media(max-width:760px){.faq-feature-card{align-items:flex-start;flex-wrap:wrap}.faq-feature-action{margin-left:64px}.faq-detail-header{grid-template-columns:1fr}.faq-detail-actions{justify-content:flex-start}.faq-stat-grid{grid-template-columns:repeat(2,1fr)}.faq-pair-card{grid-template-columns:26px 1fr}.faq-pair-actions{grid-column:2;justify-content:flex-end;opacity:1}.faq-pair-meta{padding-left:0}}
@media(max-width:520px){.faq-feature-card{padding:15px}.faq-feature-copy>p:not(.eyebrow){font-size:10px}.faq-feature-meta{flex-wrap:wrap}.faq-feature-action{margin-left:0}.faq-detail-heading h1{font-size:19px}.faq-stat-grid strong{font-size:18px}.faq-pair-list{padding:13px}.faq-pair-card{gap:8px}}
.knowledge-row.clickable{cursor:pointer}.knowledge-row.clickable:hover{background:#fdfcfc}
:global([data-theme='dark'] .archive-modal){background:#141f29;border-color:#2b4149}.archive-modal{width:min(700px,100%);overflow:hidden;border:1px solid #dbe1eb;border-radius:15px;background:#fff;box-shadow:0 22px 60px rgba(60,38,32,.2)}.archive-modal .modal-header{display:flex;align-items:flex-start;justify-content:space-between;padding:20px 22px 17px;border-bottom:1px solid #e7eaf0;background:#fff}.archive-modal .modal-header h2{margin:5px 0 5px;font-size:20px}.archive-subtitle{margin:0;color:#8b95a5;font-size:11px}.archive-body{max-height:52vh;overflow:auto;padding:5px 22px}.archive-row{display:grid;grid-template-columns:34px 1fr auto;align-items:center;gap:12px;padding:15px 0;border-bottom:1px solid var(--page-line)}.archive-row:last-child{border-bottom:0}.archive-icon{display:grid;place-items:center;width:32px;height:32px;border-radius:8px;color:#818b9d;background:#f1f3f7}.archive-copy{min-width:0}.archive-copy h3{margin:0 0 5px;color:#344157;font-size:13px;font-weight:700}.archive-copy>div{display:flex;align-items:center;gap:7px;color:#9aa3b2;font-size:10px}.archive-copy>div i{width:3px;height:3px;border-radius:50%;background:#c5cad2}.archive-copy p{margin:6px 0 0;color:#8993a2;font-size:10px}.archive-actions{display:flex;align-items:center;gap:5px}.restore-button{height:29px;padding:0 10px;border:1px solid #cde8de;border-radius:7px;color:#268063;background:#f2fbf7;font-size:11px;font-weight:600}.restore-button:hover{background:#e7f7f0}.archive-delete{display:grid;place-items:center;width:29px;height:29px;border:1px solid transparent;border-radius:7px;color:#a0a8b5;background:transparent}.archive-delete:hover{border-color:#f2d5db;color:#c95769;background:#fff6f7}.archive-empty{display:grid;place-items:center;gap:8px;min-height:210px;color:#a0a8b5;text-align:center}.archive-empty strong{color:#637084;font-size:13px}.archive-empty span{font-size:10px}.archive-footer{display:flex;align-items:center;justify-content:space-between;padding:13px 22px;border-top:1px solid #e7eaf0;color:#9aa3b2;font-size:10px}.archive-footer .ghost-button{height:31px;padding:0 11px}
:global([data-theme='dark'] .archive-modal .modal-header){background:#121c26;border-color:#2a3a43}:global([data-theme='dark'] .archive-subtitle),:global([data-theme='dark'] .archive-copy>div),:global([data-theme='dark'] .archive-copy p),:global([data-theme='dark'] .archive-footer){color:#91aaa7}:global([data-theme='dark'] .archive-copy h3),:global([data-theme='dark'] .archive-empty strong){color:#dce9e7}:global([data-theme='dark'] .archive-row),:global([data-theme='dark'] .archive-footer){border-color:#2b4149}:global([data-theme='dark'] .archive-icon){color:#aeb8c5;background:#24313c}:global([data-theme='dark'] .restore-button){color:#7de0cf;background:#14352f;border-color:#3d8075}
.archive-count{display:inline-grid;place-items:center;min-width:16px;height:16px;padding:0 4px;border-radius:99px;color:#7e8998;background:#f1f3f6;font-size:9px;font-weight:650}
:global([data-theme='dark'] .modal-overlay){background:rgba(2,7,12,.68)}

/* Typography system: crisp Chinese text with a geometric display face */
.knowledge-page{font-family:'Segoe UI Variable','Segoe UI','PingFang SC','Microsoft YaHei UI','Microsoft YaHei','Noto Sans SC',sans-serif;-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility;letter-spacing:.005em}
.knowledge-page h1,.knowledge-page h2,.knowledge-page h3,.knowledge-page strong,.knowledge-page button{font-family:'Avenir Next','Segoe UI Variable','Segoe UI','PingFang SC','Microsoft YaHei UI','Microsoft YaHei','Noto Sans SC',sans-serif}
.knowledge-page h1,.knowledge-page h2,.knowledge-page h3{font-feature-settings:'kern' 1,'liga' 1}
.header-copy h1{font-size:clamp(30px,3vw,40px);font-weight:780;line-height:1.18;letter-spacing:-.045em;color:#1b2538}
.header-copy p{font-size:13px;font-weight:500;line-height:1.7;color:#647188}
.knowledge-search input,.ghost-button,.create-button,.view-button,.knowledge-tab,.row-description,.row-metric,.row-apps,.status-badge,.empty-state,.faq-feature-copy>p,.faq-feature-meta,.faq-detail-heading p,.faq-stat-grid span,.faq-stat-grid small,.faq-pair-card,.faq-field,.faq-form-footer{font-family:'Segoe UI Variable','Segoe UI','PingFang SC','Microsoft YaHei UI','Microsoft YaHei','Noto Sans SC',sans-serif}
.knowledge-search input{font-size:12px;font-weight:500;letter-spacing:.01em}.knowledge-search input::placeholder{color:#8995a8;opacity:1}
.knowledge-tab{font-size:13px;font-weight:550;letter-spacing:.01em}.knowledge-tab.active{font-weight:720}.tab-suffix,.tab-count,.row-title p,.mono{font-family:'Cascadia Mono','SFMono-Regular','Consolas','IBM Plex Mono',monospace;font-variant-numeric:tabular-nums}
.tab-suffix{font-size:10px}.tab-count{font-size:10px;font-weight:650}.list-toolbar{font-size:12px;font-weight:500}.list-toolbar strong{font-weight:750;color:#36445b}
.row-title h2{font-size:14px;font-weight:720;letter-spacing:-.012em;color:#253149}.row-title p{font-size:10px;letter-spacing:.015em;color:#8a96a8}.row-description{font-size:12px;font-weight:500;line-height:1.5;color:#718096}.row-metric strong{font-size:13px;font-weight:720;letter-spacing:-.01em;color:#3d4b62}.row-metric span{font-size:10px;color:#8e99a9}.app-tag{font-size:10px;font-weight:550}.status-badge{font-size:10px;font-weight:700;letter-spacing:.01em}
.faq-feature-copy h2{font-size:18px;font-weight:760;letter-spacing:-.025em}.faq-feature-copy>p:not(.eyebrow){font-size:12px;line-height:1.7;color:#6e7b91}.faq-feature-meta{font-size:11px;font-weight:550;color:#68758b}.faq-feature-action{font-size:12px;font-weight:700}
.faq-detail-heading h1{font-size:24px;font-weight:760}.faq-detail-heading p:not(.eyebrow){font-size:12px;line-height:1.6}.faq-list-head h2{font-size:17px;font-weight:720}.faq-list-head>span{font-size:11px}.faq-stat-grid span{font-size:11px;font-weight:550}.faq-stat-grid strong{font-size:23px;font-weight:780}.faq-stat-grid small{font-size:10px}.faq-question h3{font-size:14px;font-weight:700;line-height:1.55}.faq-answer p{font-size:12px;line-height:1.75}.hit-count,.faq-status{font-size:10px}.faq-field>span{font-size:13px;font-weight:650}.faq-field input,.faq-field textarea,.faq-field select{font-family:inherit;font-size:13px;line-height:1.65}
.knowledge-footer{font-size:11px}.knowledge-footer .mono{font-size:10px}
:global([data-theme='dark'] .header-copy h1),:global([data-theme='dark'] .row-title h2),:global([data-theme='dark'] .faq-feature-copy h2),:global([data-theme='dark'] .faq-detail-heading h1),:global([data-theme='dark'] .faq-question h3){color:#e7efee}
:global([data-theme='dark'] .header-copy p),:global([data-theme='dark'] .row-description),:global([data-theme='dark'] .faq-feature-copy>p:not(.eyebrow)),:global([data-theme='dark'] .faq-answer p){color:#a6b8b6}
:global(.sidebar),:global(.topbar),:global(.topbar button),:global(.page-scroll){font-family:'Segoe UI Variable','Segoe UI','PingFang SC','Microsoft YaHei UI','Microsoft YaHei','Noto Sans SC',sans-serif;-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility}
:global(.brand-name){font-family:'Avenir Next','Segoe UI Variable','Segoe UI','PingFang SC','Microsoft YaHei UI',sans-serif;font-weight:780;letter-spacing:-.025em}
:global(.nav-group-label),:global(.brand-caption),:global(.topbar .mono){font-family:'Cascadia Mono','SFMono-Regular','Consolas','IBM Plex Mono',monospace}
:global(.nav-item){font-size:13px;font-weight:550;letter-spacing:.005em}:global(.nav-item.active){font-weight:700}:global(.breadcrumb){font-size:12px;font-weight:550}
:global([data-theme='dark'] .knowledge-search),:global([data-theme='dark'] .ghost-button),:global([data-theme='dark'] .view-button),:global([data-theme='dark'] .knowledge-list),:global([data-theme='dark'] .knowledge-row),:global([data-theme='dark'] .empty-state){border-color:#2b3742;background:#151c24;box-shadow:none}:global([data-theme='dark'] .knowledge-search input){color:#e7edf2}:global([data-theme='dark'] .knowledge-search input::placeholder){color:#7f8b98}:global([data-theme='dark'] .knowledge-search kbd){border-color:#34414c;color:#86929f;background:#1b242d}:global([data-theme='dark'] .knowledge-tab){color:#8d99a6}:global([data-theme='dark'] .knowledge-tab.active){color:#e7efee}:global([data-theme='dark'] .tab-count){color:#9aa6b2;background:#222b34}:global([data-theme='dark'] .knowledge-list){overflow:hidden}:global([data-theme='dark'] .knowledge-row){border-top-color:#2b3742}:global([data-theme='dark'] .knowledge-row:hover){background:#1b242d}:global([data-theme='dark'] .row-title p),:global([data-theme='dark'] .row-metric span),:global([data-theme='dark'] .list-toolbar),:global([data-theme='dark'] .knowledge-footer){color:#8d99a6}:global([data-theme='dark'] .row-description){color:#a7b2bd}:global([data-theme='dark'] .row-metric strong){color:#d4dde4}:global([data-theme='dark'] .app-tag){border-color:#33404b;color:#aab5bf;background:#1c252e}:global([data-theme='dark'] .status-draft){color:#a1acb8;background:#29323b}:global([data-theme='dark'] .row-actions button:hover),:global([data-theme='dark'] .row-more:hover){border-color:#3b4c59;color:#e0bcb3;background:#222c36}:global([data-theme='dark'] .type-icon.tone-blue){color:#9bb9ff;background:#24334b}:global([data-theme='dark'] .type-icon.tone-cinnabar){color:#e1bfb6;background:#422c26}
:global([data-theme='dark'] .popover){border-color:#33404d;background:#17212b;box-shadow:0 20px 42px rgba(0,0,0,.34)}:global([data-theme='dark'] .filter-popover p){color:#8795a6}:global([data-theme='dark'] .filter-popover button){color:#b5c0cc}:global([data-theme='dark'] .filter-popover button:hover),:global([data-theme='dark'] .filter-popover button.checked){color:#e1d1cd;background:#3e2923}:global([data-theme='dark'] .create-popover button){color:#deccc7}:global([data-theme='dark'] .create-popover button:hover){background:#3b2924}:global([data-theme='dark'] .create-popover strong){color:#e1e8ee}:global([data-theme='dark'] .create-popover small){color:#93a0ae}
:global([data-theme='dark'] .faq-pair-modal .modal-header){border-color:#2b3a46;background:#17212b}:global([data-theme='dark'] .faq-pair-modal .modal-header h2){color:#e7edf2}:global([data-theme='dark'] .faq-pair-modal .modal-header .eyebrow){color:#9da7b6}:global([data-theme='dark'] .close-button){color:#aab5c0}:global([data-theme='dark'] .close-button:hover){color:#edf2f5;background:#26313b}:global([data-theme='dark'] .faq-form-footer){border-color:#2b3a46}:global([data-theme='dark'] .faq-form-footer .ghost-button){color:#aeb9c4;background:#1c2731;border-color:#34424e}:global([data-theme='dark'] .faq-form-notice){color:#9ad8bd;background:#18352c}
</style>
