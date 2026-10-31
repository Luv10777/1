<script setup>
import { onMounted, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, ReceiptText, RefreshCw, X } from 'lucide-vue-next'
import AppSelect from '../AppSelect.vue'
import { referralApi } from '../../services/referralApi'
import { commissionKinds, commissionStates, ratePercent } from '../../domain/referral'
import { managementDate } from '../../domain/platformManagement'
import { yuan } from '../../stores/billing'

// 返利明细：每一笔返利，可以按邀请人、状态筛选；顶部合计是筛选范围内的冻结中和可提现。提现审核中的返利要先驳回提现单才能作废。
const props = defineProps({ referrerUserId: { type: Number, default: null } })
const emit = defineEmits(['clear-referrer'])
const rows = ref([])
const total = ref(null)
const totalPages = ref(0)
const page = ref(0)
const state = ref('')
const sums = ref(null)
const loading = ref(false)
const error = ref('')
const success = ref('')
const busyId = ref(null)
let version = 0

async function load() {
  const attempt = ++version
  loading.value = true
  error.value = ''
  try {
    const result = await referralApi.commissions({ referrerUserId: props.referrerUserId, state: state.value, page: page.value, size: 20 })
    if (attempt !== version) return
    rows.value = result.page.items
    total.value = result.page.total
    totalPages.value = result.page.totalPages
    sums.value = { frozen: result.frozenMinor, available: result.availableMinor }
  } catch (failure) {
    if (attempt === version) error.value = failure.message || '返利明细暂时无法读取'
  } finally {
    if (attempt === version) loading.value = false
  }
}
function search() { page.value = 0; load() }

/** 作废（例如被邀请商户退款）：没提现的直接作废；已提现的记为欠款，从推广人下一次提现里扣回。 */
async function voidCommission(item) {
  if (busyId.value) return
  const withdrawn = item.state === 'WITHDRAWN'
  const note = window.prompt(`作废这笔 ${yuan(item.amountMinor)} 的返利？${withdrawn ? '它已经提现，作废后会从推广人下一次提现里扣回。' : ''}请写明原因（至少5字，会展示给推广人）`)
  if (!note?.trim()) return
  busyId.value = item.id
  error.value = ''
  success.value = ''
  try {
    await referralApi.voidCommission(item, note.trim())
    success.value = withdrawn ? `已作废，${yuan(item.amountMinor)} 将从推广人下一次提现里扣回` : '已作废这笔返利'
    await load()
  } catch (failure) {
    window.alert(failure.message || '作废失败，请稍后再试。')
  } finally {
    busyId.value = null
  }
}
function movePage(next) { page.value = next; load() }
watch(() => props.referrerUserId, search)
onMounted(load)
</script>

<template>
  <section class="management-list" :aria-busy="loading" aria-label="返利明细">
    <form class="management-filters" @submit.prevent="search">
      <label class="mg-select"><span class="sr-only">状态</span><AppSelect v-model="state" @change="search"><option value="">全部状态</option><option v-for="(label, value) in commissionStates" :key="value" :value="value">{{ label }}</option></AppSelect></label>
      <span v-if="referrerUserId" class="referral-chip">只看邀请人 #{{ referrerUserId }}<button type="button" aria-label="取消按邀请人筛选" @click="emit('clear-referrer')"><X :size="13" /></button></span>
      <span v-if="sums" class="referral-sums">冻结中 <strong>{{ yuan(sums.frozen) }}</strong> · 可提现 <strong>{{ yuan(sums.available) }}</strong></span>
    </form>
    <p v-if="success" class="mg-muted referral-message" role="status">{{ success }}</p>
    <div v-if="error" class="mg-state mg-error" role="alert"><ReceiptText :size="30" /><h3>暂时无法读取</h3><p>{{ error }}</p><button class="mg-button" type="button" :disabled="loading" @click="load">重新读取</button></div>
    <div v-else-if="loading && !rows.length" class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取返利明细…</p></div>
    <div v-else-if="!rows.length" class="mg-state"><ReceiptText :size="32" /><h3>还没有返利</h3><p>被邀请商户充值烟火或开通会员付款后，返利会出现在这里。</p></div>
    <div v-else class="management-table-wrap">
      <table class="management-table">
        <thead><tr><th>时间</th><th>邀请人</th><th>被邀请商户</th><th>类型</th><th class="referral-money">实付</th><th>比例</th><th class="referral-money">返利</th><th>状态</th><th class="mg-table-action">处理</th></tr></thead>
        <tbody>
          <tr v-for="item in rows" :key="item.id">
            <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
            <td><span>{{ item.referrerName || `用户 #${item.referrerUserId}` }}</span><small>{{ item.referrerPhoneMasked }}</small></td>
            <td><span>{{ item.inviteeTenantName || `商户 #${item.inviteeTenantId}` }}</span><small>{{ item.inviteePhoneMasked }}</small></td>
            <td>{{ commissionKinds[item.kind] || item.kind }}</td>
            <td class="referral-money">{{ yuan(item.paidMinor) }}</td>
            <td>{{ ratePercent(item.rateBp) }}</td>
            <td class="referral-money"><strong>{{ yuan(item.amountMinor) }}</strong></td>
            <td><span class="mg-status"><i />{{ commissionStates[item.state] || item.state }}</span><small v-if="item.state === 'FROZEN'">{{ managementDate(item.availableAt) }} 后可提现</small><small v-else-if="item.state === 'VOIDED' && item.voidNote">{{ item.voidNote }}</small></td>
            <td class="mg-table-action"><button v-if="item.state !== 'VOIDED' && item.state !== 'WITHDRAWING'" class="mg-text-button" type="button" :disabled="Boolean(busyId)" @click="voidCommission(item)">作废</button><span v-else class="mg-muted">—</span></td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer v-if="!error" class="management-pagination"><span>{{ total == null ? '正在读取' : `共 ${total} 笔返利` }}</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ totalPages ? page + 1 : 0 }} / {{ totalPages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= totalPages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
  </section>
</template>

<style scoped>
.referral-money { text-align: right; white-space: nowrap; }
.referral-chip { display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 10px; border: 1px solid var(--mg-line); border-radius: 5px; font-size: 12px; }
.referral-chip button { display: grid; place-items: center; padding: 0; border: 0; background: none; color: var(--mg-muted); cursor: pointer; }
.referral-sums { margin-left: auto; align-self: center; color: var(--mg-muted); font-size: 12px; }
.referral-sums strong { color: var(--mg-ink); }
.referral-message { margin: 0; padding: 10px 20px 0; }
</style>
