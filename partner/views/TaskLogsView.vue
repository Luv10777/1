<script setup>
import { computed, ref } from 'vue'
import { ChevronLeft, ChevronRight, ClipboardList, RefreshCw, Search, X } from 'lucide-vue-next'
import AppSelect from '../../src/components/AppSelect.vue'
import YanhuoIcon from '../../src/components/YanhuoIcon.vue'
import { number } from '../../src/domain/partnerOps.js'
import { taskDate } from '../../src/domain/taskUsage.js'
import { ledgerCapabilities } from '../../src/services/ledgerAdminApi.js'
import { partnerApi } from '../../src/services/partnerApi.js'
import MemberIdentity from '../components/MemberIdentity.vue'
import { can, loadPartner } from '../partnerStore.js'

const kinds = { CREATION: '创作消耗', DISTRIBUTION: '分配给商户' }
const statuses = {
  CAPTURED: { label: '已扣费', tone: 'success' },
  DONE: { label: '已到账', tone: 'success' },
  PENDING: { label: '已扣费，待到账', tone: 'warning' },
}
const kind = ref('')
const memberNumber = ref('')
const result = ref(null)
const loading = ref(false)
const error = ref('')
const records = computed(() => result.value?.records)
const summary = computed(() => error.value ? null : result.value)
const pageCount = computed(() => Math.ceil((records.value?.total || 0) / (records.value?.size || 20)))
const finance = computed(() => can('finance'))
let appliedFilters = {}

async function load(page = 0) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    result.value = await partnerApi.taskLogs({ page, size: 20, ...appliedFilters })
  } catch (failure) {
    error.value = failure.message || '任务日志暂时无法读取。'
  } finally {
    loading.value = false
  }
}

