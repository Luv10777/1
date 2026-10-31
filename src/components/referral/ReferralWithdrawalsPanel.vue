<script setup>
import { onMounted, ref } from 'vue'
import { ChevronLeft, ChevronRight, RefreshCw, Wallet } from 'lucide-vue-next'
import AppSelect from '../AppSelect.vue'
import { referralApi } from '../../services/referralApi'
import { parseYuan, withdrawalStatuses } from '../../domain/referral'
import { managementDate } from '../../domain/platformManagement'
import { yuan } from '../../stores/billing'

// 提现审核：推广人申请后，核对收款信息、用公司网银转到本人银行卡，再登记代扣税额和转账流水；不符合的驳回，返利回到可提现。
const rows = ref([])
const total = ref(null)
const totalPages = ref(0)
const page = ref(0)
const status = ref('PENDING')
const sums = ref(null)
const loading = ref(false)
const error = ref('')
const success = ref('')
const busyKey = ref('')
let version = 0

async function load() {
  const attempt = ++version
  loading.value = true
  error.value = ''
  try {
    const result = await referralApi.withdrawals({ status: status.value, page: page.value, size: 20 })
    if (attempt !== version) return
    rows.value = result.page.items
    total.value = result.page.total
    totalPages.value = result.page.totalPages
    sums.value = { count: result.pendingCount, amount: result.pendingMinor }
  } catch (failure) {
    if (attempt === version) error.value = failure.message || '提现单暂时无法读取'
  } finally {
    if (attempt === version) loading.value = false
  }
}
function search() { page.value = 0; load() }
function movePage(next) { page.value = next; load() }

async function act(item, action, message) {
  if (busyKey.value) return
  busyKey.value = `${item.tenantId}-${item.id}`
  error.value = ''
  success.value = ''
  try {
    await action()
    success.value = message
    await load()
  } catch (failure) {
    error.value = failure.message || '操作失败，请稍后再试。'
  } finally {
    busyKey.value = ''
  }
}

const today = () => new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 10)
const who = item => item.userName || `用户 #${item.userId}`

/** 查看完整卡号要写用途，后端留审计；结果只弹出一次，不留在页面上。 */
async function reveal(item) {
  const purpose = window.prompt('查看完整收款信息的用途（会记入审计）', '网银转账核对收款信息')
  if (!purpose?.trim()) return
  error.value = ''
  try {
    const result = await referralApi.revealPayee(item, purpose.trim())
    window.alert(`户名：${result.holderName}\n开户行：${result.bankName}\n卡号：${result.cardNumber}\n提现金额：${yuan(item.amountMinor)}（实发 = 提现金额 - 代扣税额）`)
  } catch (failure) {
    error.value = failure.message || '收款信息暂时无法查看。'
  }
}

function reject(item) {
  const note = window.prompt(`驳回「${who(item)}」的提现 ${yuan(item.amountMinor)}？请写明原因（会展示给推广人，返利回到可提现）`)
  if (!note?.trim()) return
  return act(item, () => referralApi.rejectWithdrawal(item, note.trim()), `已驳回「${who(item)}」的提现，返利回到可提现`)
}

function markPaid(item) {
  const taxInput = window.prompt(`已向「${who(item)}」尾号 ${item.cardLast4} 转账？提现金额 ${yuan(item.amountMinor)}。请填写代扣的个人所得税（元，没有代扣填 0）`, '0')
  if (taxInput === null) return
  const tax = parseYuan(taxInput)
  if (tax.error) { error.value = tax.error; return }
  if (tax.minor > item.amountMinor) { error.value = '代扣税额不能超过提现金额。'; return }
  const reference = window.prompt(`实发 ${yuan(item.amountMinor - tax.minor)}。请填写网银转账流水号`)
  if (!reference?.trim()) return
  const paidDate = window.prompt('实际打款日期（年-月-日）', today())
  if (!paidDate?.trim()) return
  return act(item, () => referralApi.payWithdrawal(item, { taxMinor: tax.minor, reference: reference.trim(), paidDate: paidDate.trim() }),
    `「${who(item)}」的提现已登记打款，实发 ${yuan(item.amountMinor - tax.minor)}`)
}

