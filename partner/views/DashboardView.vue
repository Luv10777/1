<script setup>
import AppSelect from '../../src/components/AppSelect.vue'
import { computed, ref } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { capabilityBars, dashboardRanges, memberSegments, number, percent, sinceText } from '../../src/domain/partnerOps.js'
import { bds, isOwner, loadTeam } from '../partnerStore.js'
import TenantDrawer from '../components/TenantDrawer.vue'
import DailyBars from '../components/DailyBars.vue'

/**
 * 经营看板，从上到下：四个核心指标 → 每天趋势和会员状态 → 按功能消耗和商户排行 → （负责人）烟火去向和 BD 业绩。
 * 只有烟火数和使用次数，不含作品内容。
 */
const owner = computed(() => isOwner())
const days = ref(30)
const bdFilter = ref('')
const data = ref(null)
const loading = ref(false)
const error = ref('')
const metric = ref('consumed')
const openTenant = ref(null)
const metrics = computed(() => [
  { key: 'consumed', label: '消耗烟火' },
  ...(owner.value ? [{ key: 'transferred', label: '分给商户' }] : []),
  { key: 'redeemed', label: '激活码兑换' },
  { key: 'newTenants', label: '新增商户' },
])
const bars = computed(() => capabilityBars(data.value?.consumedByCapability))
const segments = computed(() => memberSegments(data.value))
const segmentTotal = computed(() => segments.value.reduce((sum, s) => sum + s.value, 0))
const range = computed(() => dashboardRanges.find(r => r.value === days.value)?.label || '')
const scope = computed(() => !owner.value ? '我名下' : bdFilter.value === '' ? '全部' : bdFilter.value === '0' ? '我直接管的'
  : `BD ${bds().find(b => String(b.userId) === bdFilter.value)?.name || ''}`)

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
if (owner.value) loadTeam().catch(() => {})
load()
</script>