function search() {
  appliedFilters = { kind: kind.value, memberNumber: memberNumber.value.trim().replace(/^#/, '') }
  return load()
}

function reset() {
  kind.value = ''
  memberNumber.value = ''
  return search()
}

function refresh() {
  load(error.value ? 0 : records.value?.page || 0)
  loadPartner(true)
}

load()
</script>

<template>
  <div class="task-usage partner-task-usage">
    <header class="usage-heading">
      <div>
        <h1>任务日志</h1>
        <p>{{ finance ? '查看全团队的烟火消耗与分配。' : '查看你使用团队烟火的创作与分配。' }}</p>
      </div>
      <button class="usage-button" type="button" :disabled="loading" @click="refresh"><RefreshCw :size="15" :class="{ spinning: loading }" />{{ loading ? '正在更新' : '刷新日志' }}</button>
    </header>

    <section class="usage-filters" aria-label="筛选任务日志">
      <form @submit.prevent="search">
        <div class="usage-filter-fields">
          <label>操作类型
            <AppSelect v-model="kind" :disabled="loading"><option value="">全部操作</option><option v-for="(label, value) in kinds" :key="value" :value="value">{{ label }}</option></AppSelect>
          </label>
          <label v-if="finance">成员编号
            <input v-model="memberNumber" placeholder="例如 #1" inputmode="numeric" maxlength="16" pattern="#?[0-9]{1,15}" :disabled="loading">
          </label>
        </div>
        <div class="usage-filter-bottom">
          <div>
            <button class="usage-button quiet" type="button" :disabled="loading" @click="reset"><X :size="14" />重置</button>
            <button class="usage-button primary" type="submit" :disabled="loading"><Search :size="15" />搜索</button>
          </div>
        </div>
      </form>
      <div class="usage-summary" aria-live="polite" :aria-busy="loading">
        <span class="usage-summary-charge">合计支出 <strong><YanhuoIcon :size="16" />{{ summary ? number(summary.creationCredits + summary.distributionCredits) : '—' }}</strong></span>
        <span>创作消耗 <strong>{{ summary ? number(summary.creationCredits) : '—' }}</strong></span>
        <span>分配转出 <strong>{{ summary ? number(summary.distributionCredits) : '—' }}</strong></span>
        <small>汇总当前筛选下的全部记录</small>
      </div>
    </section>

    <div v-if="error" class="usage-error" role="alert"><span>{{ error }}</span><button type="button" @click="load()">重新读取</button></div>

    <section class="usage-log" :aria-busy="loading" aria-labelledby="partner-log-title">
      <header class="usage-log-heading">
        <h2 id="partner-log-title">团队消费日志 <span v-if="records && !error">{{ number(records.total) }} 条</span></h2>
        <p>创作按实际扣费计入，分配按团队账户实际转出计入。</p>
      </header>
      <div v-if="loading && !records" class="usage-empty" role="status"><RefreshCw :size="28" class="spinning" /><strong>正在读取任务日志</strong></div>
      <div v-else-if="error || !records?.items.length" class="usage-empty"><ClipboardList :size="32" /><strong>{{ error ? '日志暂时无法读取' : '暂无符合条件的记录' }}</strong></div>
      <div v-else class="usage-table-scroll" role="region" aria-label="团队消费日志表格，可横向滚动" tabindex="0">
        <table class="usage-table">
          <thead><tr><th scope="col">时间</th><th scope="col">操作成员</th><th scope="col">操作</th><th scope="col">说明</th><th scope="col">烟火消耗</th><th scope="col">状态</th></tr></thead>
          <tbody>
            <tr v-for="item in records.items" :key="item.id">
              <td><time :datetime="item.createdAt">{{ taskDate(item.createdAt) }}</time></td>
              <td><MemberIdentity :number="item.actorNumber" :name="item.actorName" /></td>
              <td><strong>{{ kinds[item.kind] }}</strong></td>
              <td>
                <span v-if="item.capability" class="usage-model">{{ ledgerCapabilities[item.capability] || item.capability }}</span>
                <small v-if="item.capability">{{ item.description }}</small>
                <span v-else>{{ item.description }}</span>
              </td>
              <td><span class="usage-charge"><YanhuoIcon :size="14" />{{ number(item.credits) }}</span></td>
              <td><span class="usage-status" :data-tone="statuses[item.status]?.tone"><i aria-hidden="true" />{{ statuses[item.status]?.label || item.status }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="!error && records?.total" class="usage-pagination">
        <p>共 {{ number(records.total) }} 条 · 每页 {{ records.size }} 条</p>
        <nav aria-label="任务日志分页">
          <button class="usage-page-button" type="button" aria-label="上一页" :disabled="loading || records.page === 0" @click="load(records.page - 1)"><ChevronLeft :size="16" /></button>
          <span>第 {{ records.page + 1 }} / {{ pageCount }} 页</span>
          <button class="usage-page-button" type="button" aria-label="下一页" :disabled="loading || records.page + 1 >= pageCount" @click="load(records.page + 1)"><ChevronRight :size="16" /></button>
        </nav>
      </footer>
    </section>
    <p class="usage-footnote">时间均为北京时间。</p>
  </div>
</template>

<style scoped src="../../src/task-usage.css"></style>

<style scoped>
.partner-task-usage {
  --font-serif: var(--font-sans);
  --color-text-muted: var(--ad-muted);
  --color-bg-canvas: var(--ad-canvas);
  --color-accent: var(--ad-primary);
  --color-accent-hover: var(--ad-primary-hover);
  --color-on-accent: #fff;
  --color-success: var(--ad-primary);
  --color-warning: var(--ad-warn);
  --color-error: var(--ad-alert);
}
.partner-task-usage .usage-filter-fields { grid-template-columns: repeat(auto-fit, minmax(min(100%, 180px), 220px)); }
.partner-task-usage .usage-filter-fields :deep(.app-select-button) { display: flex; justify-content: space-between; }
.partner-task-usage .usage-filter-bottom { justify-content: flex-end; }
.partner-task-usage .usage-table { min-width: 740px; }
.partner-task-usage .usage-charge,
.partner-task-usage .usage-summary-charge strong { display: inline-flex; align-items: center; gap: 6px; }
@media (max-width: 540px) {
  .partner-task-usage .usage-filter-fields { grid-template-columns: minmax(0, 1fr); }
}
</style>
