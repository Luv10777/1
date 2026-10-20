<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { Check, ChevronRight, ClipboardList, RefreshCw, Scale, Ticket, Wallet, X } from 'lucide-vue-next'
import { allianceAdminApi as api, discountLabel, settlementStates, yuan } from '../services/allianceApi'
import '../platform-management.css'

const tab = ref('offers')
const settlementFilter = ref('HOLDING')
const summary = ref(null)
const offers = ref([])
const disputes = ref([])
const settlements = ref([])
const loading = ref(false)
const error = ref('')
const success = ref('')
const busyKey = ref('')
let generation = 0

const stats = computed(() => [
  { label: '已发放额度', value: summary.value && `¥${yuan(summary.value.grantedMinor)}`, helper: '会员期内可领的联盟券面值', icon: Ticket },
  { label: '已领 / 已核销面值', value: summary.value && `¥${yuan(summary.value.claimedFaceMinor)} / ¥${yuan(summary.value.redeemedFaceMinor)}`, helper: `已过期 ¥${summary.value ? yuan(summary.value.expiredFaceMinor) : '—'}`, icon: ClipboardList },
  { label: '待结算 / 已结算', value: summary.value && `¥${yuan(summary.value.holdingSettleMinor)} / ¥${yuan(summary.value.paidSettleMinor)}`, helper: '待结算含申诉期和申诉中的', icon: Wallet },
  { label: '待审供券 / 待处理申诉', value: summary.value && `${summary.value.pendingOffers} / ${summary.value.openDisputes}`, helper: '需要平台处理', icon: Scale },
])
const date = value => value ? new Date(value).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }) : '—'

async function load() {
  const attempt = ++generation
  loading.value = true
  error.value = ''
  try {
    const [nextSummary, nextOffers, nextDisputes, nextSettlements] = await Promise.all([
      api.summary(), api.pendingOffers(), api.disputes(), api.settlements(settlementFilter.value),
    ])
    if (attempt !== generation) return
    summary.value = nextSummary
    offers.value = nextOffers
    disputes.value = nextDisputes
    settlements.value = nextSettlements
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
    await action()
    success.value = message
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

function markPaid(item) {
  const reference = window.prompt(`已向「${item.supplierName}」线下打款 ¥${yuan(item.settlement.settleAmountMinor)}？请填写打款流水号`)
  if (!reference?.trim()) return
  return act(`settlement-${item.tenantId}-${item.settlement.id}`, () => api.markPaid(item, reference.trim()), '已标记为已结算')
}

function changeFilter(value) { settlementFilter.value = value; return load() }

load()
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>联盟券</span></div><h1>联盟券</h1><p>审核商户提供的券，处理核销申诉，按月线下结算给供券商户。</p></div>
      <div class="management-heading-actions"><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新数据</button></div>
    </header>

    <div class="management-stats"><article v-for="stat in stats" :key="stat.label"><div><span>{{ stat.label }}</span><component :is="stat.icon" :size="17" /></div><strong>{{ stat.value || '—' }}</strong><p>{{ stat.helper }}</p></article></div>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section class="management-list" :aria-busy="loading" aria-label="联盟券待办">
      <div class="management-tabs">
        <button type="button" :class="{ active: tab === 'offers' }" @click="tab = 'offers'">待审核供券 <span class="mg-count">{{ offers.length }}</span></button>
        <button type="button" :class="{ active: tab === 'disputes' }" @click="tab = 'disputes'">核销申诉 <span class="mg-count">{{ disputes.length }}</span></button>
        <button type="button" :class="{ active: tab === 'settlements' }" @click="tab = 'settlements'">结算</button>
      </div>
      <div v-if="error" class="mg-state mg-error" role="alert"><ClipboardList :size="30" /><h3>暂时无法完成</h3><p>{{ error }}</p><button class="mg-button" type="button" :disabled="loading" @click="load">重新读取</button></div>
      <div v-else-if="loading && !summary" class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取联盟券数据…</p></div>

      <template v-else-if="tab === 'offers'">
        <div v-if="!offers.length" class="mg-state"><Ticket :size="32" /><h3>没有待审核的供券单</h3><p>商户提交供券后会出现在这里。审核时看定价是否合理、数量是否合适。</p></div>
        <div v-else class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>商品 / 门店</th><th>供券商户</th><th>面值</th><th>数量</th><th>提交时间</th><th class="mg-table-action">处理</th></tr></thead>
            <tbody>
              <tr v-for="item in offers" :key="`${item.tenantId}-${item.offer.id}`">
                <td><strong>{{ item.offer.productName }}</strong><small>{{ item.offer.storeName }} · {{ item.storeAddress || '未填写地址' }}</small></td>
                <td><span>{{ item.supplierName }}</span><small class="mg-mono">{{ item.supplierCreditCode }}</small></td>
                <td class="mg-mono">¥{{ yuan(item.offer.faceValueMinor) }}</td>
                <td class="mg-mono">{{ item.offer.totalQty }} {{ item.offer.saleUnit }}</td>
                <td class="mg-time">{{ date(item.offer.createdAt) }}</td>
                <td class="mg-table-action"><button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="review(item, true)">通过</button> · <button class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="review(item, false)">驳回</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template v-else-if="tab === 'disputes'">
        <div v-if="!disputes.length" class="mg-state"><Scale :size="32" /><h3>没有待处理的申诉</h3><p>领券商户在核销后 7 天内申诉，对应结算单会冻结，处理后才能结算。</p></div>
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

      <template v-else>
        <div class="management-filters">
          <button v-for="value in ['HOLDING', 'PAID', 'VOID']" :key="value" class="mg-button" :class="{ 'mg-primary': settlementFilter === value }" type="button" :disabled="loading" @click="changeFilter(value)">{{ { HOLDING: '未结算', PAID: '已结算', VOID: '已作废' }[value] }}</button>
        </div>
        <div v-if="!settlements.length" class="mg-state"><Wallet :size="32" /><h3>没有记录</h3><p>门店核销后生成结算单，申诉期满、无申诉的才能标记已结算。</p></div>
        <div v-else class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>供券商户</th><th>商品</th><th>结算金额</th><th>状态</th><th>可结算时间</th><th class="mg-table-action">处理</th></tr></thead>
            <tbody>
              <tr v-for="item in settlements" :key="`${item.tenantId}-${item.settlement.id}`">
                <td>{{ item.supplierName }}</td>
                <td><span>{{ item.settlement.productName }}</span><small>面值 ¥{{ yuan(item.settlement.faceValueMinor) }}</small></td>
                <td class="mg-mono">¥{{ yuan(item.settlement.settleAmountMinor) }}</td>
                <td><span class="mg-status" :class="{ 'mg-approved': ['PAYABLE', 'PAID'].includes(item.settlement.state), 'mg-pending_review': item.settlement.state === 'IN_DISPUTE_WINDOW', 'mg-rejected': ['FROZEN', 'VOID'].includes(item.settlement.state) }"><i />{{ settlementStates[item.settlement.state] }}</span></td>
                <td class="mg-time">{{ item.settlement.paidAt ? `已结 ${date(item.settlement.paidAt)}` : date(item.settlement.payableAfter) }}<small v-if="item.settlement.paidReference">{{ item.settlement.paidReference }}</small></td>
                <td class="mg-table-action"><button v-if="item.settlement.state === 'PAYABLE'" class="mg-text-button" type="button" :disabled="Boolean(busyKey)" @click="markPaid(item)">标记已结算</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </section>
  </div>
</template>
