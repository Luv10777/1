<script setup>
import AppSelect from '../components/AppSelect.vue'
import { onBeforeUnmount, ref, watch } from 'vue'
import { Dialog, DialogPanel, DialogTitle, DialogDescription } from '@headlessui/vue'
import { Ban, Check, ChevronLeft, ChevronRight, ClipboardList, Crown, Flame, Gift, LockKeyhole, LogIn, RefreshCw, Search, UserCheck, UserPlus, Users, X } from 'lucide-vue-next'
import { platformManagementApi as api } from '../services/platformManagement'
import { planAdminApi } from '../services/planAdminApi'
import { auth } from '../stores/auth'
import { loadPlatformAccess, platform } from '../stores/platform'
import { auditActions, managementDate, userRoles, userStatuses } from '../domain/platformManagement'
import '../platform-management.css'

const records = ref([])
const total = ref(null)
const totalPages = ref(0)
const page = ref(0)
const size = ref(20)
const keyword = ref('')
const status = ref('')
const role = ref('')
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
const plans = ref([])
const plansError = ref('')
const planId = ref('')
const credits = ref(100)
const billingNote = ref('')
const billingError = ref('')
const billingDone = ref('')
const confirming = ref(null)
let version = 0
let detailVersion = 0

const stats = [
  { key: 'users', label: '用户总数', helper: '不含已被商户移除的账号', icon: Users },
  { key: 'activeLast7Days', label: '近 7 天登录', helper: '最近一周登录过的账号', icon: LogIn },
  { key: 'newLast7Days', label: '近 7 天新增', helper: '最近一周注册或被添加', icon: UserPlus },
  { key: 'suspended', label: '平台停用', helper: '被平台停用、无法登录', icon: Ban },
]

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
    api.users({ page: page.value, size: size.value, keyword: keyword.value.trim(), status: status.value, role: role.value }),
    api.userSummary(),
  ])
  if (attempt !== version) return
  if (listResult.status === 'fulfilled') {
    records.value = listResult.value.items
    total.value = listResult.value.total
    totalPages.value = listResult.value.totalPages
  } else {
    records.value = []
    total.value = null
    error.value = listResult.reason?.message || '用户数据读取失败，请重试。'
  }
  if (summaryResult.status === 'fulfilled') summary.value = summaryResult.value
  else { summary.value = null; summaryError.value = '统计暂时无法读取。' }
  loading.value = false
}

