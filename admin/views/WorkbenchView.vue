<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowRight, Inbox, RefreshCw } from 'lucide-vue-next'
import { isOverdue, OVERDUE_HOURS, reviewKindOrder, reviewKinds, waitedLabel } from '../../src/domain/adminReview.js'
import { platformManagementApi } from '../../src/services/platformManagement.js'
import { ledgerAdminApi, ledgerCapabilities } from '../../src/services/ledgerAdminApi.js'
import { loadReviewQueue } from '../reviewQueue.js'
import { pending } from '../pendingReviews.js'

const loading = ref(false)
const queue = ref({ items: [], failed: [] })
const queueError = ref('')
const platformSummary = ref(null)
const userSummary = ref(null)
const summaryError = ref('')
const today = ref(null)
const week = ref(null)
const ledgerError = ref('')
const now = ref(Date.now())
let generation = 0

const greeting = computed(() => {
  const hour = new Date(now.value).getHours()
  return hour < 11 ? '早上好' : hour < 13 ? '中午好' : hour < 18 ? '下午好' : '晚上好'
})
const dateLabel = computed(() => new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date(now.value)))
const rows = computed(() => {
  const review = reviewKindOrder.map(kind => {
    const list = queue.value.items.filter(item => item.kind === kind)
    const oldest = list[0]
    return { key: kind, label: reviewKinds[kind].title, count: list.length, failed: queue.value.failed.includes(kind), since: oldest?.since ?? null, late: oldest ? isOverdue(oldest, now.value) : false, to: { name: 'admin-review', query: { kind } } }
  })
  const elsewhere = [
    { key: 'statements', label: '有异议的对账单', count: pending.statements, to: { name: 'admin-alliance' } },
    { key: 'withdrawals', label: '待审核提现', count: pending.withdrawals, to: { name: 'admin-referral' } },
  ]
  return [...review, ...elsewhere].filter(row => row.count || row.failed).sort((a, b) => (a.since ?? Infinity) - (b.since ?? Infinity))
})
const total = computed(() => rows.value.reduce((sum, row) => sum + (row.count || 0), 0))
const overdue = computed(() => queue.value.items.filter(item => isOverdue(item, now.value)).length)
const kpis = computed(() => [
  { label: '用户总数', value: userSummary.value?.users, helper: '不含已被商户移除的账号' },
  { label: '近 7 天登录', value: userSummary.value?.activeLast7Days, helper: '最近一周登录过的账号' },
  { label: '近 7 天新增', value: userSummary.value?.newLast7Days, helper: '注册或被商户添加' },
  { label: '已入驻商家', value: platformSummary.value?.approvedMerchants, helper: '入驻审核通过' },
  { label: '在线信息', value: platformSummary.value?.publishedInformation, helper: '已发布且在有效期内' },
])
const capabilities = computed(() => {
  const entries = Object.entries(today.value?.consumedByCapability || {}).filter(([, amount]) => amount > 0).sort((a, b) => b[1] - a[1])
  const max = entries[0]?.[1] || 1
  const sum = entries.reduce((value, [, amount]) => value + amount, 0) || 1
  return entries.map(([key, amount]) => ({ key, label: ledgerCapabilities[key] || key, amount, width: amount / max * 100, share: Math.round(amount / sum * 100) }))
})
const number = value => value == null ? '—' : Number(value).toLocaleString('zh-CN')

async function load() {
  const attempt = ++generation
  loading.value = true
  now.value = Date.now()
  const [queueResult, platformResult, userResult, todayResult, weekResult] = await Promise.allSettled([
    loadReviewQueue(), platformManagementApi.summary(), platformManagementApi.userSummary(), ledgerAdminApi.summary(1), ledgerAdminApi.summary(7),
  ])
  if (attempt !== generation) return
  queueError.value = queueResult.status === 'rejected' ? queueResult.reason?.message || '待处理事项读取失败。' : ''
  if (queueResult.status === 'fulfilled') queue.value = queueResult.value
  platformSummary.value = platformResult.status === 'fulfilled' ? platformResult.value : null
  userSummary.value = userResult.status === 'fulfilled' ? userResult.value : null
  summaryError.value = platformResult.status === 'rejected' || userResult.status === 'rejected' ? '部分统计暂时读不到，显示为「—」。' : ''
  today.value = todayResult.status === 'fulfilled' ? todayResult.value : null
  week.value = weekResult.status === 'fulfilled' ? weekResult.value : null
  ledgerError.value = todayResult.status === 'rejected' ? todayResult.reason?.message || '烟火数据暂时读不到。' : ''
  loading.value = false
}

