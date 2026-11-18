<script setup>
import { computed, ref } from 'vue'
import { ChevronLeft, ChevronRight, RefreshCw } from 'lucide-vue-next'
import AppSelect from '../../src/components/AppSelect.vue'
import { managementDate } from '../../src/domain/platformManagement.js'
import { number } from '../../src/domain/partnerOps.js'
import { ledgerCapabilities } from '../../src/services/ledgerAdminApi.js'
import { partnerApi } from '../../src/services/partnerApi.js'
import MemberIdentity from '../components/MemberIdentity.vue'
import { can, loadPartner } from '../partnerStore.js'

const kinds = { CREATION: '创作消耗', DISTRIBUTION: '分配给商户' }
const statuses = { CAPTURED: '已扣费', DONE: '已到账', PENDING: '已扣费，待到账' }
const kind = ref('')
const memberNumber = ref('')
const result = ref(null)
const loading = ref(false)
const error = ref('')
const records = computed(() => result.value?.records)
const pageCount = computed(() => Math.ceil((records.value?.total || 0) / (records.value?.size || 20)))
const finance = computed(() => can('finance'))

async function load(page = 0) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    result.value = await partnerApi.taskLogs({ page, size: 20, kind: kind.value, memberNumber: memberNumber.value.trim() })
  } catch (failure) {
    error.value = failure.message || '任务日志暂时无法读取。'
  } finally {
    loading.value = false
  }
}

function refresh() {
  load(records.value?.page || 0)
  loadPartner(true)
}

load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div>
        <h1>任务日志</h1>
        <p>{{ finance ? '查看全团队的烟火消耗与分配。' : '查看你使用团队烟火的创作与分配。' }}创作按实际交付扣费计入，分配按团队账户实际转出计入。</p>
      </div>
      <div class="management-heading-actions">
        <button class="mg-button" type="button" :disabled="loading" @click="refresh"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
      </div>
    </header>

    <section v-if="result && !error" class="pw-kpis activity-totals" aria-label="团队烟火支出合计">
      <article><span>创作消耗</span><strong class="mg-mono">{{ number(result.creationCredits) }}</strong></article>
      <article><span>分配转出</span><strong class="mg-mono">{{ number(result.distributionCredits) }}</strong></article>
      <article><span>合计烟火支出</span><strong class="mg-mono">{{ number(result.creationCredits + result.distributionCredits) }}</strong></article>
    </section>

    <section class="management-list" :aria-busy="loading" aria-label="烟火支出记录">
      <form class="management-filters" @submit.prevent="load()">
        <label class="mg-select"><span class="sr-only">操作类型</span>
          <AppSelect v-model="kind" :disabled="loading"><option value="">全部操作</option><option v-for="(label, value) in kinds" :key="value" :value="value">{{ label }}</option></AppSelect>
        </label>
        <label v-if="finance" class="mg-search"><span class="sr-only">成员编号</span><input v-model="memberNumber" placeholder="成员编号，如 001" inputmode="numeric" maxlength="15" pattern="[0-9]+" :disabled="loading"></label>
        <button class="mg-button mg-primary" type="submit" :disabled="loading">查询</button>
      </form>
      <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p></div>
      <div v-else-if="loading && !records" class="mg-state" role="status"><p>正在读取任务日志…</p></div>
      <div v-else-if="!records?.items.length" class="mg-state"><p>没有符合条件的团队烟火支出。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>时间</th><th>操作成员</th><th>操作</th><th>说明</th><th>状态</th><th class="code-number">扣除烟火</th></tr></thead>
          <tbody>
            <tr v-for="item in records.items" :key="item.id">
              <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
              <td><MemberIdentity :number="item.actorNumber" :name="item.actorName" /></td>
              <td>{{ kinds[item.kind] }}</td>
              <td><span v-if="item.capability">{{ ledgerCapabilities[item.capability] || item.capability }}</span><small v-if="item.capability">{{ item.description }}</small><span v-else>{{ item.description }}</span></td>
              <td>{{ statuses[item.status] }}</td>
              <td class="code-number mg-mono">−{{ number(item.credits) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="!error && pageCount > 1" class="management-pagination">
        <span>共 {{ number(records.total) }} 笔</span>
        <div>
          <button class="mg-page-button" type="button" aria-label="上一页" :disabled="loading || records.page === 0" @click="load(records.page - 1)"><ChevronLeft :size="16" /></button>
          <span>{{ records.page + 1 }} / {{ pageCount }}</span>
          <button class="mg-page-button" type="button" aria-label="下一页" :disabled="loading || records.page + 1 >= pageCount" @click="load(records.page + 1)"><ChevronRight :size="16" /></button>
        </div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.activity-totals { grid-template-columns: repeat(3, minmax(0, 1fr)); }
@media (max-width: 580px) {
  .activity-totals article { padding: 12px; }
  .activity-totals strong { font-size: 21px; overflow-wrap: anywhere; }
}
</style>
