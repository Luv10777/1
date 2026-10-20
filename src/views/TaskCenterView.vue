<script setup>
import AppSelect from '../components/AppSelect.vue'
import YanhuoIcon from '../components/YanhuoIcon.vue'
import { RouterLink } from 'vue-router'
import { CalendarDays, ChevronDown, ChevronLeft, ChevronRight, ClipboardList, RefreshCw, Search, X } from 'lucide-vue-next'
import { useTaskCenter } from '../composables/useTaskCenter'
import { taskModules, taskStatuses, taskStatus, taskModel, taskWorkflow, taskDate, taskResultLink } from '../domain/taskUsage'
import { onMounted, ref } from 'vue'
import { billingApi, dateTime, number, yuan } from '../stores/billing'
import { auth } from '../stores/auth'

const { filters, result, loading, error, expandedId, detail, detailLoading, detailError, load, search, reset, toggleDetail, loadDetail } = useTaskCenter()
const events = { RESERVE: '预占', CAPTURE: '实际扣费', RELEASE: '退回' }
// 充值记录读的是订单列表（仅商家管理员可读），只列支付平台确认到账的充值。
const topups = ref([])
const topupPage = ref(0)
const topupMore = ref(false)
const topupLoading = ref(false)
const topupError = ref('')
const paymentMethods = { ALIPAY: '支付宝', WECHAT: '微信支付', MANUAL: '人工收款' }
async function loadTopups() {
  topupLoading.value = true; topupError.value = ''
  try {
    // A page may hold only unpaid or membership orders, so keep reading until a top-up turns up or the list ends.
    let found = 0
    do {
      const page = await billingApi.orders(topupPage.value)
      const paid = page.items.filter(order => order.kind === 'TOPUP' && order.state === 'PAID')
      topups.value.push(...paid); found += paid.length
      topupMore.value = page.hasMore; topupPage.value += 1
    } while (!found && topupMore.value)
  } catch (e) { topupError.value = e.message || '充值记录暂时无法读取' }
  finally { topupLoading.value = false }
}
onMounted(() => { if (auth.isOwner) loadTopups() })
const filterStatuses = Object.entries(taskStatuses).filter(([id]) => !['INTERRUPTED', 'QUEUED', 'GENERATING', 'SAVING'].includes(id))
</script>

