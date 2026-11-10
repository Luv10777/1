<script setup>
import AppSelect from '../../src/components/AppSelect.vue'
import { computed, reactive, ref } from 'vue'
import { Check, ChevronLeft, ChevronRight, Copy, Plus, RefreshCw, X } from 'lucide-vue-next'
import { partnerApi } from '../../src/services/partnerApi.js'
import { formatRedeemCode, redeemCodeStatuses } from '../../src/services/redeemCodeAdminApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { loadPartner, partner } from '../partnerStore.js'

const number = value => Number(value || 0).toLocaleString('zh-CN')
const records = ref([])
const total = ref(0)
const page = ref(0)
const status = ref('')
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
    const result = await partnerApi.codes({ page: page.value, size: 20, status: status.value })
    records.value = result.items
    total.value = result.total
  } catch (failure) {
    records.value = []
    error.value = failure.message || '激活码暂时无法读取。'
  } finally { loading.value = false }
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

async function copy(text, label) {
  try { await navigator.clipboard.writeText(text); success.value = `${label}已复制` }
  catch { success.value = `浏览器不允许自动复制，请手动选中${label}复制` }
}
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><h1>激活码</h1><p>在额度内生成激活码卖给商户；商户在「套餐与权益」输入后开通会员，还没有归属的商户会归到你名下。激活码不送烟火。</p></div>
      <div class="management-heading-actions"><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button></div>
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
        <label class="mg-select"><span class="sr-only">状态</span><AppSelect v-model="status" @change="search"><option value="">全部状态</option><option v-for="(label, value) in redeemCodeStatuses" :key="value" :value="value">{{ label }}</option></AppSelect></label>
      </form>
      <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="!records.length && !loading" class="mg-state"><h3>{{ status ? '没有匹配的激活码' : '还没有生成激活码' }}</h3></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>激活码</th><th>套餐</th><th>状态</th><th>兑换商户</th><th>备注</th><th>生成时间</th><th class="mg-table-action">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in records" :key="item.id">
              <td class="mg-mono">{{ formatRedeemCode(item.code) }}</td>
              <td>{{ item.planName || '—' }} · {{ item.durationDays ?? '—' }} 天</td>
              <td>{{ redeemCodeStatuses[item.status] || item.status }}</td>
              <td><template v-if="item.redeemedTenantId"><span>{{ item.redeemedTenantName || '—' }}</span><small>{{ managementDate(item.redeemedAt) }}</small></template><template v-else>—</template></td>
              <td>{{ item.note || '—' }}</td>
              <td class="mg-time">{{ managementDate(item.createdAt) }}</td>
              <td class="mg-table-action"><button v-if="item.status === 'UNUSED'" class="mg-text-button" type="button" @click="copy(formatRedeemCode(item.code), '激活码')"><Copy :size="13" />复制</button><template v-else>—</template></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="pages > 1" class="management-pagination"><span>共 {{ number(total) }} 个</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ page + 1 }} / {{ pages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= pages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>
  </div>
</template>
