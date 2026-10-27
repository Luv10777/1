<script setup>
import AppSelect from '../components/AppSelect.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, Flame, Gift, RefreshCw, Search, Undo2, X, ClipboardList, CalendarClock } from 'lucide-vue-next'
import { ledgerAdminApi as api, ledgerCapabilities, ledgerChange, ledgerRanges, ledgerTypes } from '../services/ledgerAdminApi'
import { auth } from '../stores/auth'
import { managementDate } from '../domain/platformManagement'
import { taskElapsed } from '../domain/taskUsage'
import '../platform-management.css'

const records = ref([])
const total = ref(null)
const totalPages = ref(0)
const page = ref(0)
const size = ref(20)
const days = ref(7)
const type = ref('')
const capability = ref('')
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const summary = ref(null)
const summaryError = ref('')
let version = 0

const number = value => value == null ? '—' : Number(value).toLocaleString('zh-CN')
const stats = computed(() => [
  { label: `${ledgerRanges[days.value]}消耗`, value: summary.value?.consumed, helper: '实际扣除的烟火，预占不计', icon: Flame },
  { label: '今日消耗', value: summary.value?.consumedToday, helper: '北京时间今天零点起', icon: CalendarClock },
  { label: `${ledgerRanges[days.value]}退回`, value: summary.value?.refunded, helper: '失败、取消或未用完退回', icon: Undo2 },
  { label: `${ledgerRanges[days.value]}充值 / 赠送`, value: summary.value ? `${number(summary.value.purchased)} / ${number(summary.value.granted)}` : null, helper: '充值购买与会员、平台赠送', icon: Gift },
])
const breakdown = computed(() => Object.entries(summary.value?.consumedByCapability || {}).map(([key, value]) => ({ label: ledgerCapabilities[key] || key, value })))

async function load() {
  const attempt = ++version
  loading.value = true
  error.value = ''
  summaryError.value = ''
  const [listResult, summaryResult] = await Promise.allSettled([
    api.entries({ page: page.value, size: size.value, days: days.value, type: type.value, capability: capability.value, keyword: keyword.value.trim() }),
    api.summary(days.value),
  ])
  if (attempt !== version) return
  if (listResult.status === 'fulfilled') {
    records.value = listResult.value.items
    total.value = listResult.value.total
    totalPages.value = listResult.value.totalPages
  } else {
    records.value = []
    total.value = null
    error.value = listResult.reason?.message || '烟火流水读取失败，请重试。'
  }
  if (summaryResult.status === 'fulfilled') summary.value = summaryResult.value
  else { summary.value = null; summaryError.value = '合计暂时无法读取。' }
  loading.value = false
}
function search() { page.value = 0; load() }
function movePage(next) { page.value = next; load() }

