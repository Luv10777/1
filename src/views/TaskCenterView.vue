<script setup>
import { billingWalletLabel } from '../domain/billing'
import AppSelect from '../components/AppSelect.vue'
import YanhuoIcon from '../components/YanhuoIcon.vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { CalendarDays, ChevronDown, ChevronLeft, ChevronRight, ClipboardList, RefreshCw, Search, X } from 'lucide-vue-next'
import { useTaskCenter } from '../composables/useTaskCenter'
import { taskModules, taskStatuses, taskStatus, taskModel, taskWorkflow, taskDate, taskResultLink, taskElapsed } from '../domain/taskUsage'
import { ref } from 'vue'
import { billingApi, dateTime, number, yuan } from '../stores/billing'
import { auth } from '../stores/auth'

const { filters, result, loading, error, expandedId, detail, detailLoading, detailError, load, search, reset, toggleDetail, loadDetail } = useTaskCenter()
const events = { RESERVE: '预占', CAPTURE: '实际扣费', RELEASE: '退回' }
// 充值记录只列支付平台确认到账的充值（仅商家管理员可读），前后余额取自当时的烟火流水。
const topups = ref([])
const topupPage = ref(0)
const topupMore = ref(false)
const topupLoading = ref(false)
const topupError = ref('')
const paymentMethods = { ALIPAY: '支付宝', WECHAT: '微信支付', MANUAL: '人工收款' }
let topupsLoaded = false
async function loadTopups(fresh = false) {
  if (fresh) { topups.value = []; topupPage.value = 0 }
  topupsLoaded = true
  topupLoading.value = true; topupError.value = ''
  try {
    const page = await billingApi.topups(topupPage.value)
    topups.value.push(...page.items)
    topupMore.value = page.hasMore; topupPage.value += 1
  } catch (e) { topupError.value = e.message || '充值记录暂时无法读取' }
  finally { topupLoading.value = false }
}
// 上方切换「消费日志 / 充值记录」，选择记在地址里，刷新后仍停在同一栏。
const route = useRoute()
const router = useRouter()
const view = ref(auth.isOwner && route.query.view === 'topups' ? 'topups' : 'usage')
if (view.value === 'topups') loadTopups()
function showView(next) {
  view.value = next
  router.replace({ query: { ...route.query, view: next === 'topups' ? 'topups' : undefined } })
  if (next === 'topups' && !topupsLoaded) loadTopups()
}
const filterStatuses = Object.entries(taskStatuses).filter(([id]) => !['INTERRUPTED', 'QUEUED', 'GENERATING', 'SAVING'].includes(id))
</script>

