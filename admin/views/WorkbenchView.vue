<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowRight, Inbox, RefreshCw } from 'lucide-vue-next'
import { platformManagementApi } from '../../src/services/platformManagement.js'
import { ledgerAdminApi, ledgerCapabilities } from '../../src/services/ledgerAdminApi.js'
import { capabilityShares, creditMetrics, creditRanges, DAILY_DAYS, periodStats, windowRows } from '../../src/domain/creditStats.js'
import { pendingTotal } from '../pendingReviews.js'
import DeltaBadge from '../DeltaBadge.vue'

const loading = ref(false)
const platformSummary = ref(null)
const userSummary = ref(null)
const summaryError = ref('')
const daily = ref([])
const ledgerError = ref('')
const rangeKey = ref('7')
const metricKey = ref('consumed')
const now = ref(Date.now())
let generation = 0

const greeting = computed(() => {
  const hour = new Date(now.value).getHours()
  return hour < 11 ? '早上好' : hour < 13 ? '中午好' : hour < 18 ? '下午好' : '晚上好'
})
const dateLabel = computed(() => new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date(now.value)))
const kpis = computed(() => {
  const users = userSummary.value
  return [
    { label: '用户总数', value: users?.users, previous: users?.usersWeekAgo, compare: '比 7 天前' },
    { label: '近 7 天登录', value: users?.activeLast7Days, previous: users?.activePrevious7Days, compare: '比上个 7 天', pending: users && users.activePrevious7Days == null ? '登录记录积累中，满 14 天后显示涨幅' : '' },
    { label: '近 7 天新增', value: users?.newLast7Days, previous: users?.newPrevious7Days, compare: '比上个 7 天' },
    { label: '已入驻商家', value: platformSummary.value?.approvedMerchants, helper: '入驻审核通过' },
    { label: '在线信息', value: platformSummary.value?.publishedInformation, helper: '已发布且在有效期内' },
  ]
})
const range = computed(() => creditRanges.find(item => item.key === rangeKey.value))
const stats = computed(() => periodStats(daily.value, range.value.days))
const metric = computed(() => creditMetrics.find(item => item.key === metricKey.value))
const rows = computed(() => windowRows(daily.value, range.value.days))
const firstCurrent = computed(() => rows.value.length - Math.min(range.value.days, rows.value.length))
const chartMax = computed(() => Math.max(1, ...rows.value.map(row => Number(row[metricKey.value] || 0))))
const tableRows = computed(() => rows.value.map((row, index) => ({ ...row, current: index >= firstCurrent.value })).reverse())
const shares = computed(() => capabilityShares(stats.value.current, stats.value.previous))
const shareTotal = computed(() => shares.value.reduce((sum, item) => sum + item.amount, 0))
const number = value => value == null ? '—' : Number(value).toLocaleString('zh-CN')
const shortDate = value => { const [, month, day] = String(value).split('-'); return `${Number(month)}/${Number(day)}` }
const labelEvery = computed(() => rows.value.length > 45 ? 15 : rows.value.length > 14 ? 5 : 1)

async function load() {
  const attempt = ++generation
  loading.value = true
  now.value = Date.now()
  const [platformResult, userResult, dailyResult] = await Promise.allSettled([
    platformManagementApi.summary(), platformManagementApi.userSummary(), ledgerAdminApi.daily(DAILY_DAYS),
  ])
  if (attempt !== generation) return
  platformSummary.value = platformResult.status === 'fulfilled' ? platformResult.value : null
  userSummary.value = userResult.status === 'fulfilled' ? userResult.value : null
  summaryError.value = platformResult.status === 'rejected' || userResult.status === 'rejected' ? '部分统计暂时读不到，显示为「—」。' : ''
  daily.value = dailyResult.status === 'fulfilled' && Array.isArray(dailyResult.value) ? dailyResult.value : []
  ledgerError.value = dailyResult.status === 'rejected' ? dailyResult.reason?.message || '烟火数据暂时读不到。' : ''
  loading.value = false
}

