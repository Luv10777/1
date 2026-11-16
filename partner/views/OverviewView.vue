<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, BarChart3, Coins, RefreshCw, Store, TicketCheck, Users } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { dateText, followUpTodos, groupTodos, membershipState, number, partnerRoles, sinceText, todoTypes } from '../../src/domain/partnerOps.js'
import { auth } from '../../src/stores/auth.js'
import { can, loadPartner, myName, partner } from '../partnerStore.js'
import TenantDrawer from '../components/TenantDrawer.vue'
import WelcomeDialog from '../components/WelcomeDialog.vue'

/**
 * 工作台：左边是今天要跟进的商户（一个商户一行，原因用标签列出，按紧急程度排），点一行打开商户详情处理；
 * 右边是团队烟火、本月激活码和常用入口。没有「商户」权限的成员只看到右边。
 */
const overview = computed(() => partner.overview)
const tenantsAllowed = computed(() => can('tenants'))
const workbench = ref(null)
const dueFollowUps = ref([])
const followUpError = ref('')
const filter = ref('')
const loading = ref(false)
const error = ref('')
const openTenant = ref(null)
const openTab = ref('overview')
// 预约跟进放在工作台待办之后，同一商户合并成一行时用工作台那份更全的商户信息。
const todos = computed(() => [...(workbench.value?.todos || []), ...followUpTodos(dueFollowUps.value)])
const allRows = computed(() => groupTodos(todos.value))
const rows = computed(() => groupTodos(todos.value, filter.value))
const tabs = computed(() => [{ type: '', label: '全部', count: allRows.value.length },
  ...Object.entries(todoTypes).map(([type, meta]) => ({ type, label: meta.short,
    count: type === 'FOLLOW_UP' ? dueFollowUps.value.length : workbench.value?.counts?.[type] || 0 }))])
/** 只因预约跟进出现的商户直接打开「跟进」页签。 */
function openRow(row) {
  openTab.value = row.reasons[0].type === 'FOLLOW_UP' ? 'follow' : 'overview'
  openTenant.value = row.tenant.tenantId
}
const today = computed(() => new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }))
const router = useRouter()
// 欢迎弹窗：每个账号每天进入工作台时弹一次；第一次进来要先填团队称呼，填好再弹。
const welcomeKey = () => `partner-welcome-${auth.user?.id}-${new Date().toLocaleDateString('sv-SE')}`
const welcomeDue = ref(false)
const welcome = computed(() => welcomeDue.value && Boolean(overview.value?.displayName))
const expiringCount = computed(() => workbench.value?.counts?.EXPIRING || 0)
function closeWelcome() {
  welcomeDue.value = false
  try { localStorage.setItem(welcomeKey(), '1') } catch { /* 本地存储不可用时这次照常关闭 */ }
}
function welcomeGo(name) { closeWelcome(); router.push({ name }) }

async function load() {
  loading.value = true
  error.value = ''
  try {
    await loadPartner(true)
    if (tenantsAllowed.value) {
      workbench.value = await partnerApi.workbench()
      followUpError.value = ''
      try { dueFollowUps.value = await partnerApi.dueFollowUps() } catch (failure) { dueFollowUps.value = []; followUpError.value = failure.message || '预约跟进暂时无法读取' }
    }
    try { if (!localStorage.getItem(welcomeKey())) welcomeDue.value = true } catch { welcomeDue.value = true }
  } catch (failure) {
    error.value = failure.message || '工作台暂时无法读取。'
  } finally { loading.value = false }
}
// 换了账号（例如别的标签页重新登录）时重读。
watch(() => auth.user?.id, (id, old) => { if (id && old && id !== old) load() })