<template>
  <div class="management-page pw-page">
    <header class="pw-head">
      <div><p class="pw-date">{{ scope }}商户 · {{ range }}</p><h1>经营看板</h1></div>
      <div class="pw-head-tools">
        <label v-if="owner && bds().length" class="mg-select"><span class="sr-only">范围</span><AppSelect v-model="bdFilter" aria-label="按 BD 查看" @change="load"><option value="">全部商户</option><option value="0">我直接管的</option><option v-for="b in bds()" :key="b.userId" :value="String(b.userId)">BD · {{ b.name || b.uid }}</option></AppSelect></label>
        <div class="partner-segment" role="group" aria-label="时间范围">
          <button v-for="r in dashboardRanges" :key="r.value" type="button" :class="{ active: days === r.value }" :aria-pressed="days === r.value" :disabled="loading" @click="pickRange(r.value)">{{ r.label }}</button>
        </div>
        <button class="mg-page-button" type="button" :disabled="loading" aria-label="刷新" @click="load"><RefreshCw :size="16" :class="{ 'mg-spinning': loading }" /></button>
      </div>
    </header>

    <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
    <div v-else-if="!data" class="mg-state"><p>正在统计…</p></div>
    <template v-else>
      <section class="pw-kpis" aria-label="核心指标">
        <article>
          <span>商户消耗烟火</span>
          <strong class="mg-mono">{{ number(data.consumed) }}</strong>
          <small>{{ number(data.uses) }} 次使用</small>
        </article>
        <article>
          <span>活跃商户</span>
          <strong class="mg-mono">{{ number(data.activeTenants) }}<em> / {{ number(data.tenants) }}</em></strong>
          <small>活跃率 {{ percent(data.activeTenants, data.tenants) }} · 首次使用 {{ number(data.firstUses) }}</small>
        </article>
        <article>
          <span>激活码兑换</span>
          <strong class="mg-mono">{{ number(data.codesRedeemed) }}</strong>
          <small>其中直接续期 {{ number(data.renewals) }} · 新生成 {{ number(data.codesGenerated) }}</small>
        </article>
        <article>
          <span>新增商户</span>
          <strong class="mg-mono">{{ number(data.newTenants) }}</strong>
          <small>名下共 {{ number(data.tenants) }} 个</small>
        </article>
      </section>

      <div class="pw-grid pw-grid-2-1">
        <section class="pw-card" aria-labelledby="trend-title">
          <header class="pw-card-head">
            <h2 id="trend-title">每天趋势</h2>
            <div class="partner-segment small" role="group" aria-label="趋势指标"><button v-for="m in metrics" :key="m.key" type="button" :class="{ active: metric === m.key }" :aria-pressed="metric === m.key" @click="metric = m.key">{{ m.label }}</button></div>
          </header>
          <DailyBars :points="data.daily" :field="metric" :label="metrics.find(m => m.key === metric)?.label || ''" />
        </section>
        <section class="pw-card" aria-labelledby="member-title">
          <header class="pw-card-head"><h2 id="member-title">会员状态</h2><span class="mg-muted">按现在</span></header>
          <div class="pw-card-body">
            <p class="pw-big mg-mono">{{ number(data.activeMembers) }}<small> 个会员有效</small></p>
            <div class="pw-stack" role="img" :aria-label="segments.map(s => `${s.label} ${s.value}`).join('，')">
              <b v-for="s in segments.filter(x => x.value)" :key="s.key" :class="`pw-seg-${s.key}`" :style="{ flexGrow: s.value }" />
            </div>
            <ul class="pw-legend">
              <li v-for="s in segments" :key="s.key"><i :class="`pw-seg-${s.key}`" /><span>{{ s.label }}</span><b class="mg-mono">{{ number(s.value) }}</b><small>{{ percent(s.value, segmentTotal) }}</small></li>
            </ul>
          </div>
        </section>
      </div>

      <div class="pw-grid pw-grid-1-1">
        <section class="pw-card" aria-labelledby="capability-title">
          <header class="pw-card-head"><h2 id="capability-title">按功能的消耗</h2><span class="mg-muted">烟火</span></header>
          <p v-if="!bars.length" class="pw-empty">{{ range }}没有消耗。</p>
          <ul v-else class="partner-hbars">
            <li v-for="b in bars" :key="b.key"><span>{{ b.label }}</span><i><b :style="{ width: Math.max(2, b.ratio * 100) + '%' }" /></i><strong class="mg-mono">{{ number(b.value) }}</strong></li>
          </ul>
        </section>
        <section class="pw-card" aria-labelledby="top-title">
          <header class="pw-card-head"><h2 id="top-title">消耗最多的商户</h2><span class="mg-muted">前 10</span></header>
          <p v-if="!data.topTenants.length" class="pw-empty">{{ range }}没有商户使用。</p>
          <ol v-else class="pw-rank">
            <li v-for="(t, i) in data.topTenants" :key="t.tenantId">
              <button type="button" @click="openTenant = t.tenantId">
                <em class="mg-mono">{{ i + 1 }}</em>
                <span><b>{{ t.tenantName || '商户' }}</b><small>{{ number(t.uses) }} 次 · 最近 {{ sinceText(t.lastUsedAt) }}{{ owner && t.bdName ? ` · BD ${t.bdName}` : '' }}</small></span>
                <strong class="mg-mono">{{ number(t.consumed) }}</strong>
              </button>
            </li>
          </ol>
        </section>
      </div>

      <div v-if="owner" class="pw-grid pw-grid-1-2">
        <section class="pw-card" aria-labelledby="flow-title">
          <header class="pw-card-head"><h2 id="flow-title">烟火去向</h2><span class="mg-muted">{{ range }}</span></header>
          <dl class="pw-flow">
            <div v-if="data.granted != null"><dt>平台发放</dt><dd class="mg-mono pw-plus">+{{ number(data.granted) }}</dd></div>
            <div><dt>分给商户</dt><dd class="mg-mono">−{{ number(data.transferred) }}</dd></div>
            <div class="pw-flow-total"><dt>现在可分配</dt><dd class="mg-mono">{{ number(data.creditsAvailable) }}</dd></div>
          </dl>
        </section>
        <section class="pw-card" aria-labelledby="bd-title">
          <header class="pw-card-head"><h2 id="bd-title">BD 业绩</h2><span class="mg-muted">{{ range }}</span></header>
          <p v-if="!data.bds.length" class="pw-empty">{{ bdFilter ? '按 BD 查看时不显示业绩对比。' : '还没有 BD，可在「团队」页添加。' }}</p>
          <div v-else class="management-table-wrap">
            <table class="management-table">
              <thead><tr><th>BD</th><th class="code-number">商户</th><th class="code-number">活跃</th><th class="code-number">消耗</th><th class="code-number">兑换</th><th class="code-number">续期</th></tr></thead>
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
      </div>
      <p class="pw-note">只统计烟火数和使用次数，不含商户的作品内容。作废的激活码 {{ number(data.codesVoided) }} 个，额度不退回。</p>
    </template>

    <TenantDrawer :tenant-id="openTenant" @close="openTenant = null" @updated="load" />
  </div>
</template>
