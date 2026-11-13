<script setup>
import { computed, ref } from 'vue'
import { ArrowRight, BarChart3, ChevronLeft, ChevronRight, Coins, Plus, RefreshCw, Store, TicketCheck } from 'lucide-vue-next'
import { partnerApi, ledgerTypes } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { dateText, groupTodos, membershipState, number, sinceText, todoTypes } from '../../src/domain/partnerOps.js'
import { auth } from '../../src/stores/auth.js'
import { isOwner, loadPartner, partner } from '../partnerStore.js'
import TenantDrawer from '../components/TenantDrawer.vue'

/**
 * 工作台：左边是今天要跟进的商户（一个商户一行，原因用标签列出，按紧急程度排），点一行打开商户详情处理；
 * 右边是手上的资源（可分配烟火、激活码额度）和常用入口；负责人下方有烟火流水。
 */
const owner = computed(() => isOwner())
const overview = computed(() => partner.overview)
const workbench = ref(null)
const filter = ref('')
const ledger = ref([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const error = ref('')
const openTenant = ref(null)
const pages = computed(() => Math.ceil(total.value / 10))
const allRows = computed(() => groupTodos(workbench.value?.todos))
const rows = computed(() => groupTodos(workbench.value?.todos, filter.value))
const tabs = computed(() => [{ type: '', label: '全部', count: allRows.value.length },
  ...Object.entries(todoTypes).map(([type, meta]) => ({ type, label: meta.short, count: workbench.value?.counts?.[type] || 0 }))])
const quotas = computed(() => (overview.value?.quotas || []).filter(q => q.planName))
const today = computed(() => new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [bench] = await Promise.all([partnerApi.workbench(), loadPartner(true)])
    workbench.value = bench
    if (owner.value) await loadLedger()
  } catch (failure) {
    error.value = failure.message || '工作台暂时无法读取。'
  } finally { loading.value = false }
}
async function loadLedger() {
  const result = await partnerApi.ledger({ page: page.value, size: 10 })
  ledger.value = result.items
  total.value = result.total
}
function movePage(next) { page.value = next; loadLedger().catch(failure => { error.value = failure.message }) }

/** 每个原因一句话说明，放在标签下面。 */
function fact(todo) {
  const t = todo.tenant
  switch (todo.type) {
    case 'EXPIRING': return `${dateText(t.memberUntil)} 到期，${membershipState(t).short}`
    case 'EXPIRED': return `${dateText(t.memberUntil)} 已过期`
    case 'LOW_BALANCE': return `余额 ${number(t.creditsAvailable)}，上次充值 ${number(todo.lastTopUpCredits)}`
    case 'NOT_STARTED': return '开通后还没用过 AI 功能'
    default: return `最近一次使用在 ${sinceText(todo.lastUsedAt)}`
  }
}
load()
</script>

<template>
  <div class="management-page pw-page">
    <header class="pw-head">
      <div>
        <p class="pw-date">{{ today }}</p>
        <h1>{{ auth.user?.name ? `${auth.user.name}，` : '' }}{{ allRows.length ? `今天有 ${allRows.length} 个商户需要跟进` : '今天没有需要跟进的商户' }}</h1>
      </div>
      <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
    </header>
    <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>

    <div class="pw-layout">
      <section class="pw-card pw-main" :aria-busy="loading" aria-labelledby="todo-title">
        <header class="pw-card-head">
          <h2 id="todo-title">待跟进商户</h2>
          <span v-if="workbench" class="mg-muted">更新于 {{ managementDate(workbench.checkedAt).slice(11) }}</span>
        </header>
        <nav class="pw-tabs" aria-label="按原因筛选">
          <button v-for="tab in tabs" :key="tab.type" type="button" :class="{ active: filter === tab.type }" :aria-pressed="filter === tab.type" @click="filter = tab.type">
            {{ tab.label }}<span>{{ tab.count }}</span>
          </button>
        </nav>
        <div v-if="workbench && !rows.length" class="pw-empty">
          <strong>{{ filter ? '这一类没有商户' : '都处理好了' }}</strong>
          <p>到期、余额不足、开通没用或 7 天没用的商户会自动出现在这里。</p>
        </div>
        <ul v-else class="pw-todos">
          <li v-for="row in rows" :key="row.tenant.tenantId">
            <button type="button" @click="openTenant = row.tenant.tenantId">
              <span class="pw-todo-who">
                <b>{{ row.tenant.tenantName || '商户' }}</b>
                <small>{{ row.tenant.ownerName || '—' }}{{ owner ? ` · ${row.tenant.bdName ? `BD ${row.tenant.bdName}` : '我直接管'}` : '' }}</small>
              </span>
              <span class="pw-todo-why">
                <span v-for="r in row.reasons" :key="r.type" class="pw-reason"><i :class="`pw-tag ${todoTypes[r.type].tone}`">{{ todoTypes[r.type].short }}</i>{{ fact(r) }}</span>
              </span>
              <span class="pw-todo-go">处理<ArrowRight :size="14" /></span>
            </button>
          </li>
        </ul>
      </section>

      <aside class="pw-side">
        <section class="pw-card" aria-labelledby="res-title">
          <header class="pw-card-head"><h2 id="res-title">我的资源</h2></header>
          <div class="pw-card-body">
            <div v-if="owner" class="pw-balance-pair">
              <div class="pw-balance">
                <span>服务商可分配烟火</span>
                <strong class="mg-mono">{{ number(overview?.partner.creditsAvailable) }}</strong>
                <small>平台发放</small>
              </div>
              <div class="pw-balance">
                <span>我的账号</span>
                <strong class="mg-mono">{{ number(overview?.account?.available) }}</strong>
                <small>赠送 {{ number(overview?.account?.gift) }} · 充值 {{ number(overview?.account?.purchased) }}</small>
              </div>
              <p class="pw-balance-hint">两边的烟火都能分给名下商户和 BD，在商户或成员详情里选择从哪里扣。<RouterLink :to="{ name: 'partner-distributions' }">分配记录</RouterLink></p>
            </div>
            <div class="pw-quotas">
              <p class="pw-label">激活码额度</p>
              <p v-if="!quotas.length" class="mg-muted">还没有额度，请联系{{ owner ? '平台' : '负责人' }}。</p>
              <div v-for="q in quotas" :key="q.planCode" class="pw-quota">
                <div><span>{{ q.planName }} · {{ q.durationDays }} 天</span><b class="mg-mono">{{ number(q.remaining) }}<small> / {{ number(q.quota) }}</small></b></div>
                <i><b :style="{ width: (q.quota ? q.remaining / q.quota * 100 : 0) + '%' }" /></i>
              </div>
            </div>
          </div>
        </section>
        <section class="pw-card" aria-labelledby="go-title">
          <header class="pw-card-head"><h2 id="go-title">常用</h2></header>
          <nav class="pw-links">
            <RouterLink :to="{ name: 'partner-tenants' }"><Store :size="16" /><span>名下商户<small>{{ number(overview?.tenantCount) }} 个 · 添加、续期{{ owner ? '、分烟火' : '' }}</small></span><ArrowRight :size="14" /></RouterLink>
            <RouterLink :to="{ name: 'partner-codes' }"><TicketCheck :size="16" /><span>激活码<small>生成、作废、导出</small></span><ArrowRight :size="14" /></RouterLink>
            <RouterLink :to="{ name: 'partner-dashboard' }"><BarChart3 :size="16" /><span>经营看板<small>使用、烟火、激活码统计</small></span><ArrowRight :size="14" /></RouterLink>
            <RouterLink v-if="owner" :to="{ name: 'partner-team' }"><Plus :size="16" /><span>团队<small>添加 BD、看业绩、分烟火给 BD</small></span><ArrowRight :size="14" /></RouterLink>
            <RouterLink v-if="owner" :to="{ name: 'partner-distributions' }"><Coins :size="16" /><span>分配记录<small>分给商户和 BD 的每一笔</small></span><ArrowRight :size="14" /></RouterLink>
          </nav>
        </section>
      </aside>
    </div>

    <section v-if="owner" class="pw-card" aria-labelledby="ledger-title">
      <header class="pw-card-head"><h2 id="ledger-title"><Coins :size="16" />烟火流水</h2><span class="mg-muted">平台发放与分给商户</span></header>
      <div v-if="!ledger.length && !loading" class="pw-empty"><p>还没有流水。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>时间</th><th>类型</th><th>商户</th><th class="code-number">变动</th><th class="code-number">变动后</th><th>备注</th></tr></thead>
          <tbody>
            <tr v-for="item in ledger" :key="item.id">
              <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
              <td>{{ ledgerTypes[item.type] || item.type }}</td>
              <td>{{ item.tenantName || '—' }}</td>
              <td class="code-number mg-mono" :class="item.delta > 0 ? 'pw-plus' : ''">{{ item.delta > 0 ? '+' : '' }}{{ number(item.delta) }}</td>
              <td class="code-number mg-mono">{{ number(item.availableAfter) }}</td>
              <td>{{ item.note || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="pages > 1" class="management-pagination"><span>共 {{ number(total) }} 条</span><div><button class="mg-page-button" type="button" :disabled="page === 0" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ page + 1 }} / {{ pages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= pages" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>

    <TenantDrawer :tenant-id="openTenant" @close="openTenant = null" @updated="load" />
  </div>
</template>
