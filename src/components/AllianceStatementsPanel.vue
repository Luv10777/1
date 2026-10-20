<script setup>
import { ref } from 'vue'
import { Dialog, DialogDescription, DialogPanel, DialogTitle } from '@headlessui/vue'
import { X } from 'lucide-vue-next'
import { allianceApi, statementStatuses, yuan } from '../services/allianceApi'

const props = defineProps({ confirmDays: { type: Number, default: null } })
const statements = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const busyId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)

const date = value => value ? new Date(value).toLocaleDateString('zh-CN') : ''
const tagClass = status => ({ PENDING_CONFIRM: 'is-warning', OBJECTED: 'is-error', CONFIRMED: 'is-active', PAID: 'is-active' })[status] || ''

async function load() {
  loading.value = true
  error.value = ''
  try { statements.value = await allianceApi.statements() }
  catch (failure) { error.value = failure.message || '对账单暂时无法读取。' }
  finally { loading.value = false }
}

async function open(item) {
  detail.value = { statement: item, lines: [] }
  detailLoading.value = true
  try { detail.value = await allianceApi.statement(item.id) }
  catch (failure) { error.value = failure.message || '明细暂时无法读取。'; detail.value = null }
  finally { detailLoading.value = false }
}

async function act(item, action) {
  if (busyId.value) return
  let reason = ''
  if (action === 'confirm' && !window.confirm(`确认 ${item.period} 对账单：${item.itemCount} 张券，结算 ¥${yuan(item.settleTotalMinor)}。确认后平台按你当前已核对的收款账户打款。`)) return
  if (action === 'object') {
    reason = window.prompt('哪里不对？平台会核实处理')
    if (!reason?.trim()) return
  }
  busyId.value = item.id
  error.value = ''
  notice.value = ''
  try {
    if (action === 'confirm') await allianceApi.confirmStatement(item.id)
    else await allianceApi.objectStatement(item.id, reason.trim())
    notice.value = action === 'confirm' ? '已确认，平台会按对账单上的账户线下打款。' : '异议已提交，平台核实后会处理。'
    detail.value = null
    await load()
  } catch (failure) {
    error.value = failure.message || '操作失败，请稍后再试。'
  } finally {
    busyId.value = null
  }
}

defineExpose({ load })
load()
</script>

<template>
  <section aria-label="对账单">
    <p class="alliance-muted alliance-statements-intro">每月 1 日自动生成上个月的对账单，只包含已过申诉期、没有申诉的结算。<template v-if="props.confirmDays">生成后 {{ props.confirmDays }} 天内不处理，视为确认。</template>确认时需要收款账户已通过平台核对。</p>
    <p v-if="notice" class="alliance-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="alliance-error" role="alert">{{ error }}</p>
    <p v-if="loading && !statements.length" class="alliance-state" role="status">正在读取…</p>
    <div v-else-if="!statements.length" class="alliance-state"><h3>还没有对账单</h3><p>有结算过了申诉期后，下个月 1 日会生成对账单。</p></div>
    <div v-else class="alliance-panel alliance-table-wrap">
      <table class="alliance-table">
        <thead><tr><th>月份</th><th>券数</th><th>面值合计</th><th>结算金额</th><th>状态</th><th>收款账户</th><th>打款</th><th /></tr></thead>
        <tbody>
          <tr v-for="item in statements" :key="item.id">
            <td class="num">{{ item.period }}</td>
            <td class="num">{{ item.itemCount }}</td>
            <td class="num">¥{{ yuan(item.faceTotalMinor) }}</td>
            <td class="num"><strong>¥{{ yuan(item.settleTotalMinor) }}</strong></td>
            <td><span class="alliance-tag" :class="tagClass(item.status)">{{ statementStatuses[item.status] }}</span><p v-if="item.status === 'PENDING_CONFIRM'" class="alliance-muted">{{ date(item.confirmDueAt) }} 前未处理视为确认</p><p v-if="item.autoConfirmed" class="alliance-muted">到期自动确认</p><p v-if="item.platformNote" class="alliance-muted">平台：{{ item.platformNote }}</p></td>
            <td>{{ item.payeeAccountLast4 ? `${item.payeeAccountName} · 尾号 ${item.payeeAccountLast4}` : '确认时记录' }}</td>
            <td>{{ item.paidDate ? `${item.paidDate} · ${item.paidReference}` : '—' }}</td>
            <td>
              <div class="alliance-row-actions">
                <button type="button" class="business-link" @click="open(item)">明细</button>
                <template v-if="item.status === 'PENDING_CONFIRM'">
                  <button type="button" class="business-link" :disabled="busyId === item.id" @click="act(item, 'object')">有异议</button>
                  <button type="button" class="business-button business-button-primary" :disabled="busyId === item.id" @click="act(item, 'confirm')">确认</button>
                </template>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <Dialog :open="Boolean(detail)" class="alliance-dialog" @close="detail = null">
      <div class="alliance-dialog-backdrop" aria-hidden="true" />
      <div class="alliance-dialog-position">
        <DialogPanel class="alliance-dialog-panel">
          <header><div><DialogTitle as="h2">{{ detail?.statement.period }} 对账单</DialogTitle><DialogDescription class="alliance-muted">{{ detail?.statement.itemCount }} 张券 · 结算 ¥{{ yuan(detail?.statement.settleTotalMinor) }}</DialogDescription></div><button type="button" class="alliance-dialog-close" aria-label="关闭" @click="detail = null"><X :size="16" /></button></header>
          <p v-if="detailLoading" class="alliance-muted" role="status">正在读取明细…</p>
          <div v-else class="alliance-table-wrap">
            <table class="alliance-table">
              <thead><tr><th>商品</th><th>核销时间</th><th>面值</th><th>结算</th></tr></thead>
              <tbody><tr v-for="line in detail?.lines || []" :key="line.settlementId"><td>{{ line.productName }}</td><td>{{ date(line.redeemedAt) }}</td><td class="num">¥{{ yuan(line.faceValueMinor) }}</td><td class="num">¥{{ yuan(line.settleAmountMinor) }}</td></tr></tbody>
            </table>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  </section>
</template>