<template>
  <div class="task-usage">
    <header class="usage-heading">
      <div><h1>任务中心</h1><p>每一次创作，都有一笔清楚的烟火记录。</p></div>
      <button v-if="view === 'topups'" class="usage-button" type="button" :disabled="topupLoading" @click="loadTopups(true)"><RefreshCw :size="15" :class="{ spinning: topupLoading }" />{{ topupLoading ? '正在更新' : '刷新记录' }}</button>
      <button v-else class="usage-button" type="button" :disabled="loading" @click="load()"><RefreshCw :size="15" :class="{ spinning: loading }" />{{ loading ? '正在更新' : '刷新日志' }}</button>
    </header>

    <div v-if="auth.isOwner" class="usage-tabs" role="tablist" aria-label="记录类型">
      <button type="button" role="tab" :aria-selected="view === 'usage'" :class="{ active: view === 'usage' }" @click="showView('usage')">消费日志</button>
      <button type="button" role="tab" :aria-selected="view === 'topups'" :class="{ active: view === 'topups' }" @click="showView('topups')">充值记录</button>
    </div>

    <template v-if="view === 'usage'">

    <section class="usage-filters" aria-label="筛选任务日志">
      <form @submit.prevent="search">
        <div class="usage-filter-fields">
          <fieldset class="usage-date-range">
            <legend><CalendarDays :size="14" /> 提交时间 <small>北京时间</small></legend>
            <div><input v-model="filters.from" type="datetime-local" aria-label="开始时间（北京时间）"><span>至</span><input v-model="filters.until" type="datetime-local" aria-label="结束时间（北京时间，不含）"></div>
          </fieldset>
          <label>使用模块<AppSelect v-model="filters.module"><option value="">全部模块</option><option v-for="(label, id) in taskModules" :key="id" :value="id">{{ label }}</option></AppSelect></label>
          <label>模型名称<input v-model="filters.model" type="search" placeholder="搜索一方志或 Seedance" maxlength="200"></label>
          <label>任务状态<AppSelect v-model="filters.status"><option value="">全部状态</option><option v-for="[id, state] in filterStatuses" :key="id" :value="id">{{ state.label }}</option></AppSelect></label>
        </div>
        <div class="usage-filter-bottom">
          <p>按提交时间查询；未设置时间时展示全部记录。</p>
          <div><button class="usage-button quiet" type="button" :disabled="loading" @click="reset"><X :size="14" />重置</button><button class="usage-button primary" type="submit" :disabled="loading"><Search :size="15" />搜索</button></div>
        </div>
      </form>
      <div class="usage-summary" aria-live="polite" :aria-busy="loading">
        <span class="usage-summary-charge">实际消耗 <strong><YanhuoIcon :size="16" />{{ result ? number(result.summary.chargedCredits) : '—' }}</strong></span>
        <span>预占中 <strong>{{ result ? number(result.summary.heldCredits) : '—' }}</strong></span>
        <span>已退回 <strong>{{ result ? number(result.summary.releasedCredits) : '—' }}</strong></span>
        <span>成功 <strong>{{ result ? number(result.summary.succeeded) : '—' }}</strong></span>
        <span>失败 <strong>{{ result ? number(result.summary.failed) : '—' }}</strong></span>
        <small>汇总当前筛选下的全部任务</small>
      </div>
    </section>

    <div v-if="error" class="usage-error" role="alert"><span>{{ error }}</span><button type="button" @click="load()">重新读取</button></div>

    <section class="usage-log" aria-labelledby="usage-log-title" :aria-busy="loading">
      <header class="usage-log-heading"><h2 id="usage-log-title">创作消费日志 <span v-if="result">{{ number(result.records.total) }} 条</span></h2><p>仅成功交付计入消耗，预占与退回单独列示。</p></header>
      <div v-if="loading && !result" class="usage-empty" role="status"><RefreshCw :size="28" class="spinning" /><strong>正在读取任务与消费记录</strong></div>
      <div v-else-if="!result?.records.items.length" class="usage-empty"><ClipboardList :size="32" /><strong>{{ error ? '日志暂时无法读取' : '暂无符合条件的任务' }}</strong><p>{{ error ? '恢复连接后可重新读取。' : '调整筛选条件，或完成一次图片、视频创作后再来查看。' }}</p><RouterLink v-if="!error" to="/image/create">开始创作</RouterLink></div>
      <div v-else class="usage-table-scroll" role="region" aria-label="任务消费日志表格，可横向滚动" tabindex="0">
        <table class="usage-table">
          <caption class="usage-sr-only">任务提交时间、模块、模型、生成数量、结果、实际烟火消耗与生成耗时</caption>
          <thead><tr><th scope="col">时间</th><th scope="col">使用模块</th><th scope="col">模型</th><th scope="col">生成数量</th><th scope="col">成功 / 失败</th><th scope="col">烟火消耗</th><th scope="col" title="图片优先显示从提交到浏览器首次加载图片的时间；其他任务及未记录图片显示时间的历史任务，显示提交至完成或失败的耗时">耗时</th><th scope="col">状态</th><th scope="col" class="usage-action-column">详情</th></tr></thead>
          <tbody>
            <template v-for="row in result.records.items" :key="row.id">
              <tr :class="{ 'usage-row-expanded': expandedId === row.id }">
                <td><time :datetime="row.createdAt">{{ taskDate(row.createdAt) }}</time><small class="usage-code">{{ row.id }}</small></td>
                <td><strong>{{ taskModules[row.module] }}</strong><small>{{ taskWorkflow(row) || '视频内容分析' }}</small></td>
                <td class="usage-model-cell"><span class="usage-model" :title="taskModel(row.model, row.module)">{{ taskModel(row.model, row.module) }}</span></td>
                <td class="usage-numeric">{{ row.quantity }} <small class="inline">{{ row.unit }}</small></td>
                <td><div class="usage-result-count"><span class="usage-success">{{ row.succeeded }}</span><span class="usage-slash">/</span><span :class="{ 'usage-failed': row.failed > 0 }">{{ row.failed }}</span><small class="inline">{{ row.unit }}</small></div><small v-if="row.cancelled || row.pending">{{ row.cancelled ? `取消 ${row.cancelled} ${row.unit}` : '' }}{{ row.cancelled && row.pending ? ' · ' : '' }}{{ row.pending ? `未完成 ${row.pending} ${row.unit}` : '' }}</small></td>
                <td><span v-if="row.chargedCredits != null" class="usage-charge"><YanhuoIcon :size="14" />{{ number(row.chargedCredits) }}</span><span v-else class="usage-unbilled">未计费记录</span><small v-if="row.billingWallet">{{ billingWalletLabel(row.billingWallet) }}</small><small v-if="row.heldCredits || row.releasedCredits">{{ row.heldCredits ? `预占 ${number(row.heldCredits)}` : '' }}{{ row.heldCredits && row.releasedCredits ? ' · ' : '' }}{{ row.releasedCredits ? `退回 ${number(row.releasedCredits)}` : '' }}</small></td>
                <td class="usage-elapsed">{{ taskElapsed(row) }}</td>
                <td><span class="usage-status" :data-tone="taskStatus(row.status).tone"><i aria-hidden="true" />{{ taskStatus(row.status).label }}</span></td>
                <td class="usage-action-column"><button class="usage-detail-button" type="button" :aria-expanded="expandedId === row.id" :aria-controls="`detail-${row.id}`" :aria-label="`${expandedId === row.id ? '收起' : '查看'} ${row.id} 消费明细`" @click="toggleDetail(row.id)">{{ expandedId === row.id ? '收起' : '明细' }}<ChevronDown :size="14" :class="{ rotated: expandedId === row.id }" /></button></td>
              </tr>
              <tr v-if="expandedId === row.id" :id="`detail-${row.id}`" class="usage-detail-row">
                <td colspan="9">
                  <p v-if="detailLoading && !detail" class="usage-detail-state" role="status">正在读取逐项明细…</p>
                  <p v-else-if="detailError" class="usage-detail-state usage-failed" role="alert">{{ detailError }} <button class="usage-detail-button" type="button" @click="loadDetail()">重试</button></p>
                  <div v-else-if="detail" class="usage-detail-content">
                    <div class="usage-detail-intro"><div><strong>{{ detail.task.title || taskModules[row.module] }}</strong><p v-if="detail.task.error" class="usage-failed">{{ detail.task.error }}</p></div><RouterLink v-if="taskResultLink(row)" :to="taskResultLink(row)">查看任务</RouterLink></div>
                    <div v-if="detail.items.length" class="usage-item-list"><h3>逐张记录</h3><div v-for="item in detail.items" :key="item.id" class="usage-item"><span>第 {{ item.ordinal }} 张 <small>{{ item.title }}</small></span><span>{{ taskModel(item.model, row.module) }}</span><span class="usage-status" :data-tone="item.reused ? 'muted' : taskStatus(item.status).tone">{{ item.reused ? '沿用原图' : taskStatus(item.status).label }}</span><strong>{{ item.reused ? '本次不计费' : item.chargedCredits == null ? '未计费记录' : `${number(item.chargedCredits)} 烟火` }}<small v-if="item.billingWallet"> · {{ billingWalletLabel(item.billingWallet) }}</small></strong><p v-if="item.error" class="usage-item-error">{{ item.error }}</p></div></div>
                    <div class="usage-events"><h3>烟火流水</h3><p v-if="!detail.events.length" class="usage-detail-state">此任务没有烟火流水，历史记录不按现行价格补算。</p><div v-for="event in detail.events" :key="event.id" class="usage-event"><time :datetime="event.createdAt">{{ taskDate(event.createdAt) }}</time><span :data-tone="event.type === 'CAPTURE' ? 'charge' : ''">{{ events[event.type] || event.type }}</span><strong>{{ number(event.credits) }} 烟火</strong><span>{{ event.description }}<small v-if="event.billingWallet"> · {{ billingWalletLabel(event.billingWallet) }}</small></span></div></div>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
      <footer v-if="result?.records.total" class="usage-pagination"><p>共 {{ number(result.records.total) }} 条 · 每页 {{ result.records.size }} 条</p><nav aria-label="任务日志分页"><button class="usage-page-button" type="button" :disabled="loading || result.records.page === 0" aria-label="上一页" @click="load(result.records.page - 1)"><ChevronLeft :size="16" /></button><span>第 {{ result.records.page + 1 }} / {{ result.records.totalPages }} 页</span><button class="usage-page-button" type="button" :disabled="loading || result.records.page + 1 >= result.records.totalPages" aria-label="下一页" @click="load(result.records.page + 1)"><ChevronRight :size="16" /></button></nav></footer>
    </section>
    <p class="usage-footnote">烟火消耗以实际扣费流水为准；失败或取消后未消耗的预占会退回。单张消耗见明细，沿用的原图不重复计费。时间均为北京时间。</p>
    </template>
    <section v-else class="usage-log usage-topups" aria-labelledby="topup-log-title" :aria-busy="topupLoading">
      <header class="usage-log-heading"><h2 id="topup-log-title">充值记录</h2><p>仅列出支付平台确认到账的充值。</p></header>
      <div v-if="topupLoading && !topups.length" class="usage-empty usage-empty-compact" role="status"><RefreshCw :size="22" class="spinning" /><strong>正在读取充值记录</strong></div>
      <div v-else-if="!topups.length" class="usage-empty usage-empty-compact"><strong>{{ topupError ? '充值记录暂时无法读取' : '还没有充值记录' }}</strong><RouterLink :to="{ path: '/billing', query: { topup: '1' } }">充值烟火</RouterLink></div>
      <div v-else class="usage-table-scroll" role="region" aria-label="充值记录表格，可横向滚动" tabindex="0">
        <table class="usage-table">
          <caption class="usage-sr-only">充值到账时间、金额、到账烟火、充值前后余额、支付方式与订单号</caption>
          <thead><tr><th scope="col">到账时间</th><th scope="col">充值金额</th><th scope="col">到账烟火</th><th scope="col">充值前余额</th><th scope="col">充值后余额</th><th scope="col">支付方式</th><th scope="col">订单号</th></tr></thead>
          <tbody><tr v-for="record in topups" :key="record.orderId"><td>{{ dateTime(record.paidAt) }}</td><td>{{ yuan(record.amountMinor) }}</td><td><span class="usage-charge usage-topup-added">+{{ number(record.credits) }}</span></td><td>{{ number(record.balanceBefore) }}</td><td><span class="usage-charge"><YanhuoIcon :size="14" />{{ number(record.balanceAfter) }}</span></td><td>{{ paymentMethods[record.paymentMethod] || record.paymentMethod }}</td><td class="usage-order-id">{{ record.orderId.slice(0, 8) }}</td></tr></tbody>
        </table>
      </div>
      <footer v-if="topupMore && topups.length" class="usage-pagination"><button class="usage-page-button" type="button" :disabled="topupLoading" @click="loadTopups()">{{ topupLoading ? '正在读取…' : '加载更多' }}</button></footer>
    </section>
  </div>
</template>

<style scoped src="../task-usage.css"></style>