onMounted(load)
</script>

<template>
  <section class="management-list" :aria-busy="loading" aria-label="提现审核">
    <form class="management-filters" @submit.prevent="search">
      <label class="mg-select"><span class="sr-only">状态</span><AppSelect v-model="status" @change="search"><option value="">全部状态</option><option v-for="(label, value) in withdrawalStatuses" :key="value" :value="value">{{ label }}</option></AppSelect></label>
      <span v-if="sums" class="referral-sums">待审核 <strong>{{ sums.count }}</strong> 笔 · <strong>{{ yuan(sums.amount) }}</strong></span>
    </form>
    <p v-if="success" class="mg-muted referral-message" role="status">{{ success }}</p>
    <p v-if="error && rows.length" class="mg-inline-error referral-message" role="alert">{{ error }}</p>
    <div v-if="error && !rows.length" class="mg-state mg-error" role="alert"><Wallet :size="30" /><h3>暂时无法读取</h3><p>{{ error }}</p><button class="mg-button" type="button" :disabled="loading" @click="load">重新读取</button></div>
    <div v-else-if="loading && !rows.length" class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取提现单…</p></div>
    <div v-else-if="!rows.length" class="mg-state"><Wallet :size="32" /><h3>{{ status === 'PENDING' ? '没有待审核的提现' : '还没有提现单' }}</h3><p>推广人申请提现后会出现在这里。</p></div>
    <div v-else class="management-table-wrap">
      <table class="management-table">
        <thead><tr><th>申请时间</th><th>推广人</th><th class="referral-money">提现金额</th><th>收款卡</th><th>状态</th><th class="referral-money">代扣税 / 实发</th><th>说明</th><th class="mg-table-action">处理</th></tr></thead>
        <tbody>
          <tr v-for="item in rows" :key="`${item.tenantId}-${item.id}`">
            <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
            <td><span>{{ who(item) }}</span><small>{{ item.phoneMasked }} · {{ item.tenantName || `商户 #${item.tenantId}` }}</small></td>
            <td class="referral-money"><strong>{{ yuan(item.amountMinor) }}</strong><small v-if="item.clawbackMinor">返利 {{ yuan(item.commissionMinor) }}，扣回 {{ yuan(item.clawbackMinor) }}</small></td>
            <td>{{ item.bankName }}<small>尾号 {{ item.cardLast4 }}</small></td>
            <td><span class="mg-status"><i />{{ withdrawalStatuses[item.status] || item.status }}</span></td>
            <td class="referral-money">{{ item.taxMinor == null ? '—' : `${yuan(item.taxMinor)} / ${yuan(item.netMinor)}` }}</td>
            <td>{{ item.status === 'PAID' ? `${item.paidDate} · ${item.paidReference}` : item.reviewNote || '—' }}</td>
            <td class="mg-table-action">
              <template v-if="item.status === 'PENDING'"><button class="mg-text-button" type="button" @click="reveal(item)">查看收款信息</button> · <button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="markPaid(item)">登记打款</button> · <button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="reject(item)">驳回</button></template>
              <span v-else class="mg-muted">—</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer v-if="!error || rows.length" class="management-pagination"><span>{{ total == null ? '正在读取' : `共 ${total} 张提现单` }}</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ totalPages ? page + 1 : 0 }} / {{ totalPages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= totalPages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
  </section>
</template>

<style scoped>
.referral-money { text-align: right; white-space: nowrap; }
.referral-sums { margin-left: auto; align-self: center; color: var(--mg-muted); font-size: 12px; }
.referral-sums strong { color: var(--mg-ink); }
.referral-message { margin: 0; padding: 10px 20px 0; }
</style>