function search() { page.value = 0; load() }
function movePage(next) { page.value = next; closeDetail(); load() }
function closeDetail() {
  if (busy.value) return
  detailVersion++
  selected.value = null
  note.value = ''
  actionError.value = ''
  trail.value = []
  trailError.value = ''
  billingNote.value = ''
  billingError.value = ''
  billingDone.value = ''
  confirming.value = null
}
async function loadTrail(item, attempt) {
  trailLoading.value = true
  trailError.value = ''
  try {
    const result = await api.audit(item, 'USER')
    if (attempt === detailVersion) trail.value = result
  } catch (failure) { if (attempt === detailVersion) trailError.value = failure.message || '处理记录读取失败。' }
  finally { if (attempt === detailVersion) trailLoading.value = false }
}
function openDetail(item) {
  closeDetail()
  selected.value = item
  loadTrail(item, ++detailVersion)
  loadPlans()
}
// 只列已上架的套餐；没有会员与烟火管理权限时，接口返回 403，提示原因并禁用操作。
async function loadPlans() {
  if (plans.value.length) return
  plansError.value = ''
  try {
    plans.value = (await planAdminApi.plans()).filter(item => item.published).map(item => item.plan)
    planId.value = plans.value[0] ? String(plans.value[0].id) : ''
  } catch (failure) { plansError.value = failure.message || '套餐读取失败，暂时不能开通会员。' }
}
const chosenPlan = () => plans.value.find(plan => String(plan.id) === String(planId.value))
const tenantLabel = item => `「${item.tenantName || `商户 #${item.tenantId}`}」`
// 先确认再提交；确认时固定套餐、数量和 requestKey，重试沿用，网络中断后重试也不会重复开通或重复赠送。
function ask(kind) {
  billingError.value = ''
  billingDone.value = ''
  const note = billingNote.value.trim()
  if (note.length < 5) { billingError.value = '请填写至少5字的处理依据。'; return }
  const item = selected.value
  let text, plan = null, amount = null
  if (kind === 'grant') {
    plan = chosenPlan()
    if (!plan) { billingError.value = '请选择要开通的套餐。'; return }
    text = `为${tenantLabel(item)}开通「${plan.name}」${plan.durationDays} 天会员，赠送 ${plan.giftCredits.toLocaleString('zh-CN')} 烟火${item.memberActive ? '，从现在的到期时间往后顺延' : ''}？`
  } else if (kind === 'revoke') {
    text = `立即关闭${tenantLabel(item)}的会员？已发放的烟火和联盟券额度不收回，正在直播的场次会被暂停。`
  } else {
    amount = Number(credits.value)
    if (!Number.isInteger(amount) || amount < 1 || amount > 100000) { billingError.value = '赠送烟火须为 1 至 100,000 的整数。'; return }
    text = `向${tenantLabel(item)}赠送 ${amount.toLocaleString('zh-CN')} 烟火？`
  }
  confirming.value = { kind, text, planId: plan?.id ?? null, amount, note, requestKey: 'platform-' + crypto.randomUUID() }
}
async function confirmBilling() {
  const { kind, planId: plan, amount, note, requestKey } = confirming.value
  const item = selected.value
  const attempt = detailVersion
  busy.value = true
  billingError.value = ''
  try {
    const result = kind === 'credits'
      ? await api.userCredits(item, { credits: amount, requestKey, note })
      : await api.userMembership(item, { granted: kind === 'grant', planId: plan, requestKey, note })
    if (attempt !== detailVersion) return
    selected.value = result
    // 会员和烟火按商户记，同一商户的其他账号在列表里一起更新。
    records.value = records.value.map(row => row.tenantId === result.tenantId
      ? { ...row, memberActive: result.memberActive, memberUntil: result.memberUntil, planName: result.planName, creditsAvailable: result.creditsAvailable }
      : row)
    billingDone.value = kind === 'grant' ? '会员已开通。' : kind === 'revoke' ? '会员已关闭。' : '烟火已赠送。'
    confirming.value = null
    billingNote.value = ''
    loadTrail(result, attempt)
  } catch (failure) {
    if (attempt === detailVersion) billingError.value = failure.message || '暂时无法确认处理结果，请刷新后核对。'
  } finally { if (attempt === detailVersion) busy.value = false }
}
const canSuspend = item => item.status !== 'SUSPENDED' && item.status !== 'REMOVED' && !item.operator && item.id !== auth.user?.id
async function changeSuspension(suspended) {
  if (note.value.trim().length < 5) { actionError.value = '请填写至少5字的处理依据。'; return }
  const item = selected.value
  const attempt = detailVersion
  busy.value = true
  actionError.value = ''
  try {
    const result = await api.userSuspension(item, { suspended, note: note.value.trim() })
    if (attempt !== detailVersion) return
    success.value = suspended ? `已停用 ${result.name || `用户 #${result.id}`}，对方所有设备已下线。` : `已解除 ${result.name || `用户 #${result.id}`} 的平台停用。`
    busy.value = false
    closeDetail()
    await load()
  } catch (failure) {
    if (attempt === detailVersion) actionError.value = failure.message || '暂时无法确认处理结果，请刷新后核对。'
  } finally { if (attempt === detailVersion) busy.value = false }
}
const membership = item => item.memberActive ? `${item.planName || '会员'} · ${managementDate(item.memberUntil).slice(0, 10)} 到期` : '未开通会员'

