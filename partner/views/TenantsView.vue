<script setup>
import AppSelect from '../../src/components/AppSelect.vue'
import { computed, reactive, ref } from 'vue'
import { Check, ChevronLeft, ChevronRight, Plus, RefreshCw, Search, Send, X } from 'lucide-vue-next'
import { partnerApi, tenantSources, validUid } from '../../src/services/partnerApi.js'
import { managementDate } from '../../src/domain/platformManagement.js'
import { expiryFilters, membershipState, planBatchTransfer, runBatchTransfers } from '../../src/domain/partnerOps.js'
import { bds, isOwner, loadPartner, loadTeam, partner } from '../partnerStore.js'

const number = value => Number(value || 0).toLocaleString('zh-CN')
const records = ref([])
const total = ref(0)
const page = ref(0)
const keyword = ref('')
const expiry = ref('')
const bdFilter = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const success = ref('')
const uid = ref('')
const addError = ref('')
const pages = computed(() => Math.ceil(total.value / 20))
const owner = computed(() => isOwner())
const available = computed(() => partner.overview?.partner.creditsAvailable || 0)

// 每个商户一份分配表单，翻页也保留；requestKey 在这一笔成功前不变，用同一个重试不会重复扣烟火。
const transfers = reactive({})
const freshTransfer = name => ({ credits: '', requestKey: 'transfer-' + crypto.randomUUID(), error: '', tenantName: name })
const batch = computed(() => planBatchTransfer(Object.entries(transfers).map(([tenantId, t]) => ({ tenantId: Number(tenantId), tenantName: t.tenantName, credits: t.credits })), available.value))
const batchError = ref('')

// 续期：选中一个商户后在列表上方确认。
const renewal = reactive({ tenant: null, planCode: '', requestKey: '', error: '' })
const renewQuotas = computed(() => (partner.overview?.quotas || []).filter(q => q.planName))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const query = { page: page.value, size: 20, keyword: keyword.value.trim(), expiry: expiry.value }
    if (owner.value && bdFilter.value !== '') query.bdUserId = bdFilter.value
    const result = await partnerApi.tenants(query)
    for (const item of result.items) transfers[item.tenantId] ||= freshTransfer(item.tenantName)
    records.value = result.items
    total.value = result.total
  } catch (failure) {
    records.value = []
    error.value = failure.message || '名下商户暂时无法读取。'
  } finally { loading.value = false }
}
function search() { page.value = 0; load() }
function movePage(next) { page.value = next; load() }

async function add() {
  if (busy.value) return
  addError.value = validUid(uid.value) ? '' : 'UID 是 8 位数字，请让商户在「套餐与权益」页查看。'
  if (addError.value) return
  busy.value = true
  try {
    const tenant = await partnerApi.addTenant(uid.value.trim())
    success.value = `已添加「${tenant.tenantName || '商户'}」`
    uid.value = ''
    await Promise.all([search(), loadPartner(true)])
  } catch (failure) {
    addError.value = failure.message || '添加失败，请稍后再试。'
  } finally { busy.value = false }
}

async function distribute() {
  if (busy.value) return
  const plan = batch.value
  batchError.value = plan.error
  if (plan.error) return
  const list = plan.entries.map(e => `「${e.tenantName || '商户'}」${number(e.credits)}`).join('、')
  if (!window.confirm(`把 ${number(plan.total)} 烟火分给 ${plan.entries.length} 个商户：${list}？分出后不能撤回。`)) return
  busy.value = true
  try {
    const keys = Object.fromEntries(plan.entries.map(e => [e.tenantId, transfers[e.tenantId].requestKey]))
    const results = await runBatchTransfers(plan.entries, keys, partnerApi.transfer)
    for (const r of results) {
      if (r.ok) {
        transfers[r.tenantId] = freshTransfer(transfers[r.tenantId].tenantName)
        records.value = records.value.map(row => row.tenantId === r.tenantId ? r.result : row)
      } else transfers[r.tenantId].error = `${r.error}，可以直接再点一次分配补上，不会重复扣烟火。`
    }
    const done = results.filter(r => r.ok).length
    success.value = done === results.length ? `已分配给 ${done} 个商户，共 ${number(plan.total)} 烟火` : ''
    batchError.value = done === results.length ? '' : `${results.length - done} 笔没有成功，已标在对应商户下面；成功的 ${done} 笔已到账。`
    await loadPartner(true)
  } finally { busy.value = false }
}

