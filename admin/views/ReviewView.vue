<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, Check, Eye, Inbox, LockKeyhole, RefreshCw } from 'lucide-vue-next'
import { isOverdue, reviewInputError, reviewKindOrder, reviewKinds, waitedLabel } from '../../src/domain/adminReview.js'
import { auditActions, informationCategories, managementDate } from '../../src/domain/platformManagement.js'
import { accountStatuses, accountTypes, yuan } from '../../src/services/allianceApi.js'
import { loadReviewQueue, QUEUE_PAGE_SIZE, revealDetail, reviewTrail, submitReview } from '../reviewQueue.js'
import { refreshPending } from '../pendingReviews.js'

const route = useRoute()
const router = useRouter()
const items = ref([])
const failed = ref([])
const more = ref({})
const loading = ref(false)
const loadError = ref('')
const selectedKey = ref('')
const note = ref('')
const rate = ref('')
const busy = ref(false)
const actionError = ref('')
const success = ref('')
const revealed = ref(null)
const trail = ref([])
const trailState = ref('')
const now = ref(Date.now())
let generation = 0
let detailGeneration = 0
let revealTimer
let clock

const tab = computed(() => reviewKindOrder.includes(route.query.kind) ? route.query.kind : 'all')
const tabs = computed(() => [
  { kind: 'all', label: '全部', count: items.value.length },
  ...reviewKindOrder.map(kind => ({ kind, label: reviewKinds[kind].label, count: items.value.filter(item => item.kind === kind).length })),
])
const visible = computed(() => tab.value === 'all' ? items.value : items.value.filter(item => item.kind === tab.value))
const selected = computed(() => visible.value.find(item => item.key === selectedKey.value) || null)
const rule = computed(() => selected.value ? reviewKinds[selected.value.kind] : null)
const raw = computed(() => selected.value?.raw)
const canReveal = computed(() => ['merchant', 'account'].includes(selected.value?.kind))
const accountMismatch = computed(() => selected.value?.kind === 'account' && raw.value.account.accountType === 'CORPORATE' && raw.value.account.accountName !== raw.value.registeredCompanyName)
const failedLabels = computed(() => failed.value.map(kind => reviewKinds[kind].title).join('、'))
const moreLabels = computed(() => Object.entries(more.value).map(([kind, total]) => `${reviewKinds[kind].title}共 ${total} 条`).join('，'))

async function load() {
  const attempt = ++generation
  loading.value = true
  loadError.value = ''
  try {
    const result = await loadReviewQueue()
    if (attempt !== generation) return
    items.value = result.items
    failed.value = result.failed
    more.value = result.more
    now.value = Date.now()
    if (!visible.value.some(item => item.key === selectedKey.value)) select(visible.value[0])
  } catch (failure) {
    if (attempt === generation) loadError.value = failure.message || '待处理事项读取失败，请重试。'
  } finally {
    if (attempt === generation) loading.value = false
  }
}

function resetDetail() {
  detailGeneration++
  clearTimeout(revealTimer)
  revealed.value = null
  note.value = ''
  rate.value = ''
  actionError.value = ''
  trail.value = []
  trailState.value = ''
}

async function select(item) {
  if (busy.value) return
  resetDetail()
  selectedKey.value = item?.key || ''
  if (!item || !['information', 'merchant'].includes(item.kind)) return
  const attempt = detailGeneration
  trailState.value = 'loading'
  try {
    const result = await reviewTrail(item)
    if (attempt === detailGeneration) { trail.value = result || []; trailState.value = '' }
  } catch {
    if (attempt === detailGeneration) trailState.value = 'error'
  }
}

function changeTab(kind) {
  router.replace({ query: kind === 'all' ? {} : { kind } })
}

function addReason(text) {
  const current = note.value.trim()
  note.value = current ? `${current.replace(/[。；;]$/, '')}；${text}` : text
}

async function reveal() {
  if (note.value.trim().length < 5) { actionError.value = '查看完整资料前，请在下方写清查阅用途（至少 5 字），会留存记录。'; return }
  const item = selected.value
  const attempt = detailGeneration
  busy.value = true
  actionError.value = ''
  try {
    const result = await revealDetail(item, note.value)
    if (attempt !== detailGeneration) return
    revealed.value = result
    clearTimeout(revealTimer)
    revealTimer = setTimeout(() => { revealed.value = null }, 60000)
  } catch (failure) {
    if (attempt === detailGeneration) actionError.value = failure.message || '完整资料暂时无法读取。'
  } finally {
    if (attempt === detailGeneration) busy.value = false
  }
}