watch(() => [auth.user?.id, auth.tenantId], () => {
  version++
  detailVersion++
  busy.value = false
  closeDetail()
  keyword.value = ''; status.value = ''; role.value = ''; page.value = 0
  records.value = []; summary.value = null; total.value = null; success.value = ''
  load()
}, { immediate: true })
onBeforeUnmount(() => { version++; detailVersion++ })
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>用户管理</span></div><h1>用户管理</h1><p>查看全平台账号的归属、会员与登录情况，必要时停用账号。</p></div>
      <div class="management-heading-actions"><span class="management-scope"><span />平台运营工作台</span><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新数据</button></div>
    </header>

    <div class="management-stats"><article v-for="stat in stats" :key="stat.key"><div><span>{{ stat.label }}</span><component :is="stat.icon" :size="17" /></div><strong>{{ summary?.[stat.key] == null ? '—' : Number(summary[stat.key]).toLocaleString('zh-CN') }}</strong><p>{{ stat.helper }}</p></article></div>
    <p v-if="summaryError" class="mg-muted" role="status">{{ summaryError }}</p>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section class="management-list" :aria-busy="loading" aria-label="用户列表">
      <div class="mg-list-caption"><h2>全部用户</h2><span><LockKeyhole :size="13" />手机号默认脱敏</span></div>
      <form class="management-filters" @submit.prevent="search">
        <label class="mg-search"><Search :size="17" /><input v-model="keyword" placeholder="搜索姓名、手机号、登录账号、商户或用户编号" aria-label="搜索关键词" maxlength="100" /><button v-if="keyword" type="button" aria-label="清空搜索" @click="keyword = ''; search()"><X :size="14" /></button></label>
        <label class="mg-select"><span class="sr-only">角色</span><AppSelect v-model="role"><option value="">全部角色</option><option v-for="(label, value) in userRoles" :key="value" :value="value">{{ label }}</option></AppSelect></label>
        <label class="mg-select"><span class="sr-only">账号状态</span><AppSelect v-model="status"><option value="">当前账号</option><option v-for="(label, value) in userStatuses" :key="value" :value="value">{{ label }}</option></AppSelect></label>
        <button class="mg-button mg-primary" type="submit" :disabled="loading">查询</button>
      </form>
      <div v-if="error" class="mg-state mg-error" role="alert"><ClipboardList :size="30" /><h3>暂时无法读取数据</h3><p>{{ error }}</p><button class="mg-button" type="button" :disabled="loading" @click="load">重新读取</button></div>
      <div v-else-if="loading" class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取用户记录…</p></div>
      <div v-else-if="!records.length" class="mg-state"><Users :size="32" /><h3>{{ keyword || status || role ? '没有匹配的用户' : '还没有用户' }}</h3><p>{{ keyword || status || role ? '调整关键词或筛选条件后重新查询。' : '商家注册或被添加后，账号会在这里显示。' }}</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>用户</th><th>所属商户</th><th>会员与烟火</th><th>状态</th><th>最近登录</th><th class="mg-table-action">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in records" :key="item.id">
              <td><button class="mg-record-title" type="button" @click="openDetail(item)"><span class="mg-avatar">{{ (item.name || '用').slice(0, 1) }}</span><span><strong>{{ item.name || '未填写姓名' }}</strong><small>用户 #{{ item.id }} · {{ item.phoneMasked }}</small></span></button></td>
              <td><span>{{ item.tenantName || '—' }}</span><small>{{ userRoles[item.role] || item.role }}{{ item.operator ? ' · 平台运营' : '' }}</small></td>
              <td><span>{{ membership(item) }}</span><small>烟火 {{ Number(item.creditsAvailable).toLocaleString('zh-CN') }}</small></td>
              <td><span class="mg-status" :class="`mg-${item.status.toLowerCase()}`"><i />{{ userStatuses[item.status] || item.status }}</span></td>
              <td class="mg-time">{{ item.lastLoginAt ? managementDate(item.lastLoginAt) : '从未登录' }}</td>
              <td class="mg-table-action"><button type="button" class="mg-text-button" @click="openDetail(item)">查看详情<ChevronRight :size="14" /></button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="!error" class="management-pagination"><span>{{ total == null ? '正在读取记录' : `共 ${total.toLocaleString('zh-CN')} 个用户` }}</span><div><label><span class="sr-only">每页条数</span><AppSelect v-model.number="size" :disabled="loading" aria-label="每页条数" @change="search"><option :value="20">20 条 / 页</option><option :value="50">50 条 / 页</option></AppSelect></label><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ totalPages ? page + 1 : 0 }} / {{ totalPages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= totalPages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>
    <p class="management-footnote"><LockKeyhole :size="13" />平台停用会让对方所有设备立即下线，商户无法自行恢复；停用与解除都留存处理依据。</p>

    <Dialog :open="Boolean(selected)" class="management-dialog" @close="closeDetail">
      <div class="mg-backdrop" aria-hidden="true" /><div class="mg-drawer-position">
        <DialogPanel class="mg-drawer management-page">
          <header class="mg-drawer-header"><div><span>用户 #{{ selected?.id }}</span><DialogTitle as="h2">{{ selected?.name || '未填写姓名' }}</DialogTitle></div><button class="mg-page-button" type="button" :disabled="busy" aria-label="关闭详情" @click="closeDetail"><X :size="20" /></button></header>
          <template v-if="selected">
            <DialogDescription class="mg-detail-description"><span class="mg-status" :class="`mg-${selected.status.toLowerCase()}`"><i />{{ userStatuses[selected.status] || selected.status }}</span><span>{{ selected.tenantName }} · 商户 #{{ selected.tenantId }}</span></DialogDescription>
            <div class="mg-drawer-body">
              <section class="mg-detail-section"><h3><Users :size="17" />账号资料</h3><dl class="mg-detail-grid"><div><dt>角色</dt><dd>{{ userRoles[selected.role] || selected.role }}{{ selected.operator ? ' · 平台运营' : '' }}</dd></div><div><dt>绑定手机</dt><dd>{{ selected.phoneMasked }}</dd></div><div><dt>登录账号</dt><dd>{{ selected.username || '未设置' }}</dd></div><div><dt>注册时间</dt><dd>{{ managementDate(selected.createdAt) }}</dd></div><div><dt>最近登录</dt><dd>{{ selected.lastLoginAt ? managementDate(selected.lastLoginAt) : '从未登录' }}</dd></div><div v-if="selected.suspendedAt"><dt>平台停用于</dt><dd>{{ managementDate(selected.suspendedAt) }}</dd></div></dl></section>
              <section class="mg-detail-section"><h3><UserCheck :size="17" />所属商户的会员</h3><dl class="mg-detail-grid"><div><dt>会员套餐</dt><dd>{{ selected.memberActive ? selected.planName || '会员' : '未开通会员' }}</dd></div><div><dt>到期时间</dt><dd>{{ selected.memberActive ? managementDate(selected.memberUntil) : '—' }}</dd></div><div><dt>烟火余额</dt><dd>{{ Number(selected.creditsAvailable).toLocaleString('zh-CN') }}</dd></div></dl></section>
              <section class="mg-detail-section" aria-labelledby="user-billing-title">
                <h3 id="user-billing-title"><Flame :size="17" />会员与烟火操作</h3>
                <p class="mg-muted">会员和烟火按商户计算，操作作用于{{ tenantLabel(selected) }}下的所有账号。赠送的烟火不计入充值收入。</p>
                <p v-if="plansError" class="mg-inline-error" role="alert">{{ plansError }}</p>
                <div class="mg-billing-row">
                  <label class="mg-select"><span class="sr-only">会员套餐</span><AppSelect v-model="planId" :disabled="busy || Boolean(confirming) || !plans.length" aria-label="会员套餐"><option v-if="!plans.length" value="">暂无上架套餐</option><option v-for="plan in plans" :key="plan.id" :value="String(plan.id)">{{ plan.name }} · {{ plan.durationDays }} 天 · 送 {{ plan.giftCredits.toLocaleString('zh-CN') }} 烟火</option></AppSelect></label>
                  <button class="mg-button" type="button" :disabled="busy || Boolean(confirming) || !planId" @click="ask('grant')"><Crown :size="15" />{{ selected.memberActive ? '续期会员' : '开通会员' }}</button>
                  <button v-if="selected.memberActive" class="mg-button mg-danger" type="button" :disabled="busy || Boolean(confirming)" @click="ask('revoke')"><Ban :size="15" />关闭会员</button>
                </div>
                <div class="mg-billing-row">
                  <label class="mg-amount"><span class="sr-only">赠送烟火数量</span><input v-model.number="credits" type="number" min="1" max="100000" step="1" inputmode="numeric" :disabled="busy || Boolean(confirming)" /><span>烟火</span></label>
                  <button class="mg-button" type="button" :disabled="busy || Boolean(confirming)" @click="ask('credits')"><Gift :size="15" />赠送烟火</button>
                </div>
                <label class="mg-note-label" for="user-billing-note">处理依据<span>必填 · 5–300字</span></label>
                <textarea id="user-billing-note" v-model="billingNote" class="mg-note" rows="2" maxlength="300" :disabled="busy || Boolean(confirming)" placeholder="说明依据，例如线下签约、活动奖励、客服补偿单号。" />
                <div v-if="confirming" class="mg-confirm" role="alert"><p>{{ confirming.text }}</p><div><button class="mg-button" type="button" :disabled="busy" @click="confirming = null">取消</button><button class="mg-button" :class="confirming.kind === 'revoke' ? 'mg-danger' : 'mg-primary'" type="button" :disabled="busy" @click="confirmBilling">{{ busy ? '正在保存…' : '确认' }}</button></div></div>
                <p v-if="billingError" class="mg-inline-error" role="alert">{{ billingError }}</p>
                <p v-if="billingDone" class="mg-billing-done" role="status"><Check :size="14" />{{ billingDone }}</p>
              </section>
              <section v-if="selected.status === 'SUSPENDED' || canSuspend(selected)" class="mg-detail-section"><h3><ClipboardList :size="17" />处理依据</h3><label class="mg-note-label" for="user-suspension-note">{{ selected.status === 'SUSPENDED' ? '解除原因' : '停用原因' }}<span>必填 · 至少5字</span></label><textarea id="user-suspension-note" v-model="note" class="mg-note" rows="3" maxlength="500" :disabled="busy" :placeholder="selected.status === 'SUSPENDED' ? '说明核实情况与解除依据。' : '说明停用依据，例如违规内容、投诉编号。停用后对方所有设备立即下线。'" /><span class="mg-note-count">{{ note.length }}/500</span><p v-if="actionError" class="mg-inline-error" role="alert">{{ actionError }}</p></section>
              <p v-else class="mg-muted">{{ selected.operator ? '平台运营账号不能在这里停用，请先从运营名单中移除。' : selected.id === auth.user?.id ? '不能停用自己的账号。' : '该账号已被商户移除，无需处理。' }}</p>
              <section class="mg-detail-section"><h3>最近处理记录</h3><p v-if="trailLoading" class="mg-muted" role="status">正在读取处理记录…</p><p v-else-if="trailError" class="mg-inline-error" role="alert">{{ trailError }}</p><p v-else-if="!trail.length" class="mg-muted">暂无平台处理记录。</p><ol v-else class="mg-audit"><li v-for="event in trail" :key="event.id"><span class="mg-audit-dot" /><div><strong>{{ auditActions[event.action] || event.action }}<small>运营人员 #{{ event.actorUserId }}</small></strong><p>{{ event.note }}</p><time>{{ managementDate(event.createdAt) }}</time></div></li></ol></section>
            </div>
            <footer class="mg-drawer-actions">
              <button class="mg-button" type="button" :disabled="busy" @click="closeDetail">关闭</button>
              <button v-if="selected.status === 'SUSPENDED'" class="mg-button mg-primary" type="button" :disabled="busy" @click="changeSuspension(false)"><UserCheck :size="15" />{{ busy ? '正在保存…' : '解除停用' }}</button>
              <button v-else-if="canSuspend(selected)" class="mg-button mg-danger" type="button" :disabled="busy" @click="changeSuspension(true)"><Ban :size="15" />{{ busy ? '正在保存…' : '停用账号' }}</button>
            </footer>
          </template>
        </DialogPanel>
      </div>
    </Dialog>
  </div>
</template>
