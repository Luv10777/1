<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { backofficeApi } from '../../src/services/backofficeApi.js'
import { money, moneyMinor, orderKinds, orderStates, paymentMethods } from '../../src/domain/backoffice.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import OperationsDialog from '../../src/components/OperationsDialog.vue'
import '../../src/platform-management.css'
import '../../src/backoffice.css'
const filters = reactive({ days: '30', state: '', kind: '', paymentMethod: '', keyword: '' })
const report = ref(null)
const page = ref(0)
const loading = ref(false)
const error = ref('')
const selected = ref(null)
const busy = ref(false)
const actionError = ref('')
const notice = ref('')
const receipt = reactive({ amount: '', reference: '' })
const pages = computed(() => Math.ceil((report.value?.page.total || 0) / 20))
let generation = 0
async function load() {
  const current = ++generation; loading.value = true; error.value = ''
  try { const data = await backofficeApi.orders({ ...filters, page: page.value, size: 20 }); if (current === generation) report.value = data }
  catch (failure) { if (current === generation) { report.value = null; error.value = failure.message || '订单数据读取失败' } }
  finally { if (current === generation) loading.value = false }
}
function search() { page.value = 0; load() }
function move(next) { page.value = next; load() }
function open(item) { selected.value = item; actionError.value = ''; notice.value = ''; Object.assign(receipt, { amount: (item.amountMinor / 100).toFixed(2), reference: '' }) }
async function confirm() {
  const receivedAmountMinor = moneyMinor(receipt.amount)
  if (receivedAmountMinor !== selected.value.amountMinor) { actionError.value = '实际到账金额须与订单金额一致'; return }
  busy.value = true; actionError.value = ''; notice.value = ''
  try {
    const data = await backofficeApi.confirmOrder(selected.value.tenantId, selected.value.id, { receivedAmountMinor, receiptReference: receipt.reference.trim() })
    selected.value = { ...selected.value, state: data.state, paidAt: data.paidAt, confirmedBy: data.confirmedBy, receiptReference: receipt.reference.trim() }
    notice.value = '收款已核销，订单权益已入账'; await load()
  } catch (failure) { actionError.value = failure.message || '收款核销失败' }
  finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <div class="management-page">
    <header class="bo-heading"><div><h1>订单对账</h1><p>统一查询会员、充值和激活码购买订单，按实际支付状态核对入账</p></div><button class="mg-button" :disabled="loading" @click="load">刷新</button></header>
    <div v-if="report" class="bo-metrics"><div><span>已支付金额</span><strong>{{ money(report.paidMinor) }}</strong></div><div><span>待支付金额</span><strong>{{ money(report.pendingMinor) }}</strong></div><div><span>已支付订单</span><strong>{{ report.paidCount }}</strong></div><div><span>待支付订单</span><strong>{{ report.pendingCount }}</strong></div></div>
    <form class="management-filters" @submit.prevent="search"><label class="bo-field">创建时间<select v-model="filters.days" @change="search"><option value="7">近 7 天</option><option value="30">近 30 天</option><option value="90">近 90 天</option><option value="365">近一年</option></select></label><label class="bo-field">订单状态<select v-model="filters.state" @change="search"><option value="">全部状态</option><option v-for="(label, key) in orderStates" :key="key" :value="key">{{ label }}</option></select></label><label class="bo-field">订单类型<select v-model="filters.kind" @change="search"><option value="">全部类型</option><option v-for="(label, key) in orderKinds" :key="key" :value="key">{{ label }}</option></select></label><label class="bo-field">支付方式<select v-model="filters.paymentMethod" @change="search"><option value="">全部方式</option><option v-for="(label, key) in paymentMethods" :key="key" :value="key">{{ label }}</option></select></label><label class="bo-field">搜索<input v-model="filters.keyword" maxlength="160" placeholder="商户名称、订单号或收款凭证" /></label><button class="mg-button" type="submit" :disabled="loading">查询</button></form>
    <p v-if="loading" class="mg-state" role="status">正在查询订单…</p><p v-if="error" class="mg-state mg-error" role="alert">{{ error }}</p>
    <div v-if="report" class="management-table-wrap"><table class="management-table"><thead><tr><th>商户</th><th>订单号 / 类型</th><th>金额</th><th>支付方式</th><th>状态</th><th>创建 / 支付时间</th><th>操作</th></tr></thead><tbody><tr v-for="item in report.page.items" :key="item.id"><td>{{ item.tenantName }}<small>商户 #{{ item.tenantId }}</small></td><td class="mg-mono">{{ item.id }}<small>{{ orderKinds[item.kind] }}{{ item.planName ? ` · ${item.planName}` : '' }}</small></td><td class="mg-mono">{{ money(item.amountMinor) }}</td><td>{{ paymentMethods[item.paymentMethod] || item.paymentMethod }}</td><td><span class="bo-status" :class="item.state">{{ orderStates[item.state] || item.state }}</span></td><td>{{ managementDate(item.createdAt) }}<small>{{ item.paidAt ? managementDate(item.paidAt) : '尚未支付' }}</small></td><td><button class="mg-text-button" @click="open(item)">查看 / 核对</button></td></tr></tbody></table><p v-if="!report.page.items.length && !loading" class="mg-state">没有匹配的订单</p></div>
    <footer v-if="report" class="management-pagination"><span>共 {{ report.page.total }} 笔</span><div><button class="mg-button" :disabled="!page || loading" @click="move(page - 1)">上一页</button><span>{{ page + 1 }} / {{ Math.max(1, pages) }}</span><button class="mg-button" :disabled="page + 1 >= pages || loading" @click="move(page + 1)">下一页</button></div></footer>
    <OperationsDialog :open="Boolean(selected)" title="订单与收款核对" :busy="busy" @close="selected = null">
      <template v-if="selected">
        <dl class="mg-detail-grid"><div><dt>商户</dt><dd>{{ selected.tenantName }}</dd></div><div><dt>订单类型</dt><dd>{{ orderKinds[selected.kind] }}</dd></div><div class="bo-wide"><dt>订单号</dt><dd class="mg-mono">{{ selected.id }}</dd></div><div><dt>订单金额</dt><dd>{{ money(selected.amountMinor) }}</dd></div><div><dt>订单状态</dt><dd>{{ orderStates[selected.state] }}</dd></div><div><dt>支付时间</dt><dd>{{ selected.paidAt ? managementDate(selected.paidAt) : '尚未支付' }}</dd></div><div><dt>有效期截止</dt><dd>{{ managementDate(selected.expiresAt) }}</dd></div><div><dt>收款凭证</dt><dd>{{ selected.receiptReference || '未记录' }}</dd></div><div><dt>核销人</dt><dd>{{ selected.confirmedBy ? `用户 #${selected.confirmedBy}` : selected.state === 'PAID' ? '支付渠道自动核验' : '尚未核销' }}</dd></div></dl>
        <p v-if="selected.paymentMethod !== 'MANUAL'" class="bo-muted">在线支付结果由支付渠道验证后自动入账。{{ selected.reconciliationAfter && selected.state !== 'PAID' ? `下次核验时间：${managementDate(selected.reconciliationAfter)}` : '' }}</p>
        <p v-if="actionError" class="mg-feedback mg-error" role="alert">{{ actionError }}</p><p v-if="notice" class="mg-feedback success" role="status">{{ notice }}</p>
        <form v-if="selected.paymentMethod === 'MANUAL' && selected.state === 'PENDING_PAYMENT'" class="bo-form" @submit.prevent="confirm"><h3 class="bo-wide">核销线下收款</h3><label class="bo-field">实际到账金额（元）<input v-model="receipt.amount" inputmode="decimal" required :disabled="busy" /></label><label class="bo-field">收款凭证编号<input v-model="receipt.reference" required minlength="6" maxlength="150" :disabled="busy" /></label><p class="bo-muted bo-wide">确认实际到账后提交。核销成功会将本订单的会员、烟火或激活码权益入账。</p><div class="bo-actions bo-wide"><button type="submit" class="mg-button mg-primary" :disabled="busy">{{ busy ? '正在核销…' : '确认到账并核销' }}</button></div></form>
      </template>
    </OperationsDialog>
  </div>
</template>
