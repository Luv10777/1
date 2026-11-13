<script setup>
import AppSelect from '../../src/components/AppSelect.vue'
import { computed, ref } from 'vue'
import { ChevronLeft, ChevronRight, RefreshCw } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { distributionRecipients, distributionSources, number } from '../../src/domain/partnerOps.js'
import { partner } from '../partnerStore.js'
import TenantDrawer from '../components/TenantDrawer.vue'

// 分配记录（只给负责人）：每一笔分给商户或 BD 的烟火，来自可分配烟火还是自己的账号、赠送和充值各多少、谁操作的。
const records = ref([])
const total = ref(0)
const page = ref(0)
const recipientType = ref('')
const source = ref('')
const loading = ref(false)
const error = ref('')
const openTenant = ref(null)
const pages = computed(() => Math.ceil(total.value / 20))
const account = computed(() => partner.overview?.account)
const retrying = ref(null)

/** 补上未到账的一笔：同一个请求再走一遍，不会重复扣。 */
async function retry(item) {
  retrying.value = item.id
  try {
    const done = await partnerApi.retryDistribution(item.id)
    records.value = records.value.map(r => r.id === done.id ? done : r)
  } catch (failure) {
    error.value = failure.message || '补上失败，请稍后再试。'
  } finally { retrying.value = null }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await partnerApi.distributions({ page: page.value, size: 20, recipientType: recipientType.value, source: source.value })
    records.value = result.items
    total.value = result.total
  } catch (failure) {
    records.value = []
    error.value = failure.message || '分配记录暂时无法读取。'
  } finally { loading.value = false }
}
function search() { page.value = 0; load() }
function movePage(next) { page.value = next; load() }
function open(item) { if (item.recipientType === 'TENANT') openTenant.value = item.recipientTenantId }
load()
</script>

<template>
  <div class="management-page pw-page">
    <header class="pw-head">
      <div><p class="pw-date">分给商户和 BD 的每一笔烟火</p><h1>分配记录</h1></div>
      <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
    </header>

    <section class="pw-kpis pw-kpis-2" aria-label="可分的烟火">
      <article><span>服务商可分配烟火</span><strong class="mg-mono">{{ number(partner.overview?.partner.creditsAvailable) }}</strong><small>平台发放，对方收到的是充值烟火</small></article>
      <article><span>我的账号</span><strong class="mg-mono">{{ number(account?.available) }}</strong><small>赠送 {{ number(account?.gift) }} · 充值 {{ number(account?.purchased) }}，分出后性质不变</small></article>
    </section>

    <section class="pw-card" :aria-busy="loading" aria-label="分配记录">
      <form class="management-filters" @submit.prevent="search">
        <label class="mg-select"><span class="sr-only">分给</span><AppSelect v-model="recipientType" @change="search"><option value="">分给：全部</option><option value="TENANT">分给：商户</option><option value="BD">分给：BD</option></AppSelect></label>
        <label class="mg-select"><span class="sr-only">来源</span><AppSelect v-model="source" @change="search"><option value="">来源：全部</option><option value="POOL">来源：可分配烟火</option><option value="ACCOUNT">来源：我的账号</option></AppSelect></label>
      </form>
      <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="!records.length && !loading" class="pw-empty"><strong>{{ recipientType || source ? '没有匹配的记录' : '还没有分配过烟火' }}</strong><p>在商户详情或团队成员详情里分配烟火后，会记在这里。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>时间</th><th>分给</th><th>来源</th><th class="code-number">烟火</th><th class="code-number">赠送 / 充值</th><th>备注</th><th>操作人</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="item in records" :key="item.id" :class="{ 'partner-row': item.recipientType === 'TENANT' }" :tabindex="item.recipientType === 'TENANT' ? 0 : undefined" @click="open(item)" @keydown.enter="open(item)">
              <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
              <td><span>{{ item.recipientName || '—' }}</span><small>{{ distributionRecipients[item.recipientType] }}</small></td>
              <td><span class="partner-pill" :class="{ bd: item.source === 'ACCOUNT' }">{{ distributionSources[item.source] }}</span></td>
              <td class="code-number mg-mono">{{ number(item.credits) }}</td>
              <td class="code-number mg-mono">{{ number(item.gift) }} / {{ number(item.purchased) }}</td>
              <td>{{ item.note || '—' }}</td>
              <td>{{ item.actorName || '—' }}</td>
              <td><span v-if="item.status === 'DONE'" class="pw-plus">已到账</span><button v-else class="mg-text-button partner-danger" type="button" :disabled="retrying === item.id" @click.stop="retry(item)">{{ retrying === item.id ? '正在补上…' : '未到账 · 补上' }}</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="pages > 1" class="management-pagination"><span>共 {{ number(total) }} 笔</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ page + 1 }} / {{ pages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= pages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>
    <p class="pw-note">「未到账」表示已经扣了来源、对方还没入账（例如网络中断），点「补上」即可到账，不会重复扣。</p>

    <TenantDrawer :tenant-id="openTenant" @close="openTenant = null" @updated="load" />
  </div>
</template>