watch(() => auth.user?.id, () => {
  version++
  page.value = 0; records.value = []; summary.value = null; total.value = null
  load()
}, { immediate: true })
onBeforeUnmount(() => { version++ })
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>烟火日志</span></div><h1>烟火日志</h1><p>全平台每一笔烟火的预占、扣除、退回与充值，按时间倒序。</p></div>
      <div class="management-heading-actions"><span class="management-scope"><span />计费运营</span><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新数据</button></div>
    </header>

    <div class="management-stats"><article v-for="stat in stats" :key="stat.label"><div><span>{{ stat.label }}</span><component :is="stat.icon" :size="17" /></div><strong>{{ typeof stat.value === 'string' ? stat.value : number(stat.value) }}</strong><p>{{ stat.helper }}</p></article></div>
    <p v-if="summaryError" class="mg-muted" role="status">{{ summaryError }}</p>
    <p v-else-if="breakdown.length" class="mg-muted credit-breakdown">按功能消耗：<span v-for="item in breakdown" :key="item.label">{{ item.label }} {{ number(item.value) }}</span></p>

    <section class="management-list" :aria-busy="loading" aria-label="烟火流水">
      <div class="mg-list-caption"><h2>烟火流水</h2><span>每个商户在所选时间内最多显示最近 2000 条</span></div>
      <form class="management-filters" @submit.prevent="search">
        <label class="mg-search"><Search :size="17" /><input v-model="keyword" placeholder="搜索商户名称或编号" aria-label="搜索商户" maxlength="100" /><button v-if="keyword" type="button" aria-label="清空搜索" @click="keyword = ''; search()"><X :size="14" /></button></label>
        <label class="mg-select"><span class="sr-only">时间范围</span><AppSelect v-model.number="days" @change="search"><option v-for="(label, value) in ledgerRanges" :key="value" :value="Number(value)">{{ label }}</option></AppSelect></label>
        <label class="mg-select"><span class="sr-only">类型</span><AppSelect v-model="type"><option value="">全部类型</option><option v-for="(label, value) in ledgerTypes" :key="value" :value="value">{{ label }}</option></AppSelect></label>
        <label class="mg-select"><span class="sr-only">功能</span><AppSelect v-model="capability"><option value="">全部功能</option><option v-for="(label, value) in ledgerCapabilities" :key="value" :value="value">{{ label }}</option></AppSelect></label>
        <button class="mg-button mg-primary" type="submit" :disabled="loading">查询</button>
      </form>
      <div v-if="error" class="mg-state mg-error" role="alert"><ClipboardList :size="30" /><h3>暂时无法读取数据</h3><p>{{ error }}</p><button class="mg-button" type="button" :disabled="loading" @click="load">重新读取</button></div>
      <div v-else-if="loading" class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在读取烟火流水…</p></div>
      <div v-else-if="!records.length" class="mg-state"><Flame :size="32" /><h3>{{ keyword || type || capability ? '没有匹配的流水' : '这段时间还没有烟火流水' }}</h3><p>{{ keyword || type || capability ? '调整关键词或筛选条件后重新查询。' : '商家充值、生成内容时，每一笔变动都会记录在这里。' }}</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>时间</th><th>商户</th><th>功能 / 说明</th><th>模型</th><th title="图片优先显示提交至首次加载图片的时间；其他任务及未记录图片显示时间的历史任务采用任务总耗时">耗时</th><th>类型</th><th class="credit-number">烟火变动</th><th class="credit-number">可用余额</th></tr></thead>
          <tbody>
            <tr v-for="item in records" :key="`${item.tenantId}-${item.id}`">
              <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
              <td><span>{{ item.tenantName || '—' }}</span><small>商户 #{{ item.tenantId }}{{ item.actorName ? ` · ${item.actorName}` : '' }}</small></td>
              <td><span>{{ ledgerCapabilities[item.capability] || (item.type === 'PURCHASE' || item.type === 'GRANT' ? '账户' : '—') }}</span><small>{{ item.description }}</small></td>
              <td class="credit-model">{{ item.model || '—' }}</td>
              <td class="credit-elapsed">{{ taskElapsed(item) }}</td>
              <td><span class="mg-status" :class="`credit-${item.type.toLowerCase()}`"><i />{{ ledgerTypes[item.type] || item.type }}</span></td>
              <td class="credit-number"><strong :class="`credit-${ledgerChange(item).tone}`">{{ ledgerChange(item).text }}</strong></td>
              <td class="credit-number">{{ number(item.availableAfter) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="!error" class="management-pagination"><span>{{ total == null ? '正在读取记录' : `共 ${total.toLocaleString('zh-CN')} 条流水` }}</span><div><label><span class="sr-only">每页条数</span><AppSelect v-model.number="size" :disabled="loading" aria-label="每页条数" @change="search"><option :value="20">20 条 / 页</option><option :value="50">50 条 / 页</option></AppSelect></label><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ totalPages ? page + 1 : 0 }} / {{ totalPages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= totalPages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>
    <p class="management-footnote">扣除表示生成交付后实际消耗的烟火；预占在提交时冻结，交付后转为扣除，失败或取消时退回。图片耗时优先采用首次加载图片的时间，包含排队；无显示记录时采用任务耗时。</p>
  </div>
</template>

<style scoped>
.credit-breakdown { display: flex; flex-wrap: wrap; gap: 6px 16px; }
.credit-number { text-align: right; font-variant-numeric: tabular-nums; }
.credit-model, .credit-elapsed { white-space: nowrap; }
.credit-elapsed { font-variant-numeric: tabular-nums; }
.credit-out { color: #ac4949; font-weight: 500; }
.credit-hold { color: #8a6d1f; font-weight: 500; }
.credit-in { color: #31714f; font-weight: 500; }
.mg-status.credit-capture { color: #ac4949; background: #fcf0f0; }
.mg-status.credit-reserve { color: #8a6d1f; background: #fbf5e6; }
.mg-status.credit-release, .mg-status.credit-purchase, .mg-status.credit-grant { color: #31714f; background: #eff7f1; }
</style>
