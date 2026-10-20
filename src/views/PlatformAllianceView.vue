<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { Check, ChevronRight, ClipboardList, Landmark, RefreshCw, Scale, Ticket, Wallet, X } from 'lucide-vue-next'
import { accountStatuses, accountTypes, allianceAdminApi as api, discountLabel, statementStatuses, yuan } from '../services/allianceApi'
import '../platform-management.css'

const tab = ref('offers')
const accountFilter = ref('PENDING_VERIFY')
const statementFilter = ref('CONFIRMED')
const summary = ref(null)
const offers = ref([])
const disputes = ref([])
const accounts = ref([])
const statements = ref([])
const loading = ref(false)
const error = ref('')
const success = ref('')
const busyKey = ref('')
let generation = 0

const stats = computed(() => [
  { label: '已发放额度', value: summary.value && `¥${yuan(summary.value.grantedMinor)}`, helper: '会员期内可领的联盟券面值', icon: Ticket },
  { label: '已领 / 已核销面值', value: summary.value && `¥${yuan(summary.value.claimedFaceMinor)} / ¥${yuan(summary.value.redeemedFaceMinor)}`, helper: `已过期 ¥${summary.value ? yuan(summary.value.expiredFaceMinor) : '—'}`, icon: ClipboardList },
  { label: '未打款 / 已打款', value: summary.value && `¥${yuan(summary.value.holdingSettleMinor)} / ¥${yuan(summary.value.paidSettleMinor)}`, helper: '未打款含申诉期、申诉中和对账中的', icon: Wallet },
  { label: '待审供券 / 待处理申诉', value: summary.value && `${summary.value.pendingOffers} / ${summary.value.openDisputes}`, helper: '需要平台处理', icon: Scale },
])
const date = value => value ? new Date(value).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }) : '—'
const today = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10)
const lastMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}` }
const statusClass = status => ({ VERIFIED: 'mg-approved', PAID: 'mg-approved', CONFIRMED: 'mg-approved', PENDING_VERIFY: 'mg-pending_review', PENDING_CONFIRM: 'mg-pending_review', REJECTED: 'mg-rejected', OBJECTED: 'mg-rejected', VOID: 'mg-rejected' })[status] || ''

async function load() {
  const attempt = ++generation
  loading.value = true
  error.value = ''
  try {
    const [nextSummary, nextOffers, nextDisputes, nextAccounts, nextStatements] = await Promise.all([
      api.summary(), api.pendingOffers(), api.disputes(), api.accounts(accountFilter.value), api.statements({ status: statementFilter.value }),
    ])
    if (attempt !== generation) return
    summary.value = nextSummary
    offers.value = nextOffers
    disputes.value = nextDisputes
    accounts.value = nextAccounts
    statements.value = nextStatements
  } catch (failure) {
    if (attempt === generation) error.value = failure.message || '联盟券数据暂时无法读取。'
  } finally {
    if (attempt === generation) loading.value = false
  }
}

async function act(key, action, message) {
  if (busyKey.value) return
  busyKey.value = key
  error.value = ''
  success.value = ''
  try {
    const result = await action()
    success.value = typeof message === 'function' ? message(result) : message
    await load()
  } catch (failure) {
    error.value = failure.message || '操作失败，请稍后再试。'
  } finally {
    busyKey.value = ''
  }
}

function review(item, approved) {
  if (!approved) {
    const note = window.prompt('驳回原因（会展示给供券商户）')
    if (!note?.trim()) return
    return act(`offer-${item.tenantId}-${item.offer.id}`, () => api.review(item, false, note.trim()),
      `「${item.offer.productName}」已驳回，预留库存已还给商户`)
  }
  const input = window.prompt(`与「${item.supplierName}」约定的结算折扣：填 5 表示按面值 5 折结算，8.5 表示 8.5 折`)
  if (input === null) return
  const rate = Math.round(Number(input) * 1000)
  if (!Number.isFinite(rate) || rate < 1 || rate > 10000) { error.value = '结算折扣请填 0.1 到 10 之间的数字，例如 5 或 8.5。'; return }
  return act(`offer-${item.tenantId}-${item.offer.id}`, () => api.review(item, true, '', rate),
    `「${item.offer.productName}」已上架到券库，约定按 ${discountLabel(rate)}结算`)
}

function resolve(item, valid) {
  const note = window.prompt(valid ? '核销有效的说明（可不填）' : '核销无效的说明（会展示给领券商户）')
  if (note === null || (!valid && !note.trim())) return
  return act(`dispute-${item.ref}`, () => api.resolveDispute(item, valid, note.trim()),
    valid ? '已判定核销有效，结算单解冻' : '已判定核销无效，结算作废，券已恢复给领券商户')
}

function reviewAccount(item, approved) {
  const note = approved ? '' : window.prompt('驳回原因（会展示给商户）')
  if (!approved && !note?.trim()) return
  return act(`account-${item.tenantId}`, () => api.reviewAccount(item, approved, note?.trim() || ''),
    approved ? `「${item.supplierName}」的收款账户已核对通过` : `「${item.supplierName}」的收款账户已驳回`)
}

/** 查看完整账号要写用途，后端留审计；结果只弹出一次，不留在页面上。 */
async function reveal(item, statement) {
  const purpose = window.prompt('查看完整账号的用途（会记入审计）', statement ? '线下打款核对账号' : '核对收款账户')
  if (!purpose?.trim()) return
  error.value = ''
  try {
    const result = statement ? await api.revealPayee(item, purpose.trim()) : await api.revealAccount(item, purpose.trim())
    window.alert(`户名：${result.accountName}\n开户行：${result.bankName} ${result.branchName}\n账号：${result.accountNumber}`)
  } catch (failure) {
    error.value = failure.message || '账号暂时无法查看。'
  }
}

function markPaid(item) {
  const reference = window.prompt(`已向「${item.statement.payeeAccountName}」（尾号 ${item.statement.payeeAccountLast4}）线下打款 ¥${yuan(item.statement.settleTotalMinor)}？请填写打款流水号`)
  if (!reference?.trim()) return
  const paidDate = window.prompt('实际打款日期（年-月-日）', today())
  if (!paidDate?.trim()) return
  return act(`statement-${item.tenantId}-${item.statement.id}`, () => api.statementPaid(item, reference.trim(), paidDate.trim()),
    `「${item.supplierName}」${item.statement.period} 对账单已标记打款`)
}

function handleObjection(item, accept) {
  const note = window.prompt(accept ? '作废原因（会展示给商户，作废后下次生成会重新出单）' : '核实说明（会展示给商户，处理后转为已确认）')
  if (!note?.trim()) return
  return act(`statement-${item.tenantId}-${item.statement.id}`,
    () => accept ? api.voidStatement(item, note.trim()) : api.resolveStatement(item, note.trim()),
    accept ? '对账单已作废，里面的结算放回，可以重新生成' : '已核实，对账单转为已确认')
}

function generate() {
  const period = window.prompt('补生成哪个月的对账单（年-月，只能是已经结束的月份）', lastMonth())
  if (!period?.trim()) return
  return act('generate', () => api.generateStatements(period.trim()), result => `已生成 ${result.created} 张 ${period.trim()} 对账单（已有的不会重复生成）`)
}

function changeAccountFilter(value) { accountFilter.value = value; return load() }
function changeStatementFilter(value) { statementFilter.value = value; return load() }

load()
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>联盟券</span></div><h1>联盟券</h1><p>审核商户提供的券，处理核销申诉，核对收款账户，按月对账后线下打款。</p></div>
      <div class="management-heading-actions"><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新数据</button></div>
    </header>

    <div class="management-stats"><article v-for="stat in stats" :key="stat.label"><div><span>{{ stat.label }}</span><component :is="stat.icon" :size="17" /></div><strong>{{ stat.value || '—' }}</strong><p>{{ stat.helper }}</p></article></div>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section class="management-list" :aria-busy="loading" aria-label="联盟券待办">
      <div class="management-tabs">
        <button type="button" :class="{ active: tab === 'offers' }" @click="tab = 'offers'">待审核供券 <span class="mg-count">{{ offers.length }}</span></button>
        <button type="button" :class="{ active: tab === 'disputes' }" @click="tab = 'disputes'">核销申诉 <span class="mg-count">{{ disputes.length }}</span></button>
        <button type="button" :class="{ active: tab === 'accounts' }" @click="tab = 'accounts'">收款账户</button>
        <button type="button" :class="{ active: tab === 'statements' }" @click="tab = 'statements'">对账单</button>
      </div>
      <div v-if="error" class="mg-state mg-error" role="alert"><ClipboardList :size="30" /><h3>暂时无法完成</h3><p>{{ error }}</p><button class="mg-button" type="button" :disabled="loading" @click="load">重新读取</button></div>
      <div v-else-if="loading && !summary" class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取联盟券数据…</p></div>

      <template v-else-if="tab === 'offers'">
        <div v-if="!offers.length" class="mg-state"><Ticket :size="32" /><h3>没有待审核的供券单</h3><p>商户提交供券后会出现在这里。审核时看定价是否合理、数量是否合适，通过时填写约定的结算折扣。</p></div>
        <div v-else class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>商品 / 门店</th><th>供券商户</th><th>面值</th><th>数量</th><th>收款账户</th><th>提交时间</th><th class="mg-table-action">处理</th></tr></thead>
            <tbody>
              <tr v-for="item in offers" :key="`${item.tenantId}-${item.offer.id}`">
                <td><strong>{{ item.offer.productName }}</strong><small>{{ item.offer.storeName }} · {{ item.storeAddress || '未填写地址' }}</small></td>
                <td><span>{{ item.supplierName }}</span><small class="mg-mono">{{ item.supplierCreditCode }}</small></td>
                <td class="mg-mono">¥{{ yuan(item.offer.faceValueMinor) }}</td>
                <td class="mg-mono">{{ item.offer.totalQty }} {{ item.offer.saleUnit }}</td>
                <td><span class="mg-status" :class="statusClass(item.accountStatus)"><i />{{ accountStatuses[item.accountStatus] || '未填写' }}</span></td>
                <td class="mg-time">{{ date(item.offer.createdAt) }}</td>
                <td class="mg-table-action"><button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="review(item, true)">通过</button> · <button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="review(item, false)">驳回</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template v-else-if="tab === 'disputes'">
        <div v-if="!disputes.length" class="mg-state"><Scale :size="32" /><h3>没有待处理的申诉</h3><p>领券商户在核销后申诉期内提出，对应结算会冻结，处理后才能进对账单。</p></div>
        <div v-else class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>券</th><th>领券商户</th><th>核销时间</th><th>申诉理由</th><th class="mg-table-action">判定</th></tr></thead>
            <tbody>
              <tr v-for="item in disputes" :key="item.ref">
                <td><strong>{{ item.productName }}</strong><small>{{ item.storeName }} · ¥{{ yuan(item.faceValueMinor) }}</small></td>
                <td>{{ item.holderName }}</td>
                <td class="mg-time">{{ date(item.redeemedAt) }}</td>
                <td>{{ item.disputeReason }}<small>{{ date(item.disputedAt) }}</small></td>
                <td class="mg-table-action"><button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="resolve(item, true)">核销有效</button> · <button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="resolve(item, false)">核销无效</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template v-else-if="tab === 'accounts'">
        <div class="management-filters">
          <button v-for="(label, value) in accountStatuses" :key="value" class="mg-button" :class="{ 'mg-primary': accountFilter === value }" type="button" :disabled="loading" @click="changeAccountFilter(value)">{{ label }}</button>
        </div>
        <div v-if="!accounts.length" class="mg-state"><Landmark :size="32" /><h3>没有记录</h3><p>商户在「供券管理 → 收款账户」填写或修改后，会出现在待核对里。核对户名是否与入驻主体一致。</p></div>
        <div v-else class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>商户</th><th>户名 / 入驻主体</th><th>开户行</th><th>账号</th><th>财务联系人</th><th>提交时间</th><th class="mg-table-action">处理</th></tr></thead>
            <tbody>
              <tr v-for="item in accounts" :key="item.tenantId">
                <td>{{ item.supplierName }}<small>{{ accountTypes[item.account.accountType] }}</small></td>
                <td><strong>{{ item.account.accountName }}</strong><small :class="{ 'mg-error': item.account.accountType === 'CORPORATE' && item.account.accountName !== item.registeredCompanyName }">入驻主体：{{ item.registeredCompanyName || '无审核通过的入驻' }}</small></td>
                <td>{{ item.account.bankName }}<small>{{ item.account.branchName }}</small></td>
                <td class="mg-mono">尾号 {{ item.account.accountNumberLast4 }}</td>
                <td>{{ item.account.financeContactName }}<small class="mg-mono">{{ item.account.financeContactPhone }}</small></td>
                <td class="mg-time">{{ date(item.account.updatedAt) }}</td>
                <td class="mg-table-action">
                  <button class="mg-text-button" type="button" @click="reveal(item, false)">查看完整账号</button>
                  <template v-if="item.account.status === 'PENDING_VERIFY'"> · <button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="reviewAccount(item, true)">核对通过</button> · <button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="reviewAccount(item, false)">驳回</button></template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template v-else>
        <div class="management-filters">
          <button v-for="(label, value) in statementStatuses" :key="value" class="mg-button" :class="{ 'mg-primary': statementFilter === value }" type="button" :disabled="loading" @click="changeStatementFilter(value)">{{ label }}</button>
          <button class="mg-button" type="button" :disabled="Boolean(busyKey)" @click="generate">补生成某月对账单</button>
        </div>
        <div v-if="!statements.length" class="mg-state"><Wallet :size="32" /><h3>没有记录</h3><p>每月 1 日自动给每个供券商户生成上月对账单；商户确认后在「已确认，待打款」里线下打款并标记。</p></div>
        <div v-else class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>供券商户 / 月份</th><th>券数</th><th>结算金额</th><th>状态</th><th>收款账户</th><th class="mg-table-action">处理</th></tr></thead>
            <tbody>
              <tr v-for="item in statements" :key="`${item.tenantId}-${item.statement.id}`">
                <td><strong>{{ item.supplierName }}</strong><small class="mg-mono">{{ item.statement.period }}</small></td>
                <td class="mg-mono">{{ item.statement.itemCount }}</td>
                <td class="mg-mono">¥{{ yuan(item.statement.settleTotalMinor) }}<small>面值 ¥{{ yuan(item.statement.faceTotalMinor) }}</small></td>
                <td>
                  <span class="mg-status" :class="statusClass(item.statement.status)"><i />{{ statementStatuses[item.statement.status] }}</span>
                  <small v-if="item.statement.objectionNote && item.statement.status === 'OBJECTED'">异议：{{ item.statement.objectionNote }}</small>
                  <small v-else-if="item.statement.status === 'PENDING_CONFIRM'">{{ date(item.statement.confirmDueAt) }} 前未处理自动确认</small>
                  <small v-else-if="item.statement.paidReference">{{ item.statement.paidDate }} · {{ item.statement.paidReference }}</small>
                </td>
                <td><template v-if="item.statement.payeeAccountLast4">{{ item.statement.payeeAccountName }}<small>{{ item.statement.payeeBankName }} · 尾号 {{ item.statement.payeeAccountLast4 }}</small></template><small v-else>当前账户：{{ accountStatuses[item.accountStatus] || '未填写' }}</small></td>
                <td class="mg-table-action">
                  <template v-if="item.statement.status === 'CONFIRMED'"><button class="mg-text-button" type="button" @click="reveal(item, true)">查看完整账号</button> · <button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="markPaid(item)">标记已打款</button></template>
                  <template v-else-if="item.statement.status === 'OBJECTED'"><button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="handleObjection(item, true)">作废重出</button> · <button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="handleObjection(item, false)">核实无误</button></template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </section>
  </div>
</template>