function startRenew(item) {
  Object.assign(renewal, { tenant: item, planCode: renewQuotas.value.find(q => q.remaining > 0)?.planCode || '', requestKey: 'renew-' + crypto.randomUUID(), error: '' })
}

async function renew() {
  if (busy.value) return
  const quota = renewQuotas.value.find(q => q.planCode === renewal.planCode)
  renewal.error = !quota ? '请选择套餐。' : quota.remaining < 1 ? `「${quota.planName}」额度已用完，请联系${owner.value ? '平台' : '负责人'}。` : ''
  if (renewal.error) return
  const name = renewal.tenant.tenantName || '商户'
  if (!window.confirm(`用 1 个「${quota.planName}」额度给「${name}」续期 ${quota.durationDays} 天？还没到期的从原到期日往后加。`)) return
  busy.value = true
  try {
    const updated = await partnerApi.renew(renewal.tenant.tenantId, { requestKey: renewal.requestKey, planCode: renewal.planCode })
    records.value = records.value.map(row => row.tenantId === updated.tenantId ? updated : row)
    success.value = `已给「${name}」续期，${membershipState(updated).text}`
    renewal.tenant = null
    await loadPartner(true)
  } catch (failure) {
    renewal.error = failure.message || '续期失败，请稍后再点一次（不会重复扣额度）。'
  } finally { busy.value = false }
}

async function assign(item, value) {
  const target = bds().find(b => String(b.userId) === String(value))
  if (!window.confirm(`把「${item.tenantName || '商户'}」${target ? `调给 BD「${target.name || target.uid}」` : '收回由你直接管'}？`)) return load()
  busy.value = true
  try {
    const updated = await partnerApi.assignBd(item.tenantId, target?.userId)
    records.value = records.value.map(row => row.tenantId === updated.tenantId ? updated : row)
    success.value = '已调配'
    await loadTeam()
  } catch (failure) {
    error.value = failure.message || '调配失败，请稍后再试。'
  } finally { busy.value = false }
}

