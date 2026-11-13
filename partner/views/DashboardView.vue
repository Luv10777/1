<script setup>
import { computed, ref } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { capabilityBars, dashboardRanges, number, sinceText } from '../../src/domain/partnerOps.js'
import { bds, isOwner, loadTeam } from '../partnerStore.js'
import TenantDrawer from '../components/TenantDrawer.vue'
import DailyBars from '../components/DailyBars.vue'

// 经营看板：选时间段（和 BD），看商户、使用、烟火和激活码的数字、每天的趋势和排行。只有使用量，不含作品内容。
const owner = computed(() => isOwner())
const days = ref(30)
const bdFilter = ref('')
const data = ref(null)
const loading = ref(false)
const error = ref('')
const metric = ref('consumed')
const openTenant = ref(null)
const metrics = computed(() => [
  { key: 'consumed', label: '商户消耗烟火' },
  ...(owner.value ? [{ key: 'transferred', label: '分给商户烟火' }] : []),
  { key: 'newTenants', label: '新增商户' },
  { key: 'redeemed', label: '激活码兑换' },
])
const bars = computed(() => capabilityBars(data.value?.consumedByCapability))
const rangeLabel = computed(() => dashboardRanges.find(r => r.value === days.value)?.label || '')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const query = { days: days.value }
    if (owner.value && bdFilter.value !== '') query.bdUserId = bdFilter.value
    data.value = await partnerApi.dashboard(query)
    if (!metrics.value.some(m => m.key === metric.value)) metric.value = 'consumed'
  } catch (failure) {
    error.value = failure.message || '看板暂时无法读取。'
  } finally { loading.value = false }
}
function pickRange(value) { days.value = value; load() }
function pickBd(value) { bdFilter.value = value; load() }
if (owner.value) loadTeam().catch(() => {})
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><h1>经营看板</h1><p>{{ owner ? '名下商户的使用、你分出的烟火和激活码情况。' : '你名下商户的使用和你生成的激活码。' }}只统计烟火数和使用次数，不含商户的作品内容。</p></div>
      <div class="management-heading-actions"><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button></div>
    </header>

    <div class="partner-dash-filters">
      <div class="partner-segment" role="group" aria-label="时间范围">
        <button v-for="r in dashboardRanges" :key="r.value" type="button" :class="{ active: days === r.value }" :aria-pressed="days === r.value" :disabled="loading" @click="pickRange(r.value)">{{ r.label }}</button>
      </div>
      <div v-if="owner && bds().length" class="partner-segment" role="group" aria-label="按 BD 查看">
        <button type="button" :class="{ active: bdFilter === '' }" :disabled="loading" @click="pickBd('')">全部</button>
        <button type="button" :class="{ active: bdFilter === '0' }" :disabled="loading" @click="pickBd('0')">我直接管的</button>
        <button v-for="b in bds()" :key="b.userId" type="button" :class="{ active: bdFilter === String(b.userId) }" :disabled="loading" @click="pickBd(String(b.userId))">{{ b.name || b.uid }}</button>
      </div>
    </div>

    <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
    <template v-else-if="data">
      <section class="partner-kpis" aria-label="商户">
        <h2>商户</h2>
        <div><span>名下商户</span><strong>{{ number(data.tenants) }}</strong><small>{{ rangeLabel }}新增 {{ number(data.newTenants) }}</small></div>
        <div><span>会员有效</span><strong>{{ number(data.activeMembers) }}</strong><small>7 天内到期 {{ number(data.expiringSoon) }}</small></div>
        <div><span>活跃商户</span><strong>{{ number(data.activeTenants) }}</strong><small>{{ rangeLabel }}用过 AI 功能</small></div>
        <div><span>首次使用</span><strong>{{ number(data.firstUses) }}</strong><small>{{ rangeLabel }}第一次用的商户</small></div>
      </section>
      <section class="partner-kpis" aria-label="使用与烟火">
        <h2>使用与烟火</h2>
        <div><span>商户消耗</span><strong>{{ number(data.consumed) }}</strong><small>烟火 · {{ number(data.uses) }} 次使用</small></div>
        <div v-if="owner"><span>分给商户</span><strong>{{ number(data.transferred) }}</strong><small>烟火</small></div>
        <div v-if="owner && data.granted != null"><span>平台发放</span><strong>{{ number(data.granted) }}</strong><small>烟火</small></div>
        <div v-if="owner && data.creditsAvailable != null"><span>可分配</span><strong>{{ number(data.creditsAvailable) }}</strong><small>烟火 · 当前</small></div>
      </section>
      <section class="partner-kpis" aria-label="激活码">
        <h2>激活码{{ owner ? '' : '（我生成的）' }}</h2>
        <div><span>生成</span><strong>{{ number(data.codesGenerated) }}</strong><small>含直接续期</small></div>
        <div><span>兑换</span><strong>{{ number(data.codesRedeemed) }}</strong><small>开通或续期成功</small></div>
        <div><span>直接续期</span><strong>{{ number(data.renewals) }}</strong><small>用额度替商户续的</small></div>
        <div><span>作废</span><strong>{{ number(data.codesVoided) }}</strong><small>额度不退回</small></div>
      </section>

      <section class="management-list" aria-labelledby="trend-title">
        <div class="mg-list-caption">
          <h2 id="trend-title">每天趋势</h2>
          <div class="partner-segment small" role="group" aria-label="趋势指标"><button v-for="m in metrics" :key="m.key" type="button" :class="{ active: metric === m.key }" @click="metric = m.key">{{ m.label }}</button></div>
        </div>
        <DailyBars :points="data.daily" :field="metric" :label="metrics.find(m => m.key === metric)?.label || ''" />
      </section>

      <div class="partner-dash-grid">
        <section class="management-list" aria-labelledby="capability-title">
          <div class="mg-list-caption"><h2 id="capability-title">按功能的消耗</h2></div>
          <p v-if="!bars.length" class="mg-state">{{ rangeLabel }}没有消耗。</p>
          <ul v-else class="partner-hbars">
            <li v-for="b in bars" :key="b.key"><span>{{ b.label }}</span><i><b :style="{ width: Math.max(2, b.ratio * 100) + '%' }" /></i><strong class="mg-mono">{{ number(b.value) }}</strong></li>
          </ul>
        </section>
        <section class="management-list" aria-labelledby="top-title">
          <div class="mg-list-caption"><h2 id="top-title">消耗最多的商户</h2></div>
          <p v-if="!data.topTenants.length" class="mg-state">{{ rangeLabel }}没有商户使用。</p>
          <table v-else class="management-table">
            <thead><tr><th>商户</th><th class="code-number">消耗</th><th class="code-number">次数</th><th>最近使用</th></tr></thead>
            <tbody>
              <tr v-for="t in data.topTenants" :key="t.tenantId" class="partner-row" tabindex="0" @click="openTenant = t.tenantId" @keydown.enter="openTenant = t.tenantId">
                <td><span>{{ t.tenantName || '商户' }}</span><small v-if="owner && t.bdName">BD {{ t.bdName }}</small></td>
                <td class="code-number mg-mono">{{ number(t.consumed) }}</td>
                <td class="code-number mg-mono">{{ number(t.uses) }}</td>
                <td>{{ sinceText(t.lastUsedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </div>

      <section v-if="owner && data.bds.length" class="management-list" aria-labelledby="bd-title">
        <div class="mg-list-caption"><h2 id="bd-title">BD 业绩（{{ rangeLabel }}）</h2></div>
        <div class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>BD</th><th class="code-number">名下商户</th><th class="code-number">活跃商户</th><th class="code-number">商户消耗</th><th class="code-number">激活码兑换</th><th class="code-number">直接续期</th></tr></thead>
            <tbody>
              <tr v-for="b in data.bds" :key="b.userId">
                <td>{{ b.name || '—' }}</td>
                <td class="code-number mg-mono">{{ number(b.tenants) }}</td>
                <td class="code-number mg-mono">{{ number(b.activeTenants) }}</td>
                <td class="code-number mg-mono">{{ number(b.consumed) }}</td>
                <td class="code-number mg-mono">{{ number(b.codesRedeemed) }}</td>
                <td class="code-number mg-mono">{{ number(b.renewals) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
    <div v-else-if="loading" class="mg-state"><p>正在统计…</p></div>

    <TenantDrawer :tenant-id="openTenant" @close="openTenant = null" @updated="load" />
  </div>
</template>