/** 每个原因一句话说明，放在标签下面。 */
function fact(todo) {
  const t = todo.tenant
  switch (todo.type) {
    case 'EXPIRING': return `${dateText(t.memberUntil)} 到期，${membershipState(t).short}`
    case 'EXPIRED': return `${dateText(t.memberUntil)} 已过期`
    case 'LOW_BALANCE': return `余额 ${number(t.creditsAvailable)}，上次充值 ${number(todo.lastTopUpCredits)}`
    case 'NOT_STARTED': return '开通后还没用过 AI 功能'
    case 'FOLLOW_UP': return Date.parse(todo.at) < Date.now() ? `约在 ${managementDate(todo.at)}，已到时间` : `约在 ${managementDate(todo.at)}`
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
        <h1 v-if="tenantsAllowed">{{ myName() ? `${myName()}，` : '' }}{{ allRows.length ? `有 ${allRows.length} 个商户待跟进` : '今天没有需要跟进的商户' }}</h1>
        <h1 v-else>{{ myName() ? `${myName()}，欢迎回来` : '欢迎回来' }}</h1>
      </div>
      <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
    </header>
    <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>

    <div class="pw-layout">
      <section v-if="tenantsAllowed" class="pw-card pw-main" :aria-busy="loading" aria-labelledby="todo-title">
        <header class="pw-card-head">
          <h2 id="todo-title">待跟进商户</h2>
          <span v-if="workbench" class="mg-muted">更新于 {{ managementDate(workbench.checkedAt).slice(11) }}</span>
        </header>
        <nav class="pw-tabs" aria-label="按原因筛选">
          <button v-for="tab in tabs" :key="tab.type" type="button" :class="{ active: filter === tab.type }" :aria-pressed="filter === tab.type" @click="filter = tab.type">
            {{ tab.label }}<span>{{ tab.count }}</span>
          </button>
        </nav>
        <p v-if="followUpError" class="mg-inline-error pw-inline-note" role="alert">{{ followUpError }}，下面只列出工作台待办。</p>
        <div v-if="workbench && !rows.length" class="pw-empty">
          <strong>{{ filter ? '这一类没有商户' : '都处理好了' }}</strong>
          <p>预约了跟进、到期、余额不足、开通没用或 7 天没用的商户会自动出现在这里。</p>
        </div>
        <ul v-else class="pw-todos">
          <li v-for="row in rows" :key="row.tenant.tenantId">
            <button type="button" @click="openRow(row)">
              <span class="pw-todo-who">
                <b>{{ row.tenant.tenantName || '商户' }}</b>
                <small>{{ row.tenant.ownerName || '—' }}{{ row.tenant.addedByName ? ` · ${row.tenant.addedByName} 添加` : '' }}</small>
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
          <header class="pw-card-head"><h2 id="res-title">团队资源</h2></header>
          <div class="pw-card-body">
            <div class="pw-balance-pair">
              <div class="pw-balance">
                <span>团队烟火</span>
                <strong class="mg-mono">{{ overview?.account ? number(overview.account.available) : '—' }}</strong>
                <small v-if="overview?.account">赠送 {{ number(overview.account.gift) }} · 充值 {{ number(overview.account.purchased) }}</small>
              </div>
              <div class="pw-balance">
                <span>本月生成激活码</span>
                <strong class="mg-mono">{{ number(overview?.monthCodes) }}</strong>
                <small>不限个数</small>
              </div>
              <p class="pw-balance-hint">平台发的烟火都进团队账户：大家在一方志创作直接用它，{{ can('distribute') ? '你也可以在商户详情里分给名下商户。' : '分给商户要有「分配烟火」权限。' }}</p>
            </div>
          </div>
        </section>
        <section class="pw-card" aria-labelledby="go-title">
          <header class="pw-card-head"><h2 id="go-title">常用</h2></header>
          <nav class="pw-links">
            <RouterLink v-if="can('tenants')" :to="{ name: 'partner-tenants' }"><Store :size="16" /><span>名下商户<small>{{ number(overview?.tenantCount) }} 个 · 添加、续期{{ can('distribute') ? '、分烟火' : '' }}</small></span><ArrowRight :size="14" /></RouterLink>
            <RouterLink v-if="can('codes')" :to="{ name: 'partner-codes' }"><TicketCheck :size="16" /><span>激活码<small>生成、作废、导出</small></span><ArrowRight :size="14" /></RouterLink>
            <RouterLink v-if="can('tenants')" :to="{ name: 'partner-dashboard' }"><BarChart3 :size="16" /><span>经营看板<small>使用、烟火、激活码统计</small></span><ArrowRight :size="14" /></RouterLink>
            <RouterLink v-if="can('finance') || can('distribute')" :to="{ name: 'partner-credits' }"><Coins :size="16" /><span>烟火<small>平台发放和分配记录</small></span><ArrowRight :size="14" /></RouterLink>
            <RouterLink :to="{ name: 'partner-team' }"><Users :size="16" /><span>团队<small>{{ can('team') ? '加成员、设权限' : '看看都有谁' }}</small></span><ArrowRight :size="14" /></RouterLink>
          </nav>
        </section>
      </aside>
    </div>

    <TenantDrawer v-if="tenantsAllowed" :tenant-id="openTenant" :tab="openTab" @close="openTenant = null" @updated="load" />
    <WelcomeDialog
      :open="welcome" :partner-name="overview?.partner.name || ''" :person-name="myName()" :role-name="partnerRoles[overview?.role] || ''"
      :todos="allRows.length" :expiring="expiringCount" :tenants="overview?.tenantCount || 0" :balance="overview?.account?.available ?? null"
      :month-codes="overview?.monthCodes || 0" :can-dashboard="tenantsAllowed" @close="closeWelcome" @go="welcomeGo"
    />
  </div>
</template>
