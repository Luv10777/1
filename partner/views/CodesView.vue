<script setup>
import AppSelect from '../../src/components/AppSelect.vue'
import { computed, reactive, ref } from 'vue'
import { Ban, Check, ChevronLeft, ChevronRight, Copy, Download, Plus, RefreshCw, Search, X } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { formatRedeemCode, redeemCodeStatuses } from '../../src/services/redeemCodeAdminApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { codesCsv } from '../../src/domain/partnerOps.js'
import { auth } from '../../src/stores/auth.js'
import { isOwner, loadPartner, loadTeam, partner } from '../partnerStore.js'

const number = value => Number(value || 0).toLocaleString('zh-CN')
const records = ref([])
const total = ref(0)
const page = ref(0)
const status = ref('')
const keyword = ref('')
const creator = ref('')
const exporting = ref(false)
const owner = computed(() => isOwner())
// 生成人名字：负责人能看到团队，BD 只会看到自己的码。
const creators = computed(() => Object.fromEntries([...partner.team.map(m => [m.userId, m.name || String(m.uid)]), [auth.user?.id, auth.user?.name || '我']]))
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const success = ref('')
const formError = ref('')
const created = ref([])
const pages = computed(() => Math.ceil(total.value / 20))
// 只有当前上架、还有额度的套餐能生成。
const quotas = computed(() => (partner.overview?.quotas || []).filter(q => q.planName))
const form = reactive({ planCode: '', quantity: '10', note: '' })
let requestKey = 'codes-' + crypto.randomUUID()

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
function query(at, size) {
  return { page: at, size, status: status.value, keyword: keyword.value.trim(), createdBy: owner.value ? creator.value : '' }
}
function search() { page.value = 0; load() }
function movePage(next) { page.value = next; load() }

async function generate() {
  if (busy.value) return
  const quota = quotas.value.find(q => q.planCode === form.planCode)
  const quantity = Number(form.quantity)
  formError.value = !quota ? '请选择套餐。'
    : !Number.isInteger(quantity) || quantity < 1 || quantity > 200 ? '一次生成 1 至 200 个。'
      : quantity > quota.remaining ? `「${quota.planName}」还能生成 ${quota.remaining} 个。` : ''
  if (formError.value) return
  busy.value = true
  try {
    created.value = await partnerApi.createCodes({ requestKey, planCode: form.planCode, quantity, note: form.note.trim() || null })
    success.value = `已生成 ${created.value.length} 个「${quota.planName}」激活码`
    requestKey = 'codes-' + crypto.randomUUID()
    form.note = ''
    await Promise.all([search(), loadPartner(true)])
  } catch (failure) {
    formError.value = failure.message || '生成失败，请稍后再试。'
  } finally { busy.value = false }
}

async function voidCode(item) {
  if (busy.value) return
  if (!window.confirm(`作废激活码 ${formatRedeemCode(item.code)}？作废后不能再兑换，额度不退回。`)) return
  busy.value = true
  try {
    const updated = await partnerApi.voidCode(item.id)
    records.value = records.value.map(row => row.id === updated.id ? updated : row)
    success.value = '激活码已作废'
  } catch (failure) {
    error.value = failure.message || '作废失败，请稍后再试。'
  } finally { busy.value = false }
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
    const csv = codesCsv(items, { statuses: redeemCodeStatuses, creators: creators.value, format: formatRedeemCode, date: value => value ? managementDate(value) : '' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    const today = new Date()
    link.download = `激活码-${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}.csv`
    link.click()
    setTimeout(() => URL.revokeObjectURL(link.href), 1000)
    success.value = `已导出 ${items.length} 个激活码`
  } catch (failure) {
    error.value = failure.message || '导出失败，请稍后再试。'
  } finally { exporting.value = false }
}

async function copy(text, label) {
  try { await navigator.clipboard.writeText(text); success.value = `${label}已复制` }
  catch { success.value = `浏览器不允许自动复制，请手动选中${label}复制` }
}
if (owner.value) loadTeam().catch(() => {})
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><h1>激活码</h1><p>在额度内生成激活码卖给商户；商户在「套餐与权益」输入后开通会员，还没有归属的商户会归到你名下。激活码不送烟火。没用过的码可以作废，作废后额度不退回。<template v-if="!owner">这里只显示你生成的码。</template></p></div>
      <div class="management-heading-actions"><button class="mg-button" type="button" :disabled="exporting" @click="exportCodes"><Download :size="15" />{{ exporting ? '导出中…' : '导出表格' }}</button><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button></div>
    </header>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section class="management-list" aria-labelledby="generate-title">
      <div class="mg-list-caption"><h2 id="generate-title">生成激活码</h2></div>
      <div v-if="!quotas.length" class="mg-state"><h3>没有可用的额度</h3><p>请联系平台为你分配激活码额度。</p></div>
      <form v-else class="partner-form" @submit.prevent="generate">
        <label>套餐<AppSelect v-model="form.planCode" required><option value="" disabled>选择套餐</option><option v-for="q in quotas" :key="q.planCode" :value="q.planCode">{{ q.planName }} · {{ q.durationDays }} 天（还能生成 {{ number(q.remaining) }} 个）</option></AppSelect></label>
        <label>数量<input v-model="form.quantity" type="number" min="1" max="200" step="1" required /></label>
        <label>备注（可选）<input v-model="form.note" maxlength="200" placeholder="例如：某某商户、某次活动" /></label>
        <button class="mg-button mg-primary" type="submit" :disabled="busy"><Plus :size="15" />{{ busy ? '生成中…' : '生成' }}</button>
        <p v-if="formError" class="mg-error" role="alert">{{ formError }}</p>
      </form>
    </section>

    <section v-if="created.length" class="management-list" aria-labelledby="created-title">
      <div class="mg-list-caption"><h2 id="created-title">刚生成的激活码</h2><span><button class="mg-text-button" type="button" @click="copy(created.map(item => formatRedeemCode(item.code)).join('\n'), '全部激活码')"><Copy :size="13" />复制全部</button> · <button class="mg-text-button" type="button" @click="created = []">收起</button></span></div>
      <ol class="code-created-list"><li v-for="item in created" :key="item.id" class="mg-mono">{{ formatRedeemCode(item.code) }}</li></ol>
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
          <thead><tr><th>激活码</th><th>套餐</th><th>状态</th><th>兑换商户</th><th>备注</th><th v-if="owner">生成人</th><th>生成时间</th><th class="mg-table-action">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in records" :key="item.id">
              <td class="mg-mono">{{ formatRedeemCode(item.code) }}</td>
              <td>{{ item.planName || '—' }} · {{ item.durationDays ?? '—' }} 天</td>
              <td>{{ redeemCodeStatuses[item.status] || item.status }}</td>
              <td><template v-if="item.redeemedTenantId"><span>{{ item.redeemedTenantName || '—' }}</span><small>{{ managementDate(item.redeemedAt) }}</small></template><template v-else>—</template></td>
              <td>{{ item.note || '—' }}</td>
              <td v-if="owner">{{ creators[item.createdBy] || '—' }}</td>
              <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
              <td class="mg-table-action"><template v-if="item.status === 'UNUSED'"><button class="mg-text-button" type="button" @click="copy(formatRedeemCode(item.code), '激活码')"><Copy :size="13" />复制</button> · <button class="mg-text-button partner-danger" type="button" :disabled="busy" @click="voidCode(item)"><Ban :size="13" />作废</button></template><template v-else>—</template></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="pages > 1" class="management-pagination"><span>共 {{ number(total) }} 个</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ page + 1 }} / {{ pages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= pages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>
  </div>
</template>
