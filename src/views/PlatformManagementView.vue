<script setup>
import AppSelect from '../components/AppSelect.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Dialog, DialogPanel, DialogTitle, DialogDescription } from '@headlessui/vue'
import { ArrowDownToLine, Check, ChevronLeft, ChevronRight, ClipboardList, Eye, FileText, LockKeyhole, RefreshCw, Search, ShieldCheck, Store, X } from 'lucide-vue-next'
import { platformManagementApi as api } from '../services/platformManagement'
import { auth } from '../stores/auth'
import { loadPlatformAccess, platform } from '../stores/platform'
import { auditActions, informationCategories, managementDate, managementStatuses } from '../domain/platformManagement'

const route = useRoute()
const router = useRouter()
const section = computed(() => route.name === 'admin-identities' ? 'identities' : route.name === 'admin-merchants' || route.query.section === 'merchants' ? 'merchants' : 'information')
const isIdentity = computed(() => section.value === 'identities')
const title = computed(() => isIdentity.value ? '用户实名' : section.value === 'merchants' ? '商家管理' : '信息管理')
const subtitle = computed(() => isIdentity.value ? '实名由阿里云当场核验，这里查看核验记录。' : section.value === 'merchants' ? '维护经营主体档案，审核入驻申请与经营状态。' : '审核商家提交的信息，通过后公开展示，处理信息上下线。')
const statusOptions = computed(() => section.value === 'information' ? ['PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'OFFLINE', 'EXPIRED'] : section.value === 'merchants' ? ['PENDING_REVIEW', 'APPROVED', 'REJECTED', 'SUSPENDED'] : ['PENDING_REVIEW', 'VERIFIED', 'REJECTED'])
const records = ref([])
const total = ref(null)
const totalPages = ref(0)
const page = ref(0)
const size = ref(20)
const keyword = ref('')
const status = ref('')
const category = ref('')
const loading = ref(false)
const error = ref('')
const success = ref('')
const summary = ref(null)
const summaryError = ref('')
const selected = ref(null)
const trail = ref([])
const trailLoading = ref(false)
const trailError = ref('')
const note = ref('')
const actionError = ref('')
const busy = ref(false)
const revealed = ref(null)
let version = 0
let detailVersion = 0
let revealTimer
const targetType = computed(() => ({ information: 'INFORMATION', merchants: 'MERCHANT', identities: 'IDENTITY' })[section.value])
const selectionTitle = computed(() => selected.value?.title || selected.value?.merchantName || selected.value?.userName || '实名申请')
const stats = computed(() => isIdentity.value ? [
  { label: '已入驻商家', value: summary.value?.approvedMerchants, helper: '入驻审核通过的商家', icon: Store },
  { label: '待审入驻', value: summary.value?.pendingMerchants, helper: '等待审核的经营主体', icon: ClipboardList },
] : section.value === 'information' ? [
  { label: '待审核信息', value: summary.value?.pendingInformation, helper: '审核通过后才会公开展示', icon: ClipboardList },
  { label: '在线信息', value: summary.value?.publishedInformation, helper: '已发布且在有效期内', icon: FileText },
  { label: '已下线信息', value: summary.value?.offlineInformation, helper: '公开页面已停止展示', icon: ArrowDownToLine },
] : [
  { label: '在线信息', value: summary.value?.publishedInformation, helper: '已发布且在有效期内', icon: FileText },
  { label: '已入驻商家', value: summary.value?.approvedMerchants, helper: '入驻审核通过的商家', icon: Store },
  { label: '待审入驻', value: summary.value?.pendingMerchants, helper: '等待审核的经营主体', icon: ClipboardList },
])

async function load() {
  const attempt = ++version
  loading.value = true
  error.value = ''
  summaryError.value = ''
  const allowed = await loadPlatformAccess()
  if (attempt !== version) return
  if (!allowed) {
    error.value = platform.error || '当前账号没有平台运营权限，请联系平台配置运营账号。'
    loading.value = false
    return
  }
  const [listResult, summaryResult] = await Promise.allSettled([
    api[section.value]({ page: page.value, size: size.value, keyword: keyword.value.trim(), status: status.value, ...(section.value === 'information' ? { category: category.value } : {}) }),
    api.summary(),
  ])
  if (attempt !== version) return
  if (listResult.status === 'fulfilled') {
    records.value = listResult.value.items
    total.value = listResult.value.total
    totalPages.value = listResult.value.totalPages
  } else {
    records.value = []
    total.value = null
    error.value = listResult.reason?.message || '数据读取失败，请重试。'
  }
  if (summaryResult.status === 'fulfilled') summary.value = summaryResult.value
  else { summary.value = null; summaryError.value = '统计暂时无法读取。' }
  loading.value = false
}

function search() { page.value = 0; load() }
function movePage(next) { page.value = next; closeDetail(); load() }
function changeSection(value) { router.replace({ name: value === 'merchants' ? 'admin-merchants' : 'admin-information' }) }
function closeDetail() {
  if (busy.value) return
  detailVersion++
  clearTimeout(revealTimer)
  selected.value = null
  revealed.value = null
  note.value = ''
  actionError.value = ''
  trail.value = []
  trailError.value = ''
}
async function loadTrail(item, attempt) {
  trailLoading.value = true
  trailError.value = ''
  try {
    const result = await api.audit(item, targetType.value)
    if (attempt === detailVersion) trail.value = result
  } catch (failure) { if (attempt === detailVersion) trailError.value = failure.message || '操作记录读取失败。' }
  finally { if (attempt === detailVersion) trailLoading.value = false }
}
function openDetail(item) {
  closeDetail()
  selected.value = item
  loadTrail(item, ++detailVersion)
}
async function reveal() {
  if (note.value.trim().length < 5) { actionError.value = '请先填写至少5字的查阅用途，查阅将留存记录。'; return }
  const item = selected.value
  const attempt = detailVersion
  busy.value = true
  actionError.value = ''
  try {
    const method = section.value === 'merchants' ? 'revealMerchant' : 'revealIdentity'
    const result = await api[method](item, note.value.trim())
    if (attempt !== detailVersion) return
    revealed.value = result
    clearTimeout(revealTimer)
    revealTimer = setTimeout(() => { revealed.value = null }, 60000)
    await loadTrail(item, attempt)
  } catch (failure) { if (attempt === detailVersion) actionError.value = failure.message || '完整资料无法读取。' }
  finally { if (attempt === detailVersion) busy.value = false }
}
async function review(nextStatus) {
  if (note.value.trim().length < 5) { actionError.value = '请填写至少5字的处理依据或驳回原因。'; return }
  const item = selected.value
  const attempt = detailVersion
  const method = { information: 'informationStatus', merchants: 'merchantReview' }[section.value]
  busy.value = true
  actionError.value = ''
  try {
    await api[method](item, { status: nextStatus, note: note.value.trim(), ...(section.value === 'information' ? { updatedAt: item.updatedAt } : {}) })
    if (attempt !== detailVersion) return
    success.value = `处理结果已保存：${managementStatuses[nextStatus]}。`
    busy.value = false
    closeDetail()
    await load()
  } catch (failure) {
    if (attempt === detailVersion) actionError.value = failure.message || '暂时无法确认处理结果，请刷新后核对。'
  } finally { if (attempt === detailVersion) busy.value = false }
}

watch(() => [section.value, auth.user?.id, auth.tenantId], () => {
  version++
  detailVersion++
  busy.value = false
  closeDetail()
  keyword.value = ''
  status.value = ''; category.value = ''; page.value = 0
  records.value = []; summary.value = null; total.value = null; success.value = ''
  load()
}, { immediate: true })
onBeforeUnmount(() => { version++; detailVersion++; clearTimeout(revealTimer); revealed.value = null })
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>{{ title }}</span></div><h1>{{ title }}</h1><p>{{ subtitle }}</p></div>
      <div class="management-heading-actions"><span class="management-scope"><span />平台运营工作台</span><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新数据</button></div>
    </header>

    <div class="management-stats" :class="{ 'three-columns': stats.length === 3 }"><article v-for="stat in stats" :key="stat.label"><div><span>{{ stat.label }}</span><component :is="stat.icon" :size="17" /></div><strong>{{ stat.value == null ? '—' : Number(stat.value).toLocaleString('zh-CN') }}</strong><p>{{ stat.helper }}</p></article></div>
    <p v-if="summaryError" class="mg-muted" role="status">{{ summaryError }}</p>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section class="management-list" :aria-busy="loading" :aria-label="isIdentity ? '实名申请列表' : '信息与商家列表'">
      <div v-if="!isIdentity" class="management-tabs" aria-label="信息类型"><button type="button" :class="{ active: section === 'information' }" @click="changeSection('information')"><FileText :size="16" />平台发布信息</button><button type="button" :class="{ active: section === 'merchants' }" @click="changeSection('merchants')"><Store :size="16" />商家档案与入驻</button></div>
      <div v-else class="mg-list-caption"><h2>实名审核记录</h2><span><LockKeyhole :size="13" />敏感资料默认脱敏</span></div>
      <form class="management-filters" @submit.prevent="search">
        <label class="mg-search"><Search :size="17" /><input v-model="keyword" :placeholder="isIdentity ? '搜索用户名称、用户编号或证件尾号' : section === 'merchants' ? '搜索商家、经营主体或地区' : '搜索标题、发布商家或地区'" aria-label="搜索关键词" maxlength="100" /><button v-if="keyword" type="button" aria-label="清空搜索" @click="keyword = ''; search()"><X :size="14" /></button></label>
        <label v-if="section === 'information'" class="mg-select"><span class="sr-only">信息分类</span><AppSelect v-model="category"><option value="">全部分类</option><option v-for="(label, value) in informationCategories" :key="value" :value="value">{{ label }}</option></AppSelect></label>
        <label class="mg-select"><span class="sr-only">处理状态</span><AppSelect v-model="status"><option value="">全部状态</option><option v-for="value in statusOptions" :key="value" :value="value">{{ managementStatuses[value] }}</option></AppSelect></label>
        <button class="mg-button mg-primary" type="submit" :disabled="loading">查询</button>
      </form>
      <div v-if="error" class="mg-state mg-error" role="alert"><ClipboardList :size="30" /><h3>暂时无法读取数据</h3><p>{{ error }}</p><button class="mg-button" type="button" :disabled="loading" @click="load">重新读取</button></div>
      <div v-else-if="loading" class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取平台记录…</p></div>
      <div v-else-if="!records.length" class="mg-state"><component :is="isIdentity ? ShieldCheck : section === 'merchants' ? Store : FileText" :size="32" /><h3>{{ keyword || status || category ? '没有匹配的记录' : isIdentity ? '还没有实名申请' : section === 'merchants' ? '还没有商家入驻申请' : '还没有发布信息' }}</h3><p>{{ keyword || status || category ? '调整关键词或筛选条件后重新查询。' : '用户提交资料后，真实记录将在这里显示。' }}</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>{{ isIdentity ? '用户与申请' : section === 'merchants' ? '商家 / 经营主体' : '发布信息' }}</th><th>{{ isIdentity ? '实名资料' : section === 'merchants' ? '行业 / 地区' : '分类 / 地区' }}</th><th>{{ isIdentity ? '所属商户' : section === 'merchants' ? '联系人' : '发布商家' }}</th><th>状态</th><th>{{ isIdentity ? '申请时间' : section === 'merchants' ? '提交时间' : '发布时间' }}</th><th class="mg-table-action">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in records" :key="`${item.tenantId}-${item.id}`">
              <td><button class="mg-record-title" type="button" @click="openDetail(item)"><span v-if="section === 'merchants'" class="mg-avatar">{{ item.merchantName?.slice(0, 1) }}</span><span><strong>{{ item.title || item.merchantName || item.userName || `用户 #${item.userId}` }}</strong><small>{{ isIdentity ? `用户 #${item.userId} · 申请 #${item.id}` : section === 'merchants' ? item.companyName : `信息 #${item.id}` }}</small></span></button></td>
              <td><template v-if="isIdentity"><strong class="mg-plain">{{ item.legalNameMasked }}</strong><small class="mg-mono">{{ item.idNumberMasked }}</small></template><template v-else><span>{{ section === 'merchants' ? item.industry : informationCategories[item.category] }}</span><small>{{ item.region }}</small></template></td>
              <td><span>{{ isIdentity ? item.tenantName : section === 'merchants' ? item.contactName : item.merchantName }}</span><small>{{ isIdentity ? item.phoneMasked : section === 'merchants' ? item.contactPhoneMasked : item.tenantName }}</small></td>
              <td><span class="mg-status" :class="`mg-${item.status.toLowerCase()}`"><i />{{ managementStatuses[item.status] }}</span></td>
              <td class="mg-time">{{ managementDate(isIdentity ? item.submittedAt : item.publishedAt || item.createdAt) }}</td>
              <td class="mg-table-action"><button type="button" class="mg-text-button" @click="openDetail(item)">{{ item.status === 'PENDING_REVIEW' ? '去审核' : '查看详情' }}<ChevronRight :size="14" /></button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="!error" class="management-pagination"><span>{{ total == null ? '正在读取记录' : `共 ${total.toLocaleString('zh-CN')} 条记录` }}</span><div><label><span class="sr-only">每页条数</span><AppSelect v-model.number="size" :disabled="loading" aria-label="每页条数" @change="search"><option :value="20">20 条 / 页</option><option :value="50">50 条 / 页</option></AppSelect></label><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ totalPages ? page + 1 : 0 }} / {{ totalPages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= totalPages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>
    <p class="management-footnote"><LockKeyhole :size="13" />{{ isIdentity ? '实名由阿里云当场核验姓名与身份证号码，平台不做人工审核。查看完整资料留存记录。' : '记录直接来自平台数据库；信息审核、上下线、入驻审核与暂停操作均留存处理依据。' }}</p>

    <Dialog :open="Boolean(selected)" class="management-dialog" @close="closeDetail">
      <div class="mg-backdrop" aria-hidden="true" /><div class="mg-drawer-position">
        <DialogPanel class="mg-drawer management-page">
          <header class="mg-drawer-header"><div><span>{{ isIdentity ? '实名申请' : section === 'merchants' ? '商家档案' : '信息详情' }} #{{ selected?.id }}</span><DialogTitle as="h2">{{ selectionTitle }}</DialogTitle></div><button class="mg-page-button" type="button" :disabled="busy" aria-label="关闭详情" @click="closeDetail"><X :size="20" /></button></header>
          <template v-if="selected">
            <DialogDescription class="mg-detail-description"><span class="mg-status" :class="`mg-${selected.status.toLowerCase()}`"><i />{{ managementStatuses[selected.status] }}</span><span>{{ selected.tenantName }} · 商户 #{{ selected.tenantId }}</span></DialogDescription>
            <div class="mg-drawer-body">
              <section v-if="isIdentity" class="mg-detail-section"><h3><ShieldCheck :size="17" />身份资料</h3><div class="mg-privacy-note"><LockKeyhole :size="15" /><p>完整资料仅用于本次核查。填写查阅用途后可查看，60秒后自动遮蔽。请勿将证件号码写入审核备注。</p></div><dl class="mg-detail-grid"><div><dt>姓名</dt><dd>{{ revealed?.legalName || selected.legalNameMasked }}</dd></div><div><dt>居民身份证号码</dt><dd class="mg-mono">{{ revealed?.idNumber || selected.idNumberMasked }}</dd></div><div><dt>账号</dt><dd>{{ selected.userName }} · #{{ selected.userId }}</dd></div><div><dt>绑定手机</dt><dd>{{ selected.phoneMasked }}</dd></div><div><dt>申请时间</dt><dd>{{ managementDate(selected.submittedAt) }}</dd></div><div><dt>核验时间</dt><dd>{{ managementDate(selected.reviewedAt) }}</dd></div></dl><button v-if="!revealed" class="mg-button" type="button" :disabled="busy" @click="reveal"><Eye :size="15" />按填写用途查看完整资料</button><button v-else class="mg-button" type="button" @click="revealed = null"><LockKeyhole :size="15" />立即遮蔽资料</button></section>
              <section v-else-if="section === 'merchants'" class="mg-detail-section"><h3><Store :size="17" />经营主体</h3><div class="mg-privacy-note"><LockKeyhole :size="15" /><p>信用代码与联系手机默认脱敏。填写查阅用途后可核对完整资料，60秒后自动遮蔽，查阅将留存记录。</p></div><dl class="mg-detail-grid"><div class="full"><dt>企业 / 个体工商户名称</dt><dd>{{ selected.companyName }}</dd></div><div><dt>统一社会信用代码</dt><dd class="mg-mono">{{ revealed?.creditCode || selected.creditCodeMasked }}</dd></div><div><dt>经营行业</dt><dd>{{ selected.industry }}</dd></div><div><dt>所在地区</dt><dd>{{ selected.region }}</dd></div><div><dt>联系人</dt><dd>{{ selected.contactName }} · {{ revealed?.contactPhone || selected.contactPhoneMasked }}</dd></div><div class="full"><dt>商家介绍</dt><dd class="mg-content">{{ selected.introduction }}</dd></div></dl><button v-if="!revealed" class="mg-button" type="button" :disabled="busy" @click="reveal"><Eye :size="15" />按填写用途查看完整资料</button><button v-else class="mg-button" type="button" @click="revealed = null"><LockKeyhole :size="15" />立即遮蔽资料</button></section>
              <section v-else class="mg-detail-section"><h3><FileText :size="17" />发布内容</h3><dl class="mg-detail-grid"><div><dt>内容分类</dt><dd>{{ informationCategories[selected.category] }}</dd></div><div><dt>发布商家</dt><dd>{{ selected.merchantName }}</dd></div><div><dt>地区</dt><dd>{{ selected.region }}</dd></div><div><dt>有效期至</dt><dd>{{ selected.validUntil || '长期有效' }}</dd></div></dl><p v-if="selected.summary" class="mg-summary-text">{{ selected.summary }}</p><p class="mg-content">{{ selected.content }}</p></section>
              <section v-if="selected.reviewNote" class="mg-detail-section"><h3>{{ isIdentity ? '核验结果' : '上次审核意见' }}</h3><p class="mg-content">{{ selected.reviewNote }}</p></section>
              <section class="mg-detail-section"><h3><ClipboardList :size="17" />处理与查阅依据</h3><label class="mg-note-label" for="management-review-note">{{ isIdentity ? '查阅用途' : '处理依据' }}<span>必填 · 至少5字</span></label><textarea id="management-review-note" v-model="note" class="mg-note" rows="3" maxlength="500" :disabled="busy" :placeholder="isIdentity ? '填写查阅用途。请勿填写完整证件号。' : '说明审核依据、调整原因，或需要商家补充的资料。'" /><span class="mg-note-count">{{ note.length }}/500</span><p v-if="actionError" class="mg-inline-error" role="alert">{{ actionError }}</p></section>
              <section class="mg-detail-section"><h3>最近处理记录</h3><p v-if="trailLoading" class="mg-muted" role="status">正在读取操作记录…</p><p v-else-if="trailError" class="mg-inline-error" role="alert">{{ trailError }}</p><p v-else-if="!trail.length" class="mg-muted">暂无平台处理记录。</p><ol v-else class="mg-audit"><li v-for="event in trail" :key="event.id"><span class="mg-audit-dot" /><div><strong>{{ auditActions[event.action] || event.action }}<small>运营人员 #{{ event.actorUserId }}</small></strong><p>{{ event.note }}</p><time>{{ managementDate(event.createdAt) }}</time></div></li></ol></section>
            </div>
            <footer class="mg-drawer-actions">
              <span v-if="section === 'information' && selected.status === 'PENDING_REVIEW'">平台人工审核</span>
              <button class="mg-button" type="button" :disabled="busy" @click="closeDetail">关闭</button>
              <template v-if="section === 'information'">
                <button v-if="selected.status === 'PENDING_REVIEW'" class="mg-button mg-danger" type="button" :disabled="busy" @click="review('REJECTED')">驳回信息</button>
                <button v-if="selected.status === 'PENDING_REVIEW'" class="mg-button mg-primary" type="button" :disabled="busy" @click="review('PUBLISHED')"><Check :size="15" />{{ busy ? '正在保存…' : '审核通过并发布' }}</button>
                <button v-if="selected.status === 'PUBLISHED'" class="mg-button mg-danger" type="button" :disabled="busy" @click="review('OFFLINE')"><ArrowDownToLine :size="15" />{{ busy ? '正在保存…' : '下线信息' }}</button>
                <button v-if="selected.status === 'OFFLINE' && selected.publishedAt" class="mg-button mg-primary" type="button" :disabled="busy" @click="review('PUBLISHED')">{{ busy ? '正在保存…' : '重新上线' }}</button>
              </template>
              <template v-else-if="section === 'merchants'">
                <button v-if="selected.status === 'PENDING_REVIEW'" class="mg-button mg-danger" type="button" :disabled="busy" @click="review('REJECTED')">驳回申请</button>
                <button v-if="selected.status === 'APPROVED'" class="mg-button mg-danger" type="button" :disabled="busy" @click="review('SUSPENDED')">暂停入驻</button>
                <button v-if="['PENDING_REVIEW', 'SUSPENDED'].includes(selected.status)" class="mg-button mg-primary" type="button" :disabled="busy" @click="review('APPROVED')">{{ busy ? '正在保存…' : selected.status === 'SUSPENDED' ? '恢复入驻' : '通过入驻' }}</button>
              </template>
            </footer>
          </template>
        </DialogPanel>
      </div>
    </Dialog>
  </div>
</template>
