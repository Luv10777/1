<script setup>
import { computed, ref } from 'vue'
import { ChevronLeft, ChevronRight, RefreshCw } from 'lucide-vue-next'
import { partnerApi, ledgerTypes } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { isOwner, loadPartner, partner } from '../partnerStore.js'

const number = value => Number(value || 0).toLocaleString('zh-CN')
const overview = computed(() => partner.overview)
const ledger = ref([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const error = ref('')
const pages = computed(() => Math.ceil(total.value / 20))

async function load() {
  loading.value = true
  error.value = ''
  try {
    await loadPartner(true)
    if (!isOwner()) return
    const result = await partnerApi.ledger({ page: page.value, size: 20 })
    ledger.value = result.items
    total.value = result.total
  } catch (failure) {
    error.value = failure.message || '流水暂时无法读取。'
  } finally { loading.value = false }
}
function movePage(next) { page.value = next; load() }
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><h1>概览</h1><p v-if="isOwner()">平台发放的烟火可以分给名下商户；激活码额度按套餐分开，用完请联系平台追加。</p><p v-else>激活码额度是整个服务商共用的，用完请联系负责人。</p></div>
      <div class="management-heading-actions"><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button></div>
    </header>
    <section v-if="overview" class="partner-cards" aria-label="服务商概况">
      <div v-if="isOwner()" class="partner-card"><span>可分配烟火</span><strong>{{ number(overview.partner.creditsAvailable) }}</strong><small>分给名下商户后记入商户的充值烟火</small></div>
      <div class="partner-card"><span>{{ isOwner() ? '名下商户' : '我名下的商户' }}</span><strong>{{ number(overview.tenantCount) }}</strong><small>按 UID 添加，或兑换了你的激活码</small></div>
    </section>

    <section class="management-list" aria-labelledby="quota-title">
      <div class="mg-list-caption"><h2 id="quota-title">激活码额度</h2></div>
      <div v-if="!overview?.quotas.length" class="mg-state"><h3>还没有激活码额度</h3><p>请联系平台为你分配月卡、季卡、年卡的激活码额度。</p></div>
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

    <section v-if="isOwner()" class="management-list" :aria-busy="loading" aria-labelledby="ledger-title">
      <div class="mg-list-caption"><h2 id="ledger-title">烟火流水</h2></div>
      <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="!ledger.length && !loading" class="mg-state"><h3>还没有流水</h3><p>平台发放烟火、你分给商户烟火后都会记在这里。</p></div>
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
      <footer v-if="pages > 1" class="management-pagination"><span>共 {{ number(total) }} 条</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ page + 1 }} / {{ pages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= pages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>
  </div>
</template>
