<script setup>
import AppSelect from '../../src/components/AppSelect.vue'
import { computed, ref } from 'vue'
import { Check, ChevronLeft, ChevronRight, Download, Plus, RefreshCw, Search, X } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { formatRedeemCode, redeemCodeStatuses } from '../../src/services/redeemCodeAdminApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { codesCsv, downloadCsv, number } from '../../src/domain/partnerOps.js'
import { auth } from '../../src/stores/auth.js'
import { isOwner, loadTeam, partner } from '../partnerStore.js'
import GenerateCodesModal from '../components/GenerateCodesModal.vue'
import CodeDrawer from '../components/CodeDrawer.vue'

// 激活码：列表浏览、筛选、导出；生成用弹窗，点一行看详情和作废。
const records = ref([])
const total = ref(0)
const page = ref(0)
const status = ref('')
const keyword = ref('')
const creator = ref('')
const loading = ref(false)
const exporting = ref(false)
const error = ref('')
const success = ref('')
const generating = ref(false)
const openCode = ref(null)
const owner = computed(() => isOwner())
const pages = computed(() => Math.ceil(total.value / 20))
const quotas = computed(() => partner.overview?.quotas || [])
// 生成人名字：负责人能看到团队，BD 只会看到自己的码。
const creators = computed(() => Object.fromEntries([...partner.team.map(m => [m.userId, m.name || String(m.uid)]), [auth.user?.id, auth.user?.name || '我']]))

function query(at, size) {
  return { page: at, size, status: status.value, keyword: keyword.value.trim(), createdBy: owner.value ? creator.value : '' }
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await partnerApi.codes(query(page.value, 20))
    records.value = result.items
    total.value = result.total
  } catch (failure) {
    records.value = []
    error.value = failure.message || '激活码暂时无法读取。'
  } finally { loading.value = false }
}
function search() { page.value = 0; load() }
function movePage(next) { page.value = next; load() }

function created(codes) {
  success.value = `已生成 ${codes.length} 个「${codes[0]?.planName || ''}」激活码`
  search()
  if (owner.value) loadTeam().catch(() => {})
}
function updated(code) {
  records.value = records.value.map(r => r.id === code.id ? code : r)
  openCode.value = code
  if (owner.value) loadTeam().catch(() => {})
}

/** 按当前筛选导出全部激活码（每页 100 个逐页读）。 */
async function exportCodes() {
  if (exporting.value) return
  exporting.value = true
  try {
    const items = []
    for (let at = 0; ; at++) {
      const result = await partnerApi.codes(query(at, 100))
      items.push(...result.items)
      if (items.length >= result.total || !result.items.length) break
    }
    downloadCsv(codesCsv(items, { statuses: redeemCodeStatuses, creators: creators.value, format: formatRedeemCode, date: v => v ? managementDate(v) : '' }), '激活码')
    success.value = `已导出 ${items.length} 个激活码`
  } catch (failure) {
    error.value = failure.message || '导出失败，请稍后再试。'
  } finally { exporting.value = false }
}

if (owner.value) loadTeam().catch(() => {})
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><h1>激活码</h1><p>在额度内生成激活码卖给商户；商户在「套餐与权益」输入后开通会员，还没有归属的商户会归到生成人名下。激活码不送烟火。点一行查看详情或作废。<template v-if="!owner">这里只显示你生成的码。</template></p></div>
      <div class="management-heading-actions">
        <button class="mg-button" type="button" :disabled="exporting" @click="exportCodes"><Download :size="15" />{{ exporting ? '导出中…' : '导出表格' }}</button>
        <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
        <button class="mg-button mg-primary" type="button" @click="generating = true"><Plus :size="15" />生成激活码</button>
      </div>
    </header>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section v-if="quotas.length" class="partner-cards" aria-label="激活码额度">
      <div v-for="q in quotas" :key="q.planCode" class="partner-card"><span>{{ q.planName ? `${q.planName} · ${q.durationDays} 天` : `${q.planCode}（套餐已下架）` }}</span><strong>{{ number(q.remaining) }}</strong><small>还能生成 · 已生成 {{ number(q.used) }} / 总额度 {{ number(q.quota) }}</small></div>
    </section>

    <section class="management-list" :aria-busy="loading" aria-label="激活码列表">
      <form class="management-filters" @submit.prevent="search">
        <label class="mg-search"><Search :size="17" /><input v-model="keyword" placeholder="按激活码或备注搜索" aria-label="搜索激活码" maxlength="40" /></label>
        <label class="mg-select"><span class="sr-only">状态</span><AppSelect v-model="status" @change="search"><option value="">全部状态</option><option v-for="(label, value) in redeemCodeStatuses" :key="value" :value="value">{{ label }}</option></AppSelect></label>
        <label v-if="owner" class="mg-select"><span class="sr-only">生成人</span><AppSelect v-model="creator" @change="search"><option value="">全部生成人</option><option v-for="m in partner.team" :key="m.userId" :value="String(m.userId)">{{ m.role === 'BD' ? 'BD · ' : '' }}{{ m.name || m.uid }}</option></AppSelect></label>
        <button class="mg-button mg-primary" type="submit" :disabled="loading">查询</button>
      </form>
      <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="!records.length && !loading" class="mg-state"><h3>{{ status || keyword || creator ? '没有匹配的激活码' : '还没有生成激活码' }}</h3></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>激活码</th><th>套餐</th><th>状态</th><th>兑换商户</th><th>备注</th><th v-if="owner">生成人</th><th>生成时间</th></tr></thead>
          <tbody>
            <tr v-for="item in records" :key="item.id" class="partner-row" tabindex="0" @click="openCode = item" @keydown.enter="openCode = item">
              <td class="mg-mono">{{ formatRedeemCode(item.code) }}</td>
              <td>{{ item.planName || '—' }} · {{ item.durationDays ?? '—' }} 天</td>
              <td><span class="partner-pill" :class="{ bd: item.status === 'UNUSED' }">{{ redeemCodeStatuses[item.status] || item.status }}</span></td>
              <td><template v-if="item.redeemedTenantId"><span>{{ item.redeemedTenantName || '—' }}</span><small>{{ managementDate(item.redeemedAt) }}</small></template><template v-else>—</template></td>
              <td>{{ item.note || '—' }}</td>
              <td v-if="owner">{{ creators[item.createdBy] || '—' }}</td>
              <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="pages > 1" class="management-pagination"><span>共 {{ number(total) }} 个</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ page + 1 }} / {{ pages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= pages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>

    <GenerateCodesModal :open="generating" @close="generating = false" @created="created" />
    <CodeDrawer :code="openCode" :creator-name="openCode ? creators[openCode.createdBy] || '' : ''" @close="openCode = null" @updated="updated" />
  </div>
</template>