onMounted(load)
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="management-page wb-page">
    <header class="ad-page-head">
      <div>
        <h1>{{ greeting }}，{{ total ? `有 ${total} 件事等你处理` : '暂时没有待处理的事' }}</h1>
        <p>{{ dateLabel }}<template v-if="overdue"> · <span class="wb-late">其中 {{ overdue }} 件已等超过 {{ OVERDUE_HOURS }} 小时</span></template></p>
      </div>
      <div class="wb-head-actions">
        <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
        <RouterLink class="mg-button mg-primary" :to="{ name: 'admin-review' }"><Inbox :size="15" />开始逐条处理</RouterLink>
      </div>
    </header>

    <section class="wb-kpis" aria-label="平台概况">
      <div v-for="item in kpis" :key="item.label"><span>{{ item.label }}</span><strong>{{ number(item.value) }}</strong><small>{{ item.helper }}</small></div>
    </section>
    <p v-if="summaryError" class="ad-note">{{ summaryError }}</p>

    <div class="wb-grid">
      <section class="ad-panel" aria-labelledby="wb-queue-title">
        <header><h2 id="wb-queue-title">待你处理</h2><small>等得最久的排最前</small></header>
        <p v-if="queueError" class="ad-alert wb-inset" role="alert">{{ queueError }}</p>
        <div v-else-if="loading && !rows.length" class="mg-state" role="status"><RefreshCw :size="24" class="mg-spinning" /><p>正在读取…</p></div>
        <p v-else-if="!rows.length" class="wb-empty">都处理完了。新的申请进来后，侧栏和铃铛会显示数字。</p>
        <ul v-else class="wb-queue">
          <li v-for="row in rows" :key="row.key">
            <div>
              <strong>{{ row.label }}</strong>
              <small v-if="row.failed" class="wb-late">暂时读不到</small>
              <small v-else-if="row.since != null" :class="{ 'wb-late': row.late }">最久一条已等 {{ waitedLabel(row.since, now) }}</small>
              <small v-else>在原页面处理</small>
            </div>
            <b :class="{ late: row.late }">{{ row.failed ? '—' : row.count }}</b>
            <RouterLink class="mg-button" :to="row.to">去处理<ArrowRight :size="14" /></RouterLink>
          </li>
        </ul>
      </section>

      <section class="ad-panel" aria-labelledby="wb-credit-title">
        <header><h2 id="wb-credit-title">烟火</h2><RouterLink class="mg-text-button" :to="{ name: 'admin-credits' }">看流水<ArrowRight :size="13" /></RouterLink></header>
        <p v-if="ledgerError" class="wb-empty">{{ ledgerError }}</p>
        <template v-else>
          <dl class="wb-credit">
            <div><dt>今日消耗</dt><dd>{{ number(today?.consumed) }}</dd></div>
            <div><dt>今日购买</dt><dd>{{ number(today?.purchased) }}</dd></div>
            <div><dt>今日赠送</dt><dd>{{ number(today?.granted) }}</dd></div>
          </dl>
          <dl class="wb-credit wb-credit-week">
            <div><dt>近 7 天消耗</dt><dd>{{ number(week?.consumed) }}</dd></div>
            <div><dt>近 7 天购买</dt><dd>{{ number(week?.purchased) }}</dd></div>
            <div><dt>近 7 天退回</dt><dd>{{ number(week?.refunded) }}</dd></div>
          </dl>
          <h3 class="wb-sub">今天花在哪些功能上</h3>
          <p v-if="!capabilities.length" class="wb-empty">今天还没有消耗。</p>
          <ul v-else class="wb-bars">
            <li v-for="item in capabilities" :key="item.key"><span>{{ item.label }}</span><span class="wb-track"><i :style="{ width: `${item.width}%` }" /></span><span class="mg-mono">{{ number(item.amount) }}<small>{{ item.share }}%</small></span></li>
          </ul>
        </template>
      </section>
    </div>
  </div>
</template>
