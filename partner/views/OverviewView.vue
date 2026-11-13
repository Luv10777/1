<script setup>
import { computed, ref } from 'vue'
import { AlertTriangle, ChevronLeft, ChevronRight, RefreshCw } from 'lucide-vue-next'
import { partnerApi, ledgerTypes } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { dateText, membershipState, number, sinceText, todoTypes } from '../../src/domain/partnerOps.js'
import { isOwner, loadPartner, partner } from '../partnerStore.js'
import TenantDrawer from '../components/TenantDrawer.vue'

// 工作台：每天打开先看要联系谁。待办按类分组，点一条打开商户详情；下面是额度和（负责人的）烟火流水。
const owner = computed(() => isOwner())
const overview = computed(() => partner.overview)
const workbench = ref(null)
const ledger = ref([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const error = ref('')
const openTenant = ref(null)
const pages = computed(() => Math.ceil(total.value / 20))
const groups = computed(() => Object.entries(todoTypes).map(([type, meta]) => ({ type, ...meta,
  items: (workbench.value?.todos || []).filter(t => t.type === type) })))
const todoTotal = computed(() => new Set((workbench.value?.todos || []).map(t => t.tenant.tenantId)).size)
const quotaLeft = computed(() => (overview.value?.quotas || []).reduce((sum, q) => sum + (q.planName ? q.remaining : 0), 0))

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
  const result = await partnerApi.ledger({ page: page.value, size: 20 })
  ledger.value = result.items
  total.value = result.total
}
function movePage(next) { page.value = next; loadLedger().catch(failure => { error.value = failure.message }) }

function detail(todo) {
  const t = todo.tenant
  switch (todo.type) {
    case 'EXPIRING': return `${dateText(t.memberUntil)} 到期 · ${membershipState(t).short}`
    case 'EXPIRED': return `${dateText(t.memberUntil)} 已过期`
    case 'LOW_BALANCE': return `余额 ${number(t.creditsAvailable)} · 最近一次充值 ${number(todo.lastTopUpCredits)}`
    case 'NOT_STARTED': return `开通至今没有使用 · ${t.memberActive ? `${dateText(t.memberUntil)} 到期` : '会员已过期'}`
    default: return `最近一次使用 ${sinceText(todo.lastUsedAt)}`
  }
}
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><h1>工作台</h1><p>{{ owner ? '每天先看这里：哪些商户要续费、要充值、要帮他用起来。点一条打开商户详情直接处理。' : '你名下商户的待办。点一条打开商户详情，可以直接续期。' }}</p></div>
      <div class="management-heading-actions"><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button></div>
    </header>
    <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>

    <section v-if="overview" class="partner-cards" aria-label="概况">
      <div class="partner-card"><span>今天要跟进</span><strong>{{ number(todoTotal) }}</strong><small>个商户有待办</small></div>
      <div class="partner-card"><span>{{ owner ? '名下商户' : '我名下的商户' }}</span><strong>{{ number(overview.tenantCount) }}</strong><small>按 UID 添加或兑换了激活码</small></div>
      <div v-if="owner" class="partner-card"><span>可分配烟火</span><strong>{{ number(overview.partner.creditsAvailable) }}</strong><small>平台发放，分给名下商户</small></div>
      <div class="partner-card"><span>激活码额度</span><strong>{{ number(quotaLeft) }}</strong><small>还能生成或直接续期的个数</small></div>
    </section>

    <section class="management-list" :aria-busy="loading" aria-labelledby="todo-title">
      <div class="mg-list-caption"><h2 id="todo-title">待办</h2><span v-if="workbench" class="mg-muted">更新于 {{ managementDate(workbench.checkedAt) }}</span></div>
      <div v-if="workbench && !workbench.todos.length" class="mg-state"><h3>今天没有待办</h3><p>商户都在正常使用，到期、余额不足或长时间没用时会出现在这里。</p></div>
      <div v-else class="partner-todo-groups">
        <div v-for="group in groups" :key="group.type" class="partner-todo-group" :class="{ empty: !group.items.length }">
          <header><strong>{{ group.title }}</strong><span class="partner-todo-count">{{ group.items.length }}</span><small>{{ group.hint }}</small></header>
          <p v-if="!group.items.length" class="mg-muted">没有</p>
          <ul v-else>
            <li v-for="todo in group.items" :key="group.type + todo.tenant.tenantId">
              <button type="button" @click="openTenant = todo.tenant.tenantId">
                <span><b>{{ todo.tenant.tenantName || '商户' }}</b><small>{{ todo.tenant.ownerName || '—' }}{{ owner && todo.tenant.bdName ? ` · BD ${todo.tenant.bdName}` : '' }}</small></span>
                <span class="partner-todo-detail"><AlertTriangle v-if="group.type === 'EXPIRING' || group.type === 'LOW_BALANCE'" :size="13" />{{ detail(todo) }}</span>
              </button>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <section class="management-list" aria-labelledby="quota-title">
      <div class="mg-list-caption"><h2 id="quota-title">激活码额度</h2></div>
      <div v-if="!overview?.quotas.length" class="mg-state"><h3>还没有激活码额度</h3><p>请联系{{ owner ? '平台' : '负责人' }}分配月卡、季卡、年卡的激活码额度。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>套餐</th><th class="code-number">总额度</th><th class="code-number">已生成</th><th class="code-number">还能生成</th></tr></thead>
          <tbody>
            <tr v-for="quota in overview.quotas" :key="quota.planCode">
              <td>{{ quota.planName ? `${quota.planName} · ${quota.durationDays} 天` : `${quota.planCode}（套餐已下架）` }}</td>
              <td class="code-number mg-mono">{{ number(quota.quota) }}</td>
              <td class="code-number mg-mono">{{ number(quota.used) }}</td>
              <td class="code-number mg-mono">{{ number(quota.remaining) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="owner" class="management-list" aria-labelledby="ledger-title">
      <div class="mg-list-caption"><h2 id="ledger-title">烟火流水</h2></div>
      <div v-if="!ledger.length && !loading" class="mg-state"><h3>还没有流水</h3><p>平台发放烟火、你分给商户烟火后都会记在这里。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>时间</th><th>类型</th><th>商户</th><th class="code-number">变动</th><th class="code-number">变动后</th><th>备注</th></tr></thead>
          <tbody>
            <tr v-for="item in ledger" :key="item.id">
              <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
              <td>{{ ledgerTypes[item.type] || item.type }}</td>
              <td>{{ item.tenantName || '—' }}</td>
              <td class="code-number mg-mono">{{ item.delta > 0 ? '+' : '' }}{{ number(item.delta) }}</td>
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
