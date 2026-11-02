<script setup>
import AppSelect from '../components/AppSelect.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ChevronRight, Flame, Gift, RefreshCw, Search, ShoppingCart, Wallet, X, ClipboardList } from 'lucide-vue-next'
import { grantedSources, ledgerAdminApi as api, purchasedSources } from '../services/ledgerAdminApi'
import { auth } from '../stores/auth'
import '../platform-management.css'

const overview = ref(null)
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const sort = ref('available')
let version = 0

const number = value => value == null ? '—' : Number(value).toLocaleString('zh-CN')
const sorts = { available: '按当前可用', consumed: '按累计消耗', purchased: '按累计购买', granted: '按累计赠送' }
const available = merchant => merchant.giftAvailable + merchant.purchasedAvailable
const stats = computed(() => [
  { label: '购买的烟火', value: overview.value?.purchased, helper: '在线充值与充值兑换码，累计', icon: ShoppingCart },
  { label: '赠送的烟火', value: overview.value?.granted, helper: '会员、平台赠送与赠送兑换码，累计', icon: Gift },
  { label: '已消耗', value: overview.value?.consumed, helper: '交付后实际扣除，预占和退回不计', icon: Flame },
  { label: '商家现有烟火', value: overview.value?.available, helper: overview.value ? `另有 ${number(overview.value.held)} 生成中预占` : '全平台可用余额合计', icon: Wallet },
])
const breakdown = (totals, labels) => Object.entries(totals || {}).filter(([, value]) => value).map(([key, value]) => ({ label: labels[key] || key, value }))
const purchasedBreakdown = computed(() => breakdown(overview.value?.purchasedBySource, purchasedSources))
const grantedBreakdown = computed(() => breakdown(overview.value?.grantedBySource, grantedSources))
const merchants = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  const rows = (overview.value?.merchants || []).filter(m => !key || String(m.tenantName || '').toLowerCase().includes(key) || String(m.tenantId) === key)
  const value = m => sort.value === 'available' ? available(m) : m[sort.value]
  return [...rows].sort((a, b) => value(b) - value(a) || a.tenantId - b.tenantId)
})

async function load() {
  const attempt = ++version
  loading.value = true
  error.value = ''
  try {
    const result = await api.overview()
    if (attempt === version) overview.value = result
  } catch (failure) {
    if (attempt === version) { overview.value = null; error.value = failure.message || '烟火看板读取失败，请重试。' }
  } finally { if (attempt === version) loading.value = false }
}

watch(() => auth.user?.id, () => { overview.value = null; load() }, { immediate: true })
onBeforeUnmount(() => { version++ })
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>烟火看板</span></div><h1>烟火看板</h1><p>全平台累计购买、赠送和消耗的烟火，以及每个商家现在还有多少、用了多少。</p></div>
      <div class="management-heading-actions"><span class="management-scope"><span />计费运营</span><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新数据</button></div>
    </header>

    <div class="management-stats"><article v-for="stat in stats" :key="stat.label"><div><span>{{ stat.label }}</span><component :is="stat.icon" :size="17" /></div><strong>{{ number(stat.value) }}</strong><p>{{ stat.helper }}</p></article></div>
    <p v-if="purchasedBreakdown.length || grantedBreakdown.length" class="mg-muted overview-breakdown">
      <span v-if="purchasedBreakdown.length">购买：<span v-for="item in purchasedBreakdown" :key="item.label">{{ item.label }} {{ number(item.value) }}</span></span>
      <span v-if="grantedBreakdown.length">赠送：<span v-for="item in grantedBreakdown" :key="item.label">{{ item.label }} {{ number(item.value) }}</span></span>
    </p>

    <section class="management-list" :aria-busy="loading" aria-label="商家烟火">
      <div class="mg-list-caption"><h2>商家烟火</h2><span>{{ overview ? `共 ${overview.merchants.length.toLocaleString('zh-CN')} 个商家` : '' }}</span></div>
      <form class="management-filters" @submit.prevent>
        <label class="mg-search"><Search :size="17" /><input v-model="keyword" placeholder="搜索商户名称或编号" aria-label="搜索商户" maxlength="100" /><button v-if="keyword" type="button" aria-label="清空搜索" @click="keyword = ''"><X :size="14" /></button></label>
        <label class="mg-select"><span class="sr-only">排序</span><AppSelect v-model="sort"><option v-for="(label, value) in sorts" :key="value" :value="value">{{ label }}</option></AppSelect></label>
      </form>
      <div v-if="error" class="mg-state mg-error" role="alert"><ClipboardList :size="30" /><h3>暂时无法读取数据</h3><p>{{ error }}</p><button class="mg-button" type="button" :disabled="loading" @click="load">重新读取</button></div>
      <div v-else-if="loading && !overview" class="mg-state" role="status"><RefreshCw :size="27" class="mg-spinning" /><p>正在汇总各商家烟火…</p></div>
      <div v-else-if="!merchants.length" class="mg-state"><Flame :size="32" /><h3>{{ keyword ? '没有匹配的商家' : '还没有商家' }}</h3><p>{{ keyword ? '换个关键词试试。' : '商家注册后会出现在这里。' }}</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>商户</th><th class="overview-number">当前可用</th><th class="overview-number">赠送余额</th><th class="overview-number">充值余额</th><th class="overview-number">生成中</th><th class="overview-number">累计购买</th><th class="overview-number">累计赠送</th><th class="overview-number">累计消耗</th></tr></thead>
          <tbody>
            <tr v-for="item in merchants" :key="item.tenantId">
              <td><span>{{ item.tenantName || '—' }}</span><small>商户 #{{ item.tenantId }}</small></td>
              <td class="overview-number"><strong>{{ number(available(item)) }}</strong></td>
              <td class="overview-number">{{ number(item.giftAvailable) }}</td>
              <td class="overview-number">{{ number(item.purchasedAvailable) }}</td>
              <td class="overview-number">{{ number(item.held) }}</td>
              <td class="overview-number">{{ number(item.purchased) }}</td>
              <td class="overview-number">{{ number(item.granted) }}</td>
              <td class="overview-number overview-consumed">{{ number(item.consumed) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <p class="management-footnote">当前可用 = 赠送余额 + 充值余额，消耗时先用赠送的。生成中的烟火已从可用里冻结，交付后计入消耗，失败或取消退回。累计数从开户起算。</p>
  </div>
</template>

<style scoped>
.overview-breakdown { display: flex; flex-wrap: wrap; gap: 6px 28px; }
.overview-breakdown > span > span { margin-left: 12px; }
.overview-number { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.overview-consumed { color: #ac4949; }
</style>