<template>
  <div class="task-usage">
    <header class="usage-heading">
      <div><h1>任务中心</h1><p>每一次创作，都有一笔清楚的烟火记录。</p></div>
      <button class="usage-button" type="button" :disabled="loading" @click="load()"><RefreshCw :size="15" :class="{ spinning: loading }" />{{ loading ? '正在更新' : '刷新日志' }}</button>
    </header>

    <section class="usage-filters" aria-label="筛选任务日志">
      <form @submit.prevent="search">
        <div class="usage-filter-fields">
          <fieldset class="usage-date-range">
            <legend><CalendarDays :size="14" /> 提交时间 <small>北京时间</small></legend>
            <div><input v-model="filters.from" type="datetime-local" aria-label="开始时间（北京时间）"><span>至</span><input v-model="filters.until" type="datetime-local" aria-label="结束时间（北京时间，不含）"></div>
          </fieldset>
          <label>使用模块<AppSelect v-model="filters.module"><option value="">全部模块</option><option v-for="(label, id) in taskModules" :key="id" :value="id">{{ label }}</option></AppSelect></label>
          <label>模型名称<input v-model="filters.model" type="search" placeholder="搜索模型，如 Seedance" maxlength="200"></label>
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
      <div v-else-if="!result?.records.items.length" class="usage-empty"><ClipboardList :size="32" /><strong>{{ error ? '日志暂时无法读取' : '暂无符合条件的任务' }}</strong><p>{{ error ? '恢复连接后可重新读取。' : '调整筛选条件，或完成一次图片、视频创作后再来查看。' }}</p><RouterLink v-if="!error" to="/image/create">开始创作 <span aria-hidden="true">↗</span></RouterLink></div>
      <div v-else class="usage-table-scroll" role="region" aria-label="任务消费日志表格，可横向滚动" tabindex="0">
        <table class="usage-table">
          <caption class="usage-sr-only">任务提交时间、模块、模型、生成数量、结果与实际烟火消耗</caption>
          <thead><tr><th scope="col">时间</th><th scope="col">使用模块</th><th scope="col">模型</th><th scope="col">生成数量</th><th scope="col">成功 / 失败</th><th scope="col">烟火消耗</th><th scope="col">状态</th><th scope="col" class="usage-action-column">详情</th></tr></thead>
          <tbody>
            <template v-for="row in result.records.items" :key="row.id">
              <tr :class="{ 'usage-row-expanded': expandedId === row.id }">
                <td><time :datetime="row.createdAt">{{ taskDate(row.createdAt) }}</time><small class="usage-code">{{ row.id }}</small></td>
                <td><strong>{{ taskModules[row.module] }}</strong><small>{{ taskWorkflow(row) || '视频内容分析' }}</small></td>
                <td class="usage-model-cell"><span class="usage-model" :title="taskModel(row.model)">{{ taskModel(row.model) }}</span></td>
                <td class="usage-numeric">{{ row.quantity }} <small class="inline">{{ row.unit }}</small></td>
                <td><div class="usage-result-count"><span class="usage-success">{{ row.succeeded }}</span><span class="usage-slash">/</span><span :class="{ 'usage-failed': row.failed > 0 }">{{ row.failed }}</span><small class="inline">{{ row.unit }}</small></div><small v-if="row.cancelled || row.pending">{{ row.cancelled ? `取消 ${row.cancelled} ${row.unit}` : '' }}{{ row.cancelled && row.pending ? ' · ' : '' }}{{ row.pending ? `未完成 ${row.pending} ${row.unit}` : '' }}</small></td>
                <td><span v-if="row.chargedCredits !== null" class="usage-charge"><YanhuoIcon :size="14" />{{ number(row.chargedCredits) }}</span><span v-else class="usage-unbilled">未计费记录</span><small v-if="row.heldCredits || row.releasedCredits">{{ row.heldCredits ? `预占 ${number(row.heldCredits)}` : '' }}{{ row.heldCredits && row.releasedCredits ? ' · ' : '' }}{{ row.releasedCredits ? `退回 ${number(row.releasedCredits)}` : '' }}</small></td>
                <td><span class="usage-status" :data-tone="taskStatus(row.status).tone"><i aria-hidden="true" />{{ taskStatus(row.status).label }}</span></td>
                <td class="usage-action-column"><button class="usage-detail-button" type="button" :aria-expanded="expandedId === row.id" :aria-controls="`detail-${row.id}`" :aria-label="`${expandedId === row.id ? '收起' : '查看'} ${row.id} 消费明细`" @click="toggleDetail(row.id)">{{ expandedId === row.id ? '收起' : '明细' }}<ChevronDown :size="14" :class="{ rotated: expandedId === row.id }" /></button></td>
              </tr>
              <tr v-if="expandedId === row.id" :id="`detail-${row.id}`" class="usage-detail-row">
                <td colspan="8">
                  <p v-if="detailLoading && !detail" class="usage-detail-state" role="status">正在读取逐项明细…</p>
                  <p v-else-if="detailError" class="usage-detail-state usage-failed" role="alert">{{ detailError }} <button class="usage-detail-button" type="button" @click="loadDetail()">重试</button></p>
                  <div v-else-if="detail" class="usage-detail-content">
                    <div class="usage-detail-intro"><div><strong>{{ detail.task.title || taskModules[row.module] }}</strong><p v-if="detail.task.error" class="usage-failed">{{ detail.task.error }}</p></div><RouterLink v-if="taskResultLink(row)" :to="taskResultLink(row)">查看任务 <span aria-hidden="true">↗</span></RouterLink></div>
                    <div v-if="detail.items.length" class="usage-item-list"><h3>逐张记录</h3><div v-for="item in detail.items" :key="item.id" class="usage-item"><span>第 {{ item.ordinal }} 张 <small>{{ item.title }}</small></span><span>{{ taskModel(item.model) }}</span><span class="usage-status" :data-tone="item.reused ? 'muted' : taskStatus(item.status).tone">{{ item.reused ? '沿用原图' : taskStatus(item.status).label }}</span><strong>{{ item.reused ? '本次不计费' : item.chargedCredits === null ? '未计费记录' : `${number(item.chargedCredits)} 烟火` }}</strong><p v-if="item.error" class="usage-item-error">{{ item.error }}</p></div></div>
                    <div class="usage-events"><h3>烟火流水</h3><p v-if="!detail.events.length" class="usage-detail-state">此任务没有烟火流水，历史记录不按现行价格补算。</p><div v-for="event in detail.events" :key="event.id" class="usage-event"><time :datetime="event.createdAt">{{ taskDate(event.createdAt) }}</time><span :data-tone="event.type === 'CAPTURE' ? 'charge' : ''">{{ events[event.type] || event.type }}</span><strong>{{ number(event.credits) }} 烟火</strong><span>{{ event.description }}</span></div></div>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
      <footer v-if="result?.records.total" class="usage-pagination"><p>共 {{ number(result.records.total) }} 条 · 每页 {{ result.records.size }} 条</p><nav aria-label="任务日志分页"><button class="usage-page-button" type="button" :disabled="loading || result.records.page === 0" aria-label="上一页" @click="load(result.records.page - 1)"><ChevronLeft :size="16" /></button><span>第 {{ result.records.page + 1 }} / {{ result.records.totalPages }} 页</span><button class="usage-page-button" type="button" :disabled="loading || result.records.page + 1 >= result.records.totalPages" aria-label="下一页" @click="load(result.records.page + 1)"><ChevronRight :size="16" /></button></nav></footer>
    </section>
    <section v-if="auth.isOwner" class="usage-log usage-topups" aria-labelledby="topup-log-title" :aria-busy="topupLoading">
      <header class="usage-log-heading"><h2 id="topup-log-title">充值记录</h2><p>仅列出支付平台确认到账的充值。</p></header>
      <div v-if="topupLoading && !topups.length" class="usage-empty usage-empty-compact" role="status"><RefreshCw :size="22" class="spinning" /><strong>正在读取充值记录</strong></div>
      <div v-else-if="!topups.length" class="usage-empty usage-empty-compact"><strong>{{ topupError ? '充值记录暂时无法读取' : '还没有充值记录' }}</strong><RouterLink :to="{ path: '/billing', query: { topup: '1' } }">充值烟火</RouterLink></div>
      <div v-else class="usage-table-scroll" role="region" aria-label="充值记录表格，可横向滚动" tabindex="0">
        <table class="usage-table">
          <caption class="usage-sr-only">充值到账时间、金额、到账烟火、支付方式与订单号</caption>
          <thead><tr><th scope="col">到账时间</th><th scope="col">充值金额</th><th scope="col">到账烟火</th><th scope="col">支付方式</th><th scope="col">订单号</th></tr></thead>
          <tbody><tr v-for="order in topups" :key="order.id"><td>{{ dateTime(order.paidAt) }}</td><td>{{ yuan(order.amountMinor) }}</td><td><span class="usage-charge"><YanhuoIcon :size="14" />{{ number(order.credits) }}</span></td><td>{{ paymentMethods[order.paymentMethod] || order.paymentMethod }}</td><td class="usage-order-id">{{ order.id.slice(0, 8) }}</td></tr></tbody>
        </table>
      </div>
      <footer v-if="topupMore && topups.length" class="usage-pagination"><button class="usage-page-button" type="button" :disabled="topupLoading" @click="loadTopups">{{ topupLoading ? '正在读取…' : '加载更多' }}</button></footer>
    </section>
    <p class="usage-footnote">烟火消耗以实际扣费流水为准；失败或取消后未消耗的预占会退回。单张消耗见明细，沿用的原图不重复计费。时间均为北京时间。</p>
  </div>