const sourceText = item => tenantSources[item.source] || item.source
if (owner.value) loadTeam().catch(() => {})
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><h1>{{ owner ? '名下商户' : '我名下的商户' }}</h1><p>输入商户的 UID 添加到名下（已归属其他服务商的不能添加）。<template v-if="owner">在「分配烟火」列填好数量后一次分出，可分配烟火：{{ number(available) }}。</template><template v-else>烟火由负责人分配。</template></p></div>
      <div class="management-heading-actions"><button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button></div>
    </header>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section class="management-list" aria-labelledby="add-title">
      <div class="mg-list-caption"><h2 id="add-title">添加商户</h2></div>
      <form class="partner-form" @submit.prevent="add">
        <label>商户的 UID<input v-model="uid" inputmode="numeric" maxlength="8" placeholder="8 位数字" /></label>
        <button class="mg-button mg-primary" type="submit" :disabled="busy"><Plus :size="15" />添加到名下</button>
        <p v-if="addError" class="mg-error" role="alert">{{ addError }}</p>
      </form>
    </section>

    <section v-if="renewal.tenant" class="management-list" aria-labelledby="renew-title">
      <div class="mg-list-caption"><h2 id="renew-title">给「{{ renewal.tenant.tenantName || '商户' }}」续期</h2><button class="mg-text-button" type="button" @click="renewal.tenant = null">取消</button></div>
      <div v-if="!renewQuotas.length" class="mg-state"><p>没有可用的激活码额度，请联系{{ owner ? '平台' : '负责人' }}。</p></div>
      <form v-else class="partner-form" @submit.prevent="renew">
        <p class="partner-form-note">现在：{{ membershipState(renewal.tenant).text }}。续期扣 1 个激活码额度，直接开通，不用商户输入激活码。</p>
        <label>套餐<AppSelect v-model="renewal.planCode"><option value="" disabled>选择套餐</option><option v-for="q in renewQuotas" :key="q.planCode" :value="q.planCode" :disabled="q.remaining < 1">{{ q.planName }} · {{ q.durationDays }} 天（还剩 {{ number(q.remaining) }} 个额度）</option></AppSelect></label>
        <button class="mg-button mg-primary" type="submit" :disabled="busy">{{ busy ? '续期中…' : '确认续期' }}</button>
        <p v-if="renewal.error" class="mg-error" role="alert">{{ renewal.error }}</p>
      </form>
    </section>

    <section class="management-list" :aria-busy="loading" aria-label="名下商户列表">
      <form class="management-filters" @submit.prevent="search">
        <label class="mg-search"><Search :size="17" /><input v-model="keyword" placeholder="搜索商户名、联系人或 UID" aria-label="搜索商户" maxlength="60" /></label>
        <label class="mg-select"><span class="sr-only">到期</span><AppSelect v-model="expiry" @change="search"><option value="">全部会员状态</option><option v-for="(label, value) in expiryFilters" :key="value" :value="value">{{ label }}</option></AppSelect></label>
        <label v-if="owner" class="mg-select"><span class="sr-only">BD</span><AppSelect v-model="bdFilter" @change="search"><option value="">全部 BD</option><option value="0">我直接管的</option><option v-for="b in bds()" :key="b.userId" :value="String(b.userId)">{{ b.name || b.uid }}</option></AppSelect></label>
        <button class="mg-button mg-primary" type="submit" :disabled="loading">查询</button>
      </form>
      <div v-if="error" class="mg-state mg-error" role="alert"><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="!records.length && !loading" class="mg-state"><h3>{{ keyword || expiry || bdFilter ? '没有匹配的商户' : '还没有名下商户' }}</h3><p>在上方输入商户的 UID 添加；商户兑换了你生成的激活码也会自动归到你名下。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>商户</th><th>联系人</th><th>会员</th><th class="code-number">烟火余额</th><th>归属</th><th v-if="owner">分配烟火</th><th class="mg-table-action">续期</th></tr></thead>
          <tbody>
            <tr v-for="item in records" :key="item.tenantId">
              <td><span>{{ item.tenantName || '—' }}</span><small>商户 #{{ item.tenantId }}</small></td>
              <td><span>{{ item.ownerName || '—' }}</span><small>UID {{ item.ownerUid || '—' }}</small></td>
              <td :class="['partner-member', membershipState(item).tone]">{{ membershipState(item).text }}</td>
              <td class="code-number mg-mono">{{ item.creditsAvailable == null ? '—' : number(item.creditsAvailable) }}</td>
              <td>
                <AppSelect v-if="owner" class="partner-bd-select" :model-value="item.bdUserId ? String(item.bdUserId) : ''" :disabled="busy" :aria-label="`${item.tenantName || '商户'}归属的 BD`" @update:model-value="value => assign(item, value)"><option value="">我直接管</option><option v-for="b in bds()" :key="b.userId" :value="String(b.userId)">BD · {{ b.name || b.uid }}</option></AppSelect>
                <span v-else>{{ sourceText(item) }}</span>
                <small>{{ owner ? sourceText(item) + ' · ' : '' }}{{ managementDate(item.boundAt) }}</small>
              </td>
              <td v-if="owner">
                <input v-model="transfers[item.tenantId].credits" class="partner-credit-input" type="number" min="1" max="1000000" step="1" placeholder="烟火数" :aria-label="`分给${item.tenantName || '商户'}的烟火`" />
                <small v-if="transfers[item.tenantId].error" class="mg-error" role="alert">{{ transfers[item.tenantId].error }}</small>
              </td>
              <td class="mg-table-action"><button class="mg-text-button" type="button" :disabled="busy" @click="startRenew(item)">续期</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer v-if="owner && batch.entries.length" class="partner-batch-bar">
        <span>已填 {{ batch.entries.length }} 个商户，合计 <strong class="mg-mono">{{ number(batch.total) }}</strong> 烟火 / 可分配 <span class="mg-mono">{{ number(available) }}</span></span>
        <button class="mg-button mg-primary" type="button" :disabled="busy" @click="distribute"><Send :size="15" />{{ busy ? '分配中…' : '分配' }}</button>
        <p v-if="batchError || batch.error" class="mg-error" role="alert">{{ batchError || batch.error }}</p>
      </footer>
      <footer v-if="pages > 1" class="management-pagination"><span>共 {{ number(total) }} 个商户</span><div><button class="mg-page-button" type="button" :disabled="page === 0 || loading" aria-label="上一页" @click="movePage(page - 1)"><ChevronLeft :size="16" /></button><span>{{ page + 1 }} / {{ pages }}</span><button class="mg-page-button" type="button" :disabled="page + 1 >= pages || loading" aria-label="下一页" @click="movePage(page + 1)"><ChevronRight :size="16" /></button></div></footer>
    </section>
  </div>
</template>
