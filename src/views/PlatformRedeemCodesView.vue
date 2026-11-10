<script setup>
import AppSelect from '../components/AppSelect.vue'
import { reactive, ref } from 'vue'
import { Check, ChevronLeft, ChevronRight, Copy, Plus, RefreshCw, Search, TicketCheck, X } from 'lucide-vue-next'
import { formatRedeemCode, redeemCodeAdminApi as api, redeemCodeExpiry, redeemCodeKinds, redeemCodeStatuses } from '../services/redeemCodeAdminApi'
import { planAdminApi } from '../services/planAdminApi'
import { managementDate } from '../domain/platformManagement'
import '../platform-management.css'

const records = ref([])
const total = ref(null)
const totalPages = ref(0)
const page = ref(0)
const status = ref('')
const kind = ref('')
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const forbidden = ref(false)
const success = ref('')
const busy = ref(false)
const formOpen = ref(false)
const formError = ref('')
const blank = () => ({ kind: 'MEMBERSHIP', planId: '', credits: '100', amount: '0', quantity: '10', expiresOn: '', note: '' })
const form = reactive(blank())
let requestKey = ''
const created = ref([])
// 激活码按已上架的套餐生成，兑换后开通该套餐，不送烟火。
const plans = ref([])
const plansError = ref('')
async function loadPlans() {
  plansError.value = ''
  try { plans.value = (await planAdminApi.plans()).filter(item => item.published).map(item => item.plan) }
  catch (failure) { plans.value = []; plansError.value = failure.message || '套餐暂时无法读取。' }
}