async function decide(approved) {
  const item = selected.value
  if (!item || busy.value) return
  const problem = reviewInputError(item.kind, approved, { note: note.value, rate: rate.value })
  if (problem) { actionError.value = problem; return }
  const attempt = detailGeneration
  busy.value = true
  actionError.value = ''
  success.value = ''
  try {
    await submitReview(item, approved, { note: note.value, rate: rate.value })
    if (attempt !== detailGeneration) return
    const rule = reviewKinds[item.kind]
    const index = visible.value.findIndex(entry => entry.key === item.key)
    const next = visible.value[index + 1] || visible.value[index - 1]
    items.value = items.value.filter(entry => entry.key !== item.key)
    success.value = `已${approved ? rule.approve : rule.reject}：${item.title}`
    busy.value = false
    select(next)
    refreshPending()
  } catch (failure) {
    if (attempt === detailGeneration) actionError.value = failure.message || '暂时无法确认处理结果，请刷新后核对。'
  } finally {
    if (attempt === detailGeneration) busy.value = false
  }
}

function onKey(event) {
  if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey || busy.value) return
  if (event.target.closest?.('input,textarea,select,[contenteditable="true"],[role="dialog"]')) return
  if (event.key !== 'j' && event.key !== 'k') return
  const index = visible.value.findIndex(item => item.key === selectedKey.value)
  const next = visible.value[index + (event.key === 'j' ? 1 : -1)]
  if (next) { event.preventDefault(); select(next); document.querySelector(`[data-review-key="${next.key}"]`)?.scrollIntoView({ block: 'nearest' }) }
}