</template>

<style scoped>
.task-usage{width:100%;min-width:0;color:var(--color-primary)}
.usage-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:24px}
.usage-heading h1{margin:0 0 8px;font:600 28px/1.4 var(--font-serif)}
.usage-heading p{margin:0;font-size:13px;color:var(--color-text-muted)}
.usage-button{display:inline-flex;align-items:center;justify-content:center;gap:8px;min-height:36px;padding:0 14px;border:1px solid var(--color-border-subtle);border-radius:4px;background:var(--color-bg-surface);color:var(--color-primary);font-size:12px;white-space:nowrap;transition:background .15s}
.usage-button:hover:not(:disabled){background:var(--color-bg-subtle)}
.usage-button.primary{background:var(--color-accent);border-color:transparent;color:var(--color-on-accent)}
.usage-button.primary:hover:not(:disabled){background:var(--color-accent-hover)}
.usage-button.quiet{background:transparent}
.usage-filters,.usage-log{border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface)}
.usage-filters{margin-bottom:20px;padding:20px 20px 0}
.usage-filter-fields{display:grid;grid-template-columns:minmax(340px,2.3fr) minmax(140px,1fr) minmax(160px,1.2fr) minmax(120px,.8fr);gap:16px;align-items:end}
.usage-filter-fields>label{display:grid;gap:9px;font-size:12px;color:var(--color-text-muted)}
.usage-filter-fields input,.usage-filter-fields :deep(.app-select-button){display:block;width:100%;min-width:0;height:38px;padding:0 10px;border:1px solid var(--color-border-subtle);border-radius:4px;background:var(--color-bg-surface);color:var(--color-primary);font-size:12px}
.usage-filter-fields input::placeholder{color:var(--color-text-muted)}
.usage-date-range{margin:0;padding:0;border:0;min-width:0}
.usage-date-range legend{display:flex;align-items:center;gap:6px;margin-bottom:9px;padding:0;font-size:12px;color:var(--color-text-muted)}
.usage-date-range legend small{margin-left:4px;font-size:12px}
.usage-date-range>div{display:flex;align-items:center;gap:8px;font-size:12px;color:var(--color-text-muted)}
.usage-date-range input{flex:1;width:0;min-width:0;padding:0 6px;font-variant-numeric:tabular-nums}
.usage-filter-bottom{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 0}
.usage-filter-bottom p{margin:0;font-size:12px;color:var(--color-text-muted)}
.usage-filter-bottom>div{display:flex;gap:8px}
.usage-summary{display:flex;align-items:center;flex-wrap:wrap;gap:10px 20px;border-top:1px solid var(--color-border-subtle);padding:14px 0;font-size:12px;color:var(--color-text-muted);font-variant-numeric:tabular-nums}
.usage-summary>span{display:inline-flex;align-items:baseline;gap:7px}
.usage-summary strong{font-size:14px;font-weight:600;color:var(--color-primary)}
.usage-summary .usage-summary-charge strong{font-size:19px;color:var(--color-accent-text)}
.usage-summary>small{margin-left:auto;font-size:12px}
.usage-error{display:flex;justify-content:space-between;gap:16px;align-items:center;margin-bottom:16px;padding:14px 16px;background:var(--color-bg-surface);border:1px solid var(--color-border-subtle);border-radius:4px;color:var(--color-error);font-size:13px}
.usage-error button{padding:4px 0;background:none;border:0;color:inherit;text-decoration:underline;white-space:nowrap}
.usage-log{overflow:hidden}
.usage-topups{margin-top:20px}
.usage-topups .usage-pagination{justify-content:center}
.usage-topups .usage-page-button{width:auto;padding:0 14px}
.usage-empty.usage-empty-compact{min-height:140px}
.usage-order-id{font-family:var(--font-mono);font-size:12px;color:var(--color-text-muted)}
.usage-log-heading{display:flex;justify-content:space-between;gap:20px;align-items:baseline;padding:20px}
.usage-log-heading h2{display:flex;align-items:baseline;gap:10px;margin:0;font-size:15px;font-weight:600}
.usage-log-heading h2 span,.usage-log-heading p{margin:0;font-size:12px;font-weight:400;color:var(--color-text-muted)}
.usage-table-scroll{overflow-x:auto;scrollbar-width:thin}
.usage-table{width:100%;min-width:1060px;border-collapse:collapse;text-align:left;font-size:12px}
.usage-table th{height:42px;padding:0 16px;border-block:1px solid var(--color-border-subtle);background:var(--color-bg-canvas);font-size:12px;font-weight:500;color:var(--color-text-muted);white-space:nowrap}
.usage-table td{height:82px;padding:16px;border-bottom:1px solid var(--color-border-subtle);vertical-align:middle}
.usage-table tbody>tr:last-child>td{border-bottom:0}
.usage-table td:first-child,.usage-table th:first-child{padding-left:20px}
.usage-table tr:hover>td,.usage-row-expanded>td{background:color-mix(in srgb,var(--color-bg-subtle) 45%,var(--color-bg-surface))}
.usage-table td>strong{font-weight:500;white-space:nowrap}
.usage-table time{white-space:nowrap;font-size:12px;font-variant-numeric:tabular-nums}
.usage-table td>small{display:block;margin-top:7px;font-size:12px;color:var(--color-text-muted);line-height:1.4}
.usage-code{font-family:var(--font-mono)}
.usage-model-cell{max-width:230px;overflow-wrap:anywhere}
.usage-model{display:inline-block;padding:5px 9px;border:1px solid var(--color-border-subtle);border-radius:4px;background:var(--color-bg-surface);font-size:12px;line-height:1.5}
.usage-numeric,.usage-result-count,.usage-charge{font-variant-numeric:tabular-nums}
.usage-result-count{display:flex;align-items:center;gap:8px;font-size:14px}
.inline{font-size:12px;color:var(--color-text-muted);font-weight:400;white-space:nowrap}
.usage-success{color:var(--color-success)}
.usage-failed{color:var(--color-error)}
.usage-slash{color:var(--color-border-strong)}
.usage-charge{display:inline-block;font-size:15px;font-weight:600;white-space:nowrap}
.usage-unbilled{color:var(--color-text-muted);font-size:12px}
.usage-status{display:inline-flex;align-items:center;gap:6px;font-size:12px;white-space:nowrap;color:var(--color-text-muted)}
.usage-status i{width:5px;height:5px;border-radius:50%;background:currentColor}
.usage-status[data-tone=success]{color:var(--color-success)}
.usage-status[data-tone=error]{color:var(--color-error)}
.usage-status[data-tone=warning]{color:var(--color-warning)}
.usage-status[data-tone=info]{color:var(--color-info)}
.usage-action-column{text-align:right}
.usage-detail-button{display:inline-flex;align-items:center;gap:6px;padding:6px 0;border:0;background:none;font-size:12px;color:var(--color-accent-text);white-space:nowrap}
.usage-detail-button:hover{text-decoration:underline}
.usage-detail-button svg{transition:transform .15s}
.usage-detail-button .rotated{transform:rotate(180deg)}
.usage-detail-row>td{padding:0!important;background:var(--color-bg-canvas)!important}
.usage-detail-content{padding:20px 24px}
.usage-detail-intro{display:flex;justify-content:space-between;gap:24px;margin-bottom:20px;font-size:12px}
.usage-detail-intro>div{max-width:900px;min-width:0}
.usage-detail-intro strong{display:block;font-weight:500;line-height:1.8;overflow-wrap:anywhere;white-space:pre-wrap}
.usage-detail-intro p{margin:8px 0 0;line-height:1.7}
.usage-detail-intro>a{flex-shrink:0;color:var(--color-accent-text)}
.usage-item-list{margin-bottom:24px}
.usage-detail-content h3{margin:0 0 12px;font-size:12px;font-weight:600}
.usage-item{display:grid;grid-template-columns:2fr 1.4fr 100px 110px;gap:12px;align-items:baseline;padding:12px 0;border-top:1px solid var(--color-border-subtle);font-size:12px}
.usage-item>span:first-child{overflow-wrap:anywhere}
.usage-item small{margin-left:12px;color:var(--color-text-muted);line-height:1.6}
.usage-item>strong{text-align:right;font-size:12px;font-weight:500}
.usage-item-error{grid-column:1/-1;margin:0;color:var(--color-error);line-height:1.7}
.usage-event{display:grid;grid-template-columns:160px 72px 100px 1fr;gap:16px;padding:10px 0;border-top:1px solid var(--color-border-subtle);font-size:12px;color:var(--color-text-muted)}
.usage-event strong{font-weight:500;color:var(--color-primary)}
.usage-event [data-tone=charge]{color:var(--color-accent-text)}
.usage-detail-state{margin:0;padding:20px;color:var(--color-text-muted);font-size:12px}
.usage-empty{display:flex;min-height:280px;flex-direction:column;align-items:center;justify-content:center;gap:14px;padding:40px 24px;text-align:center;color:var(--color-text-muted)}
.usage-empty strong{font-size:14px;font-weight:500;color:var(--color-primary)}
.usage-empty p{margin:0;font-size:12px;line-height:1.7}
.usage-empty>a{margin-top:6px;font-size:12px;color:var(--color-accent-text)}
.usage-pagination{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:14px 20px;font-size:12px;color:var(--color-text-muted);border-top:1px solid var(--color-border-subtle)}
.usage-pagination p{margin:0}
.usage-pagination nav{display:flex;align-items:center;gap:14px}
.usage-page-button{display:grid;place-items:center;width:30px;height:30px;border:1px solid var(--color-border-subtle);border-radius:4px;background:var(--color-bg-surface)}
.usage-page-button:hover:not(:disabled){background:var(--color-bg-subtle)}
.usage-footnote{margin:16px 0 0;font-size:12px;line-height:1.8;color:var(--color-text-muted)}
.usage-sr-only{position:absolute;width:1px;height:1px;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap}
.spinning{animation:usage-spin 1.2s linear infinite}
@keyframes usage-spin{to{transform:rotate(360deg)}}
@media(min-width:1600px){.usage-filter-fields{grid-template-columns:minmax(430px,2.3fr) 1fr 1.2fr .8fr}.usage-table td{height:88px}}
@media(max-width:1250px){.usage-filter-fields{grid-template-columns:1fr 1fr}.usage-date-range{grid-column:1/-1}.usage-summary>small{width:100%;margin-left:0}.usage-log-heading{align-items:flex-start;flex-direction:column;gap:8px}}
@media(max-width:640px){.usage-heading{align-items:flex-start}.usage-heading h1{font-size:25px}.usage-heading p{max-width:200px;font-size:12px;line-height:1.7}.usage-heading>.usage-button{padding:0 10px}.usage-filters{padding:16px 16px 0}.usage-filter-fields{gap:14px 12px}.usage-filter-fields>label:nth-child(3){grid-column:1/-1;grid-row:3}.usage-date-range>div{flex-direction:column;align-items:stretch;gap:5px}.usage-date-range input{width:100%;flex:auto}.usage-date-range>div>span{font-size:12px}.usage-filter-bottom{align-items:flex-end;gap:12px}.usage-filter-bottom p{max-width:150px;line-height:1.7}.usage-summary{gap:12px 18px}.usage-summary-charge{width:100%}.usage-log-heading{padding:16px}.usage-pagination{padding:14px 16px;flex-wrap:wrap}.usage-pagination nav{margin-left:auto}.usage-footnote{font-size:12px}}
@media(prefers-reduced-motion:reduce){.spinning{animation:none}.usage-detail-button svg,.usage-button{transition:none}}
</style>