const number = value => Number(value || 0).toLocaleString('zh-CN')
const money = minor => `¥${(Number(minor || 0) / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const today = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10)

async function load() {
  loading.value = true
  error.value = ''
  forbidden.value = false
  try {
    const result = await api.list({ page: page.value, size: 20, status: status.value, kind: kind.value, keyword: keyword.value.trim() })
    records.value = result.items
    total.value = result.total
    totalPages.value = result.totalPages
  } catch (failure) {
    records.value = []
    total.value = null
    if (failure.status === 403) forbidden.value = true
    else error.value = failure.message || '兑换码暂时无法读取。'
  } finally { loading.value = false }
}
function search() { page.value = 0; load() }
function movePage(next) { page.value = next; load() }

function openNew() {
  Object.assign(form, blank())
  formError.value = ''
  requestKey = 'redeem-' + crypto.randomUUID()
  formOpen.value = true
  loadPlans()
}

async function save() {
  if (busy.value) return
  formError.value = ''
  const membership = form.kind === 'MEMBERSHIP'
  const credits = membership ? 0 : Number(form.credits), quantity = Number(form.quantity)
  const plan = plans.value.find(item => String(item.id) === String(form.planId))
  if (membership && !plan) { formError.value = '请选择激活码开通的套餐。'; return }
  if (!membership && (!Number.isInteger(credits) || credits < 1 || credits > 100000)) { formError.value = '每个码的烟火须为 1 至 100,000 的整数。'; return }
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 200) { formError.value = '一次生成 1 至 200 个。'; return }
  const amountMinor = form.kind === 'TOPUP' ? Math.round(Number(form.amount || 0) * 100) : 0
  if (!Number.isInteger(amountMinor) || amountMinor < 0 || amountMinor > 10000000) { formError.value = '实收金额须为 0 至 100,000 元。'; return }
  busy.value = true
  try {
    created.value = await api.create({ requestKey, kind: form.kind, credits, amountMinor, quantity, expiresAt: redeemCodeExpiry(form.expiresOn), note: form.note.trim() || null, planId: membership ? plan.id : null })
    success.value = membership
      ? `已生成 ${created.value.length} 个激活码，每个开通「${plan.name}」${plan.durationDays} 天`
      : `已生成 ${created.value.length} 个${redeemCodeKinds[form.kind]}兑换码，每个 ${number(credits)} 烟火`
    formOpen.value = false
    search()
  } catch (failure) {
    formError.value = failure.message || '生成失败，请稍后再试。'
  } finally { busy.value = false }
}

async function copy(text, label) {
  try {
    await navigator.clipboard.writeText(text)
    success.value = `${label}已复制`
  } catch {
    success.value = `浏览器不允许自动复制，请手动选中${label}复制`
  }
}

async function voidCode(item) {
  if (busy.value || !window.confirm(`作废后 ${formatRedeemCode(item.code)} 不能再兑换，确定作废吗？`)) return
  busy.value = true
  error.value = ''
  try {
    await api.void(item.id)
    success.value = `${formatRedeemCode(item.code)} 已作废`
    await load()
  } catch (failure) {
    error.value = failure.message || '作废失败，请稍后再试。'
  } finally { busy.value = false }
}

load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>兑换码</span></div><h1>兑换码</h1><p>批量生成激活码或烟火兑换码发给商家，商家（管理员或店员）在「套餐与权益」页输入：激活码开通会员，兑换码烟火到账。每个码只能兑换一次。</p></div>
      <div class="management-heading-actions">
        <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
        <button v-if="!forbidden" class="mg-button mg-primary" type="button" @click="openNew"><Plus :size="15" />生成兑换码</button>
      </div>
    </header>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section v-if="formOpen" class="management-list code-form-panel" aria-labelledby="code-form-title">
      <div class="mg-list-caption"><h2 id="code-form-title">生成兑换码</h2><button class="mg-text-button" type="button" @click="formOpen = false">取消</button></div>
      <form class="code-form" @submit.prevent="save">
        <fieldset class="full code-kinds">
          <legend>类型</legend>
          <label><input v-model="form.kind" type="radio" value="MEMBERSHIP" name="code-kind" /><span><strong>激活码</strong><small>开通所选套餐的会员，有效期内兑换会顺延；不送烟火，使用功能照常扣烟火</small></span></label>
          <label><input v-model="form.kind" type="radio" value="TOPUP" name="code-kind" /><span><strong>充值</strong><small>记入商家的充值余额，计入「购买的烟火」，按实收金额记邀请返利</small></span></label>
          <label><input v-model="form.kind" type="radio" value="GIFT" name="code-kind" /><span><strong>赠送</strong><small>记入商家的赠送烟火，计入「赠送的烟火」，消耗时优先使用</small></span></label>
        </fieldset>
        <label v-if="form.kind === 'MEMBERSHIP'">开通的套餐<AppSelect v-model="form.planId" required><option value="" disabled>选择已上架的套餐</option><option v-for="plan in plans" :key="plan.id" :value="String(plan.id)">{{ plan.name }} · {{ plan.durationDays }} 天 · {{ money(plan.priceMinor) }}</option></AppSelect><small v-if="plansError" class="mg-error">{{ plansError }}</small><small v-else-if="!plans.length">还没有上架的套餐，请先到「会员套餐」上架月卡、季卡、年卡。</small></label>
        <label v-else>每个码的烟火<input v-model="form.credits" type="number" min="1" max="100000" step="1" required /></label>
        <label v-if="form.kind === 'TOPUP'">每个码的实收金额（元）<input v-model="form.amount" type="number" min="0" max="100000" step="0.01" required /><small>兑换后按这个金额给商家的邀请人记充值返利；填 0 不记返利。</small></label>
        <label>生成数量<input v-model="form.quantity" type="number" min="1" max="200" step="1" required /><small>一次最多 200 个，每个码各自只能用一次。</small></label>
        <label>失效日期（可选）<input v-model="form.expiresOn" type="date" :min="today()" /><small>当天 23:59 后失效；不填长期有效。</small></label>
        <label class="full">备注（可选）<input v-model="form.note" maxlength="200" placeholder="例如：双十一线下活动、某代理商批次" /></label>
        <p v-if="formError" class="mg-error full" role="alert">{{ formError }}</p>
        <div class="full code-form-actions"><button class="mg-button" type="button" @click="formOpen = false">取消</button><button class="mg-button mg-primary" type="submit" :disabled="busy">{{ busy ? '生成中…' : `生成 ${form.quantity || 0} 个` }}</button></div>
      </form>
    </section>

    <section v-if="created.length" class="management-list code-created" aria-labelledby="code-created-title">
      <div class="mg-list-caption"><h2 id="code-created-title">刚生成的兑换码</h2><span><button class="mg-text-button" type="button" @click="copy(created.map(item => formatRedeemCode(item.code)).join('\n'), '全部兑换码')"><Copy :size="13" />复制全部</button> · <button class="mg-text-button" type="button" @click="created = []">收起</button></span></div>
      <ol class="code-created-list"><li v-for="item in created" :key="item.id" class="mg-mono">{{ formatRedeemCode(item.code) }}</li></ol>
    </section>

    <section class="management-list" :aria-busy="loading" aria-label="兑换码列表">
      <form class="management-filters" @submit.prevent="search">
        <label class="mg-search"><Search :size="17" /><input v-model="keyword" placeholder="搜索兑换码或备注" aria-label="搜索兑换码" maxlength="100" /><button v-if="keyword" type="button" aria-label="清空搜索" @click="keyword = ''; search()"><X :size="14" /></button></label>
        <label class="mg-select"><span class="sr-only">状态</span><AppSelect v-model="status" @change="search"><option value="">全部状态</option><option v-for="(label, value) in redeemCodeStatuses" :key="value" :value="value">{{ label }}</option></AppSelect></label>
        <label class="mg-select"><span class="sr-only">类型</span><AppSelect v-model="kind" @change="search"><option value="">全部类型</option><option v-for="(label, value) in redeemCodeKinds" :key="value" :value="value">{{ label }}</option></AppSelect></label>
        <button class="mg-button mg-primary" type="submit" :disabled="loading">查询</button>
      </form>
      <div v-if="forbidden" class="mg-state mg-error" role="alert"><TicketCheck :size="30" /><h3>没有兑换码管理权限</h3><p>兑换码属于计费模块，需要把你的账号加入服务端配置 BILLING_OPERATOR_USER_IDS 后才能管理。</p></div>
      <div v-else-if="error" class="mg-state mg-error" role="alert"><TicketCheck :size="30" /><h3>暂时无法读取</h3><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="loading && !records.length" class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取兑换码…</p></div>
      <div v-else-if="!records.length" class="mg-state"><TicketCheck :size="32" /><h3>{{ keyword || status || kind ? '没有匹配的兑换码' : '还没有兑换码' }}</h3><p>{{ keyword || status || kind ? '调整关键词或筛选条件后重新查询。' : '点右上角「生成兑换码」，选择类型和烟火数量。' }}</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>兑换码</th><th>类型</th><th class="code-number">烟火 / 套餐</th><th class="code-number">实收</th><th>状态</th><th>兑换商户</th><th>备注</th><th>失效时间</th><th>生成时间</th><th class="mg-table-action">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in records" :key="item.id">
              <td class="mg-mono code-value">{{ formatRedeemCode(item.code) }}</td>
              <td>{{ redeemCodeKinds[item.kind] || item.kind }}</td>
              <td class="code-number mg-mono">{{ item.kind === 'MEMBERSHIP' ? `${item.planName || '套餐'} · ${item.durationDays ?? '—'} 天` : number(item.credits) }}</td>
              <td class="code-number mg-mono">{{ item.kind === 'TOPUP' ? money(item.amountMinor) : '—' }}</td>
              <td><span class="mg-status" :class="`code-${item.status.toLowerCase()}`"><i />{{ redeemCodeStatuses[item.status] || item.status }}</span></td>
              <td><template v-if="item.redeemedTenantId"><span>{{ item.redeemedTenantName || '—' }}</span><small>商户 #{{ item.redeemedTenantId }} · {{ managementDate(item.redeemedAt) }}</small></template><template v-else>—</template></td>
              <td class="code-note">{{ item.note || '—' }}</td>
              <td class="mg-time">{{ item.expiresAt ? managementDate(item.expiresAt) : '长期有效' }}</td>
              <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
              <td class="mg-table-action">
                <template v-if="item.status === 'UNUSED'"><button class="mg-text-button" type="button" @click="copy(formatRedeemCode(item.code), '兑换码')"><Copy :size="13" />复制</button> · <button class="mg-text-button" type="button" :disabled="busy" @click="voidCode(item)">作废</button></template>
                <template v-else>—</template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="!error && !forbidden" class="management-pagination"><span>{{ total == null ? '正在读取记录' : `共 ${total.toLocaleString('zh-CN')} 个兑换码` }}</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ totalPages ? page + 1 : 0 }} / {{ totalPages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= totalPages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>
  </div>
</template>

<style scoped>
.code-form-panel, .code-created { margin-bottom: 20px; }
.code-form { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px 18px; padding: 18px 22px 22px; }
.code-form label { display: grid; gap: 6px; color: var(--mg-muted); font-size: 12px; }
.code-form input { width: 100%; min-height: 36px; padding: 7px 10px; border: 1px solid #dfe4e9; border-radius: 5px; background: #fff; color: var(--mg-ink); font-size: 13px; }
.code-form small { color: var(--mg-muted); font-size: 12px; line-height: 1.6; }
.code-form .full { grid-column: 1 / -1; }
.code-kinds { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; padding: 0; margin: 0; border: 0; }
.code-kinds legend { margin-bottom: 6px; color: var(--mg-muted); font-size: 12px; }
.code-kinds label { display: flex; align-items: flex-start; gap: 10px; padding: 12px 14px; border: 1px solid var(--mg-line); border-radius: 6px; cursor: pointer; }
.code-kinds label:has(input:checked) { border-color: var(--mg-accent); background: var(--mg-soft); }
.code-kinds input { width: 15px; min-height: 0; margin-top: 3px; }
.code-kinds span { display: grid; gap: 2px; }
.code-kinds strong { color: var(--mg-ink); font-size: 13px; font-weight: 600; }
.code-form-actions { display: flex; justify-content: flex-end; gap: 10px; }
.code-created-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 6px 18px; margin: 0; padding: 14px 22px 18px 44px; font-size: 13px; }
.code-value { white-space: nowrap; letter-spacing: .03em; }
.code-number { text-align: right; }
.code-note { max-width: 220px; white-space: normal; }
.mg-status.code-unused { color: #284d77; background: #eef3f9; }
.mg-status.code-redeemed { color: #31714f; background: #eff7f1; }
.mg-status.code-voided, .mg-status.code-expired { color: #78818b; background: #f1f3f5; }
@media (max-width: 900px) { .code-form, .code-kinds { grid-template-columns: minmax(0, 1fr); } }
</style>