watch(tab, () => { if (!visible.value.some(item => item.key === selectedKey.value)) select(visible.value[0]) })
onMounted(() => {
  window.addEventListener('keydown', onKey)
  clock = setInterval(() => { now.value = Date.now() }, 60000)
  load()
})
onBeforeUnmount(() => {
  generation++
  detailGeneration++
  clearTimeout(revealTimer)
  clearInterval(clock)
  window.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div class="management-page rv-page">
    <header class="ad-page-head">
      <div><h1>审核中心</h1><p>信息、入驻、供券、申诉和收款账号在这里逐条处理，处理完自动跳到下一条。等得最久的排在最前。</p></div>
      <button class="mg-button" type="button" :disabled="loading || busy" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
    </header>

    <p v-if="failed.length" class="ad-alert" role="alert"><AlertTriangle :size="15" />{{ failedLabels }}暂时读不到，其他事项照常处理。</p>
    <p v-if="moreLabels" class="ad-note">{{ moreLabels }}，这里只显示等得最久的 {{ QUEUE_PAGE_SIZE }} 条，处理完刷新会接着显示。</p>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}</div>

    <div class="rv-tabs" role="tablist" aria-label="事项类型">
      <button v-for="item in tabs" :key="item.kind" type="button" role="tab" :aria-selected="tab === item.kind" :class="{ active: tab === item.kind }" @click="changeTab(item.kind)">{{ item.label }}<b :class="{ quiet: !item.count }">{{ item.count }}</b></button>
    </div>

    <div class="rv-board" :aria-busy="loading">
      <div v-if="loadError" class="mg-state mg-error rv-full" role="alert"><h3>暂时无法读取</h3><p>{{ loadError }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="loading && !items.length" class="mg-state rv-full" role="status"><RefreshCw :size="26" class="mg-spinning" /><p>正在读取待处理事项…</p></div>
      <template v-else>
        <ul class="rv-list" aria-label="待处理列表">
          <li v-for="item in visible" :key="item.key">
            <button type="button" :data-review-key="item.key" :class="{ active: item.key === selectedKey }" :aria-current="item.key === selectedKey ? 'true' : undefined" @click="select(item)">
              <span class="rv-row"><span class="rv-kind">{{ reviewKinds[item.kind].label }}</span><span :class="['rv-wait', { late: isOverdue(item, now) }]">{{ isOverdue(item, now) ? '超过一天 · ' : '' }}{{ waitedLabel(item.since, now) }}</span></span>
              <strong>{{ item.title }}</strong>
              <small>{{ item.party }}<template v-if="item.detail"> · {{ item.detail }}</template></small>
            </button>
          </li>
          <li v-if="!visible.length" class="rv-list-empty">这一类没有待处理的事项。</li>
        </ul>

        <section v-if="selected" class="rv-detail" aria-label="事项详情">
          <div class="rv-body">
            <div class="rv-meta"><span :class="['ad-pill', isOverdue(selected, now) ? 'alert' : 'warn']">{{ isOverdue(selected, now) ? '已超过一天' : '待处理' }}</span><span>{{ rule.title }}</span><span>已等 {{ waitedLabel(selected.since, now) }}</span></div>
            <h2>{{ selected.title }}</h2>
            <p class="rv-sub">{{ selected.party }}<template v-if="selected.detail"> · {{ selected.detail }}</template></p>

            <template v-if="selected.kind === 'information'">
              <section class="rv-section"><h3>发布内容</h3><p v-if="raw.summary" class="mg-summary-text">{{ raw.summary }}</p><p class="rv-content">{{ raw.content }}</p></section>
              <section class="rv-section"><h3>资料</h3><dl class="mg-detail-grid"><div><dt>内容分类</dt><dd>{{ informationCategories[raw.category] || raw.category }}</dd></div><div><dt>发布商家</dt><dd>{{ raw.merchantName }} · {{ raw.tenantName }}</dd></div><div><dt>地区</dt><dd>{{ raw.region || '—' }}</dd></div><div><dt>有效期至</dt><dd>{{ raw.validUntil || '长期有效' }}</dd></div></dl></section>
              <section v-if="raw.reviewNote" class="rv-section"><h3>上次审核意见</h3><p class="rv-content">{{ raw.reviewNote }}</p></section>
            </template>

            <template v-else-if="selected.kind === 'merchant'">
              <section class="rv-section"><h3>商家介绍</h3><p class="rv-content">{{ raw.introduction || '未填写' }}</p></section>
              <section class="rv-section"><h3>经营主体</h3><dl class="mg-detail-grid"><div class="full"><dt>企业 / 个体工商户名称</dt><dd>{{ raw.companyName }}</dd></div><div><dt>统一社会信用代码</dt><dd class="mg-mono">{{ revealed?.creditCode || raw.creditCodeMasked }}</dd></div><div><dt>经营行业</dt><dd>{{ raw.industry }}</dd></div><div><dt>所在地区</dt><dd>{{ raw.region }}</dd></div><div><dt>联系人</dt><dd>{{ raw.contactName }} · <span class="mg-mono">{{ revealed?.contactPhone || raw.contactPhoneMasked }}</span></dd></div></dl></section>
              <section v-if="raw.reviewNote" class="rv-section"><h3>上次审核意见</h3><p class="rv-content">{{ raw.reviewNote }}</p></section>
            </template>

            <template v-else-if="selected.kind === 'offer'">
              <section class="rv-section"><h3>供券内容</h3><dl class="mg-detail-grid"><div><dt>商品</dt><dd>{{ raw.offer.productName }}</dd></div><div><dt>单张面值</dt><dd class="mg-mono">¥{{ yuan(raw.offer.faceValueMinor) }}</dd></div><div><dt>供券数量</dt><dd>{{ raw.offer.totalQty }} {{ raw.offer.saleUnit }}</dd></div><div><dt>可用门店</dt><dd>{{ raw.offer.storeName }}<small class="rv-dd-sub">{{ raw.storeAddress || '未填写地址' }}</small></dd></div><div><dt>供券商户</dt><dd>{{ raw.supplierName }}<small class="rv-dd-sub mg-mono">{{ raw.supplierCreditCode }}</small></dd></div><div><dt>收款账户</dt><dd>{{ accountStatuses[raw.accountStatus] || '未填写' }}</dd></div></dl></section>
              <section class="rv-section"><h3>结算折扣</h3><label class="rv-rate" for="review-rate">按面值<input id="review-rate" v-model="rate" inputmode="decimal" maxlength="4" placeholder="5" :disabled="busy" />折结算</label><p class="ad-note">通过时必填。填 5 表示按面值 5 折结算，8.5 表示 8.5 折；驳回不用填。</p></section>
            </template>

            <template v-else-if="selected.kind === 'dispute'">
              <section class="rv-section"><h3>申诉理由</h3><p class="rv-content">{{ raw.disputeReason }}</p></section>
              <section class="rv-section"><h3>核销记录</h3><dl class="mg-detail-grid"><div><dt>商品</dt><dd>{{ raw.productName }}</dd></div><div><dt>面值</dt><dd class="mg-mono">¥{{ yuan(raw.faceValueMinor) }}</dd></div><div><dt>核销门店</dt><dd>{{ raw.storeName }}</dd></div><div><dt>领券商户</dt><dd>{{ raw.holderName }}</dd></div><div><dt>核销时间</dt><dd>{{ managementDate(raw.redeemedAt) }}</dd></div><div><dt>申诉时间</dt><dd>{{ managementDate(raw.disputedAt) }}</dd></div></dl></section>
              <p class="ad-note">判定有效：结算解冻，照常进对账单。判定无效：这笔结算作废，券恢复给领券商户，需要写明原因。</p>
            </template>

            <template v-else-if="selected.kind === 'account'">
              <p v-if="accountMismatch" class="ad-alert"><AlertTriangle :size="15" />对公账户户名与入驻主体不一致，请核对。</p>
              <section class="rv-section"><h3>收款账户</h3><dl class="mg-detail-grid"><div><dt>账户类型</dt><dd>{{ accountTypes[raw.account.accountType] }}</dd></div><div><dt>户名</dt><dd>{{ revealed?.accountName || raw.account.accountName }}</dd></div><div><dt>入驻主体</dt><dd>{{ raw.registeredCompanyName || '无审核通过的入驻' }}</dd></div><div><dt>开户行</dt><dd>{{ revealed ? `${revealed.bankName} ${revealed.branchName}` : `${raw.account.bankName} ${raw.account.branchName || ''}` }}</dd></div><div><dt>账号</dt><dd class="mg-mono">{{ revealed?.accountNumber || `尾号 ${raw.account.accountNumberLast4}` }}</dd></div><div><dt>财务联系人</dt><dd>{{ raw.account.financeContactName }} · <span class="mg-mono">{{ raw.account.financeContactPhone }}</span></dd></div></dl></section>
            </template>

            <section v-if="canReveal" class="rv-section"><div class="mg-privacy-note"><LockKeyhole :size="15" /><p>完整资料默认脱敏。在下方写清查阅用途后可查看，60 秒后自动遮蔽，查阅留存记录。</p></div><button v-if="!revealed" class="mg-button" type="button" :disabled="busy" @click="reveal"><Eye :size="15" />按填写用途查看完整资料</button><button v-else class="mg-button" type="button" @click="revealed = null"><LockKeyhole :size="15" />立即遮蔽</button></section>

            <section v-if="['information', 'merchant'].includes(selected.kind)" class="rv-section"><h3>处理经过</h3><p v-if="trailState === 'loading'" class="mg-muted">正在读取处理记录…</p><p v-else-if="trailState === 'error'" class="mg-inline-error">处理记录暂时读不到。</p><p v-else-if="!trail.length" class="mg-muted">第一次提交，平台还没有处理过。</p><ol v-else class="mg-audit"><li v-for="event in trail" :key="event.id"><span class="mg-audit-dot" /><div><strong>{{ auditActions[event.action] || event.action }}<small>运营人员 #{{ event.actorUserId }}</small></strong><p>{{ event.note }}</p><time>{{ managementDate(event.createdAt) }}</time></div></li></ol></section>
          </div>

          <footer class="rv-actions">
            <div class="rv-reasons" aria-label="常用处理依据"><button v-for="text in rule.reasons" :key="text" type="button" :disabled="busy" @click="addReason(text)">{{ text }}</button></div>
            <label class="sr-only" for="review-note">处理依据</label>
            <textarea id="review-note" v-model="note" class="mg-note" rows="2" :maxlength="rule.noteMax" :disabled="busy" :placeholder="rule.approveNeedsNote ? `处理依据，通过和驳回都要写，至少 ${rule.noteMin} 字，会留存在记录里` : '驳回或判定无效时写明原因，会展示给商户；通过时可不写'" />
            <div class="rv-action-row">
              <p v-if="actionError" class="mg-inline-error" role="alert">{{ actionError }}</p>
              <p v-else class="rv-keys"><kbd>J</kbd><kbd>K</kbd> 上一条 / 下一条</p>
              <button class="mg-button mg-danger" type="button" :disabled="busy" @click="decide(false)">{{ rule.reject }}</button>
              <button class="mg-button mg-primary" type="button" :disabled="busy" @click="decide(true)"><Check :size="15" />{{ busy ? '正在保存…' : rule.approve }}</button>
            </div>
          </footer>
        </section>
        <section v-else class="rv-detail rv-done"><Inbox :size="34" /><h2>{{ items.length ? '这一类处理完了' : '待处理的都处理完了' }}</h2><p>新的申请进来后点「刷新」，侧栏和铃铛的数字每分钟自动更新。</p></section>
      </template>
    </div>
  </div>
</template>
