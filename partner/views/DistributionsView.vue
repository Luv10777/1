<script setup>
import AppSelect from '../../src/components/AppSelect.vue'
import { computed, ref } from 'vue'
import { ChevronLeft, ChevronRight, RefreshCw } from 'lucide-vue-next'
import { partnerApi, ledgerTypes } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { number } from '../../src/domain/partnerOps.js'
import { can, loadPartner, loadTeam, partner, teamNames } from '../partnerStore.js'
import TenantDrawer from '../components/TenantDrawer.vue'
import MemberIdentity from '../components/MemberIdentity.vue'

/**
 * 烟火：团队账户的余额（大家在一方志创作和分给商户都扣它）、平台发放记录（财务权限）、分给商户的每一笔。
 * 有财务权限的看全部分配、可以按操作人筛；只有分配权限的只看自己分出去的。
 */
const finance = computed(() => can('finance'))
const account = computed(() => partner.overview?.account)
const names = computed(() => teamNames())
const records = ref([])
const total = ref(0)
const page = ref(0)
const actor = ref('')
const loading = ref(false)
const error = ref('')
const openTenant = ref(null)
const retrying = ref(null)
const pages = computed(() => Math.ceil(total.value / 20))
const grants = ref([])
const grantTotal = ref(0)
const grantPage = ref(0)
const grantPages = computed(() => Math.ceil(grantTotal.value / 10))

/** 补上未到账的一笔：同一个请求再走一遍，不会重复扣。 */
async function retry(item) {
  retrying.value = item.id
  try {
    const done = await partnerApi.retryDistribution(item.id)
    records.value = records.value.map(r => r.id === done.id ? done : r)
    await loadPartner(true)
  } catch (failure) {
    error.value = failure.message || '补上失败，请稍后再试。'
  } finally { retrying.value = null }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [result] = await Promise.all([
      partnerApi.distributions({ page: page.value, size: 20, actorUserId: finance.value ? actor.value : '' }),
      loadPartner(true),
      finance.value ? loadGrants() : null,
    ])
    records.value = result.items
    total.value = result.total
  } catch (failure) {
    records.value = []
    error.value = failure.message || '烟火记录暂时无法读取。'
  } finally { loading.value = false }
}
async function loadGrants() {
  const result = await partnerApi.ledger({ page: grantPage.value, size: 10 })
  grants.value = result.items
  grantTotal.value = result.total
}
function search() { page.value = 0; load() }
function movePage(next) { page.value = next; load() }
function moveGrantPage(next) { grantPage.value = next; loadGrants().catch(failure => { error.value = failure.message }) }
function open(item) { if (item.recipientType === 'TENANT') openTenant.value = item.recipientTenantId }
if (finance.value) loadTeam().catch(() => {})
load()
</script>

<template>
  <div class="management-page pw-page">
    <header class="pw-head">
      <div><p class="pw-date">平台发的烟火都进团队账户</p><h1>烟火</h1></div>
      <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
    </header>

    <section class="pw-kpis pw-kpis-2" aria-label="团队烟火">
      <article><span>团队烟火</span><strong class="mg-mono">{{ account ? number(account.available) : '—' }}</strong><small>赠送 {{ number(account?.gift) }} · 充值 {{ number(account?.purchased) }}</small></article>
      <article><span>怎么用</span><strong class="pw-kpi-text">创作 + 分给商户</strong><small>成员在一方志创作直接扣它；分给商户时先扣赠送，对方收到的性质不变。</small></article>
    </section>

    <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>

    <section v-if="finance" class="pw-card" aria-labelledby="grant-title">
      <header class="pw-card-head"><h2 id="grant-title">平台发放</h2><span class="mg-muted">线下收款后由平台发进团队账户</span></header>
      <div v-if="!grants.length && !loading" class="pw-empty"><p>还没有发放记录。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>时间</th><th>类型</th><th class="code-number">烟火</th><th>备注</th></tr></thead>
          <tbody>
            <tr v-for="item in grants" :key="item.id">
              <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
              <td><span>{{ ledgerTypes[item.type] || item.type }}</span><small v-if="item.tenantName">{{ item.tenantName }}</small></td>
              <td class="code-number mg-mono" :class="item.delta > 0 ? 'pw-plus' : ''">{{ item.delta > 0 ? '+' : '' }}{{ number(item.delta) }}</td>
              <td>{{ item.note || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="grantPages > 1" class="management-pagination"><span>共 {{ number(grantTotal) }} 条</span><div><button class="mg-page-button" type="button" :disabled="grantPage === 0" aria-label="上一页" @click="moveGrantPage(grantPage - 1)"><ChevronLeft :size="16" /></button><span>{{ grantPage + 1 }} / {{ grantPages }}</span><button class="mg-page-button" type="button" :disabled="grantPage + 1 >= grantPages" aria-label="下一页" @click="moveGrantPage(grantPage + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>

    <section class="pw-card" :aria-busy="loading" aria-labelledby="dist-title">
      <header class="pw-card-head"><h2 id="dist-title">分给商户</h2><span class="mg-muted">{{ finance ? '全部成员分出去的' : '你分出去的' }}</span></header>
      <form v-if="finance" class="management-filters" @submit.prevent="search">
        <label class="mg-select"><span class="sr-only">操作人</span><AppSelect v-model="actor" @change="search"><option value="">全部操作人</option><option v-for="m in partner.team" :key="m.userId" :value="String(m.userId)">{{ names[m.userId] }}</option></AppSelect></label>
      </form>
      <div v-if="!records.length && !loading" class="pw-empty"><strong>{{ actor ? '没有匹配的记录' : '还没有分配过烟火' }}</strong><p>在商户详情里分配烟火后，会记在这里。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>时间</th><th>分给</th><th class="code-number">烟火</th><th class="code-number">赠送 / 充值</th><th>扣款来源</th><th>备注</th><th>操作人</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="item in records" :key="item.id" :class="{ 'partner-row': item.recipientType === 'TENANT' }" :tabindex="item.recipientType === 'TENANT' ? 0 : undefined" @click="open(item)" @keydown.enter="open(item)">
              <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
              <td><span>{{ item.recipientName || '—' }}</span><small v-if="item.recipientType !== 'TENANT'">以前分给 BD 的</small></td>
              <td class="code-number mg-mono">{{ number(item.credits) }}</td>
              <td class="code-number mg-mono">{{ number(item.gift) }} / {{ number(item.purchased) }}</td>
              <td>{{ item.source === 'ACCOUNT' ? '团队账户' : item.source === 'POOL' ? '历史可分配额度' : '—' }}</td>
              <td>{{ item.note || '—' }}</td>
              <td><MemberIdentity :number="item.actorNumber" :name="item.actorName" /></td>
              <td><span v-if="item.status === 'DONE'" class="pw-plus">已到账</span><button v-else-if="can('distribute') && item.recipientType === 'TENANT'" class="mg-text-button partner-danger" type="button" :disabled="retrying === item.id" @click.stop="retry(item)">{{ retrying === item.id ? '正在补上…' : '未到账 · 补上' }}</button><span v-else class="partner-danger">未到账</span></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="pages > 1" class="management-pagination"><span>共 {{ number(total) }} 笔</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ page + 1 }} / {{ pages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= pages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>
    <p class="pw-note">「未到账」表示款项已从记录所示账户扣出，商户尚未入账。点「补上」可继续入账，无需再次扣款。</p>

    <TenantDrawer v-if="can('tenants')" :tenant-id="openTenant" @close="openTenant = null" @updated="load" />
  </div>
</template>