onMounted(load)
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="management-page wb-page">
    <header class="ad-page-head">
      <div>
        <h1>{{ greeting }}</h1>
        <p>{{ dateLabel }}<template v-if="pendingTotal"> · 有 <RouterLink class="wb-pending" :to="{ name: 'admin-review' }">{{ pendingTotal }} 件待处理</RouterLink></template></p>
      </div>
      <div class="wb-head-actions">
        <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
        <RouterLink class="mg-button mg-primary" :to="{ name: 'admin-review' }"><Inbox :size="15" />开始逐条处理</RouterLink>
      </div>
    </header>

    <section class="wb-kpis" aria-label="平台概况">
      <div v-for="item in kpis" :key="item.label">
        <span>{{ item.label }}</span>
        <strong>{{ number(item.value) }}</strong>
        <small v-if="item.helper">{{ item.helper }}</small>
        <DeltaBadge v-else :current="item.value ?? null" :previous="item.previous ?? null" :label="item.compare" :pending="item.pending" />
      </div>
    </section>
    <p v-if="summaryError" class="ad-note">{{ summaryError }}</p>

    <div class="wb-grid">
      <section class="ad-panel" aria-labelledby="wb-stats-title">
        <header>
          <h2 id="wb-stats-title">烟火统计</h2>
          <div class="wb-ranges" role="tablist" aria-label="时间段">
            <button v-for="item in creditRanges" :key="item.key" type="button" role="tab" :aria-selected="rangeKey === item.key" :class="{ active: rangeKey === item.key }" @click="rangeKey = item.key">{{ item.label }}</button>
          </div>
        </header>
        <p v-if="ledgerError" class="wb-empty">{{ ledgerError }}</p>
        <div v-else-if="loading && !daily.length" class="mg-state" role="status"><RefreshCw :size="24" class="mg-spinning" /><p>正在读取…</p></div>
        <template v-else>
          <div class="wb-metrics" role="tablist" aria-label="烟火类型">
            <button v-for="item in creditMetrics" :key="item.key" type="button" role="tab" :aria-selected="metricKey === item.key" :class="{ active: metricKey === item.key }" @click="metricKey = item.key">
              <span>{{ range.label }}{{ item.label }}</span>
              <strong>{{ number(stats.current[item.key]) }}</strong>
              <DeltaBadge :current="stats.current[item.key]" :previous="stats.previous?.[item.key] ?? null" :label="range.compare" />
            </button>
          </div>

          <div class="wb-chart-head"><h3>每日{{ metric.label }}</h3><small>{{ range.days === 1 ? '最近 14 天，深色为今天' : `${range.label}，共 ${rows.length} 天` }}</small></div>
          <div class="wb-chart" role="img" :aria-label="`每日${metric.label}柱状图`">
            <div v-for="(row, index) in rows" :key="row.date" class="wb-bar" :class="{ current: index >= firstCurrent }" :title="`${row.date} ${metric.label} ${number(row[metricKey])}`">
              <i :style="{ height: `${Number(row[metricKey] || 0) / chartMax * 100}%` }" />
              <small v-if="(rows.length - 1 - index) % labelEvery === 0">{{ shortDate(row.date) }}</small>
            </div>
          </div>

          <div class="wb-table-wrap">
            <table class="wb-table">
              <thead><tr><th>日期</th><th v-for="item in creditMetrics" :key="item.key" :class="{ active: metricKey === item.key }">{{ item.label }}</th></tr></thead>
              <tbody>
                <tr v-for="row in tableRows" :key="row.date" :class="{ muted: !row.current }"><td>{{ row.date }}</td><td v-for="item in creditMetrics" :key="item.key" :class="{ active: metricKey === item.key }">{{ number(row[item.key]) }}</td></tr>
              </tbody>
              <tfoot>
                <tr><th>{{ range.label }}合计</th><td v-for="item in creditMetrics" :key="item.key">{{ number(stats.current[item.key]) }}</td></tr>
                <tr class="muted"><th>上期合计</th><td v-for="item in creditMetrics" :key="item.key">{{ stats.previous ? number(stats.previous[item.key]) : '—' }}</td></tr>
              </tfoot>
            </table>
          </div>
        </template>
      </section>

      <section class="ad-panel" aria-labelledby="wb-share-title">
        <header><h2 id="wb-share-title">{{ range.label }}烟火花在哪</h2><RouterLink class="mg-text-button" :to="{ name: 'admin-credits' }">看流水<ArrowRight :size="13" /></RouterLink></header>
        <p v-if="ledgerError" class="wb-empty">{{ ledgerError }}</p>
        <p v-else-if="!shares.length" class="wb-empty">{{ range.label }}还没有消耗。</p>
        <template v-else>
          <p class="wb-share-total">共消耗 <b>{{ number(shareTotal) }}</b> 烟火</p>
          <ul class="wb-shares">
            <li v-for="item in shares" :key="item.key">
              <div class="wb-share-row"><span>{{ ledgerCapabilities[item.key] || item.key }}</span><strong>{{ item.share.toFixed(item.share > 0 && item.share < 1 ? 1 : 0) }}<small>%</small></strong></div>
              <span class="wb-track"><i :style="{ width: `${item.share}%` }" /></span>
              <div class="wb-share-meta"><span class="mg-mono">{{ number(item.amount) }} 烟火</span><DeltaBadge :current="item.amount" :previous="item.previous" :label="range.compare" /></div>
            </li>
          </ul>
        </template>
      </section>
    </div>
  </div>
</template>
