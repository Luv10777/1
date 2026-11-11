<script setup>
import { computed, reactive, ref } from 'vue'
import { Check, ChevronRight, Plus, RefreshCw, Store, X } from 'lucide-vue-next'
import { partnerAdminApi as api, ledgerTypes, tenantSources, validUid } from '../services/partnerApi'
import { activationPlans, planAdminApi } from '../services/planAdminApi'
import { managementDate } from '../domain/platformManagement'
import '../platform-management.css'

const number = value => Number(value || 0).toLocaleString('zh-CN')
const partners = ref([])
const plans = ref([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const forbidden = ref(false)
const success = ref('')
const selectedId = ref(null)
const selected = computed(() => partners.value.find(item => item.partner.id === selectedId.value) || null)
const tenants = ref([])
const ledger = ref([])
const detailError = ref('')

const createForm = reactive({ open: false, name: '', contact: '', adminUid: '', error: '' })
const grantForm = reactive({ credits: '', note: '', requestKey: '', error: '' })
const quotaForm = reactive({ planCode: '', quota: '', error: '' })
const memberForm = reactive({ uid: '', error: '' })
const assignForm = reactive({ uid: '', found: null, partnerId: '', error: '' })

async function load() {
  loading.value = true
  error.value = ''
  forbidden.value = false
  try {
    const [list, planList] = await Promise.all([api.list(), planAdminApi.plans()])
    partners.value = list
    plans.value = activationPlans(planList)
    if (selectedId.value && !selected.value) selectedId.value = null
  } catch (failure) {
    partners.value = []
    if (failure.status === 403) forbidden.value = true
    else error.value = failure.message || '服务商暂时无法读取。'
  } finally { loading.value = false }
}

function replace(updated) {
  partners.value = partners.value.map(item => item.partner.id === updated.partner.id ? updated : item)
}

async function select(id) {
  selectedId.value = id
  Object.assign(grantForm, { credits: '', note: '', requestKey: 'grant-' + crypto.randomUUID(), error: '' })
  Object.assign(quotaForm, { planCode: '', quota: '', error: '' })
  Object.assign(memberForm, { uid: '', error: '' })
  await loadDetail()
}

async function loadDetail() {
  if (!selectedId.value) return
  detailError.value = ''
  try {
    const [tenantPage, ledgerPage] = await Promise.all([api.tenants(selectedId.value, { size: 50 }), api.ledger(selectedId.value, { size: 20 })])
    tenants.value = tenantPage.items
    ledger.value = ledgerPage.items
  } catch (failure) {
    detailError.value = failure.message || '名下商户和流水暂时无法读取。'
  }
}

async function run(work, done) {
  if (busy.value) return
  busy.value = true
  try { await work(); if (done) success.value = done }
  finally { busy.value = false }
}

async function create() {
  createForm.error = !createForm.name.trim() ? '请填写服务商名称。' : !validUid(createForm.adminUid) ? '登录账号的用户 ID 是 8 位数字。' : ''
  if (createForm.error) return
  try {
    await run(async () => {
      const created = await api.create({ name: createForm.name.trim(), contact: createForm.contact.trim() || null, adminUid: Number(createForm.adminUid) })
      partners.value = [created, ...partners.value]
      Object.assign(createForm, { open: false, name: '', contact: '', adminUid: '', error: '' })
      await select(created.partner.id)
    }, '服务商已创建，对方用这个账号登录 partner.yifangzhi.com')
  } catch (failure) { createForm.error = failure.message || '创建失败，请稍后再试。' }
}

async function grant() {
  const credits = Number(grantForm.credits)
  grantForm.error = !Number.isInteger(credits) || credits < 1 || credits > 10000000 ? '烟火须为 1 至 10,000,000 的整数。' : !grantForm.note.trim() ? '请填写备注（例如收款流水号）。' : ''
  if (grantForm.error) return
  if (!window.confirm(`给「${selected.value.partner.name}」发放 ${number(credits)} 烟火？请确认已经收到对方的款项。`)) return
  try {
    await run(async () => {
      replace(await api.grant(selectedId.value, { requestKey: grantForm.requestKey, credits, note: grantForm.note.trim() }))
      Object.assign(grantForm, { credits: '', note: '', requestKey: 'grant-' + crypto.randomUUID(), error: '' })
      await loadDetail()
    }, `已发放 ${number(credits)} 烟火`)
  } catch (failure) { grantForm.error = failure.message || '发放失败，请用同一金额再试一次。' }
}

async function saveQuota() {
  const quota = Number(quotaForm.quota)
  quotaForm.error = !quotaForm.planCode ? '请选择套餐。' : !Number.isInteger(quota) || quota < 0 || quota > 1000000 ? '额度须为 0 至 1,000,000 的整数。' : ''
  if (quotaForm.error) return
  try {
    await run(async () => {
      replace(await api.setQuota(selectedId.value, quotaForm.planCode, quota))
      Object.assign(quotaForm, { planCode: '', quota: '', error: '' })
    }, '额度已更新')
  } catch (failure) { quotaForm.error = failure.message || '保存失败，请稍后再试。' }
}

async function addMember() {
  memberForm.error = validUid(memberForm.uid) ? '' : '用户 ID 是 8 位数字。'
  if (memberForm.error) return
  try {
    await run(async () => { replace(await api.addMember(selectedId.value, memberForm.uid.trim())); memberForm.uid = '' }, '登录账号已添加')
  } catch (failure) { memberForm.error = failure.message || '添加失败，请稍后再试。' }
}

async function removeMember(member) {
  if (!window.confirm(`移除「${member.name || member.uid}」后，这个账号不能再登录服务商后台。确定移除吗？`)) return
  try { await run(async () => replace(await api.removeMember(selectedId.value, member.userId)), '登录账号已移除') }
  catch (failure) { memberForm.error = failure.message || '移除失败，请稍后再试。' }
}

async function toggleStatus() {
  const next = selected.value.partner.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (next === 'DISABLED' && !window.confirm('停用后这家服务商不能登录后台、分配烟火和生成激活码；已生成的激活码照常可用。确定停用吗？')) return
  try { await run(async () => replace(await api.setStatus(selectedId.value, next)), next === 'ACTIVE' ? '已启用' : '已停用') }
  catch (failure) { error.value = failure.message || '操作失败，请稍后再试。' }
}

async function lookup() {
  assignForm.error = validUid(assignForm.uid) ? '' : '用户 ID 是 8 位数字。'
  assignForm.found = null
  if (assignForm.error) return
  try { assignForm.found = await api.lookup(assignForm.uid.trim()); assignForm.partnerId = assignForm.found.partnerId ? String(assignForm.found.partnerId) : '' }
  catch (failure) { assignForm.error = failure.message || '查询失败，请稍后再试。' }
}

async function reassign() {
  const found = assignForm.found
  const target = partners.value.find(item => String(item.partner.id) === assignForm.partnerId)
  if (!window.confirm(`把「${found.tenantName || '该商户'}」${target ? `改归「${target.partner.name}」` : '解除归属'}？`)) return
  try {
    await run(async () => {
      await api.reassign(found.tenantId, target?.partner.id || null)
      await Promise.all([lookup(), load(), loadDetail()])
    }, '归属已更新')
  } catch (failure) { assignForm.error = failure.message || '修改失败，请稍后再试。' }
}

const membership = item => item.memberActive == null ? '—' : item.memberActive ? `${item.planName || '会员'} · ${managementDate(item.memberUntil).slice(0, 10)} 到期` : '未激活'
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>服务商</span></div><h1>服务商</h1><p>服务商用自己的一方志账号登录 partner.yifangzhi.com。线下收款后在这里给服务商发烟火、按套餐分激活码额度；名下商户第一次归属为准，只有这里能改。</p></div>
      <div class="management-heading-actions">
        <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
        <button v-if="!forbidden" class="mg-button mg-primary" type="button" @click="createForm.open = true"><Plus :size="15" />新建服务商</button>
      </div>
    </header>
    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section v-if="createForm.open" class="management-list" aria-labelledby="partner-create-title">
      <div class="mg-list-caption"><h2 id="partner-create-title">新建服务商</h2><button class="mg-text-button" type="button" @click="createForm.open = false">取消</button></div>
      <form class="partner-admin-form" @submit.prevent="create">
        <label>服务商名称<input v-model="createForm.name" maxlength="80" required /></label>
        <label>联系方式（可选）<input v-model="createForm.contact" maxlength="120" placeholder="联系人、电话" /></label>
        <label>登录账号的用户 ID<input v-model="createForm.adminUid" inputmode="numeric" maxlength="8" placeholder="8 位数字" required /><small>对方先注册一方志账号，在「套餐与权益」页能看到用户 ID。</small></label>
        <p v-if="createForm.error" class="mg-error" role="alert">{{ createForm.error }}</p>
        <div><button class="mg-button mg-primary" type="submit" :disabled="busy">创建</button></div>
      </form>
    </section>

    <section class="management-list" :aria-busy="loading" aria-label="服务商列表">
      <div v-if="forbidden" class="mg-state mg-error" role="alert"><Store :size="30" /><h3>没有服务商管理权限</h3><p>服务商涉及烟火和激活码，需要把你的账号加入服务端配置 BILLING_OPERATOR_USER_IDS 后才能管理。</p></div>
      <div v-else-if="error" class="mg-state mg-error" role="alert"><h3>暂时无法读取</h3><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="!partners.length && !loading" class="mg-state"><Store :size="32" /><h3>还没有服务商</h3><p>点右上角「新建服务商」。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>服务商</th><th>状态</th><th class="code-number">可分配烟火</th><th>激活码额度（已生成 / 总数）</th><th class="code-number">名下商户</th><th class="mg-table-action">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in partners" :key="item.partner.id" :class="{ 'is-selected': item.partner.id === selectedId }">
              <td><span>{{ item.partner.name }}</span><small>{{ item.partner.contact || '—' }}</small></td>
              <td>{{ item.partner.status === 'ACTIVE' ? '启用中' : '已停用' }}</td>
              <td class="code-number mg-mono">{{ number(item.partner.creditsAvailable) }}</td>
              <td><template v-if="item.quotas.length"><span v-for="q in item.quotas" :key="q.planCode" class="partner-quota">{{ q.planName || q.planCode }} {{ q.used }} / {{ q.quota }}</span></template><template v-else>—</template></td>
              <td class="code-number mg-mono">{{ number(item.tenantCount) }}</td>
              <td class="mg-table-action"><button class="mg-text-button" type="button" @click="select(item.partner.id)">管理</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <template v-if="selected">
      <section class="management-list" aria-labelledby="partner-detail-title">
        <div class="mg-list-caption"><h2 id="partner-detail-title">{{ selected.partner.name }}</h2><span><button class="mg-text-button" type="button" :disabled="busy" @click="toggleStatus">{{ selected.partner.status === 'ACTIVE' ? '停用' : '启用' }}</button> · <button class="mg-text-button" type="button" @click="selectedId = null">收起</button></span></div>
        <div class="partner-admin-grid">
          <form class="partner-admin-form" @submit.prevent="grant">
            <h3>发放烟火</h3>
            <label>烟火数<input v-model="grantForm.credits" type="number" min="1" max="10000000" step="1" /></label>
            <label>备注<input v-model="grantForm.note" maxlength="200" placeholder="收款流水号、金额" /></label>
            <p v-if="grantForm.error" class="mg-error" role="alert">{{ grantForm.error }}</p>
            <div><button class="mg-button mg-primary" type="submit" :disabled="busy">发放</button></div>
          </form>
          <form class="partner-admin-form" @submit.prevent="saveQuota">
            <h3>激活码额度</h3>
            <label>套餐<select v-model="quotaForm.planCode"><option value="" disabled>选择套餐</option><option v-for="plan in plans" :key="plan.code" :value="plan.code">{{ plan.name }} · {{ plan.durationDays }} 天</option></select></label>
            <label>总额度（个）<input v-model="quotaForm.quota" type="number" min="0" max="1000000" step="1" /><small>设的是总数，不能低于已经生成的个数。</small></label>
            <p v-if="quotaForm.error" class="mg-error" role="alert">{{ quotaForm.error }}</p>
            <div><button class="mg-button mg-primary" type="submit" :disabled="busy">保存额度</button></div>
          </form>
          <form class="partner-admin-form" @submit.prevent="addMember">
            <h3>登录账号</h3>
            <ul class="partner-members"><li v-for="member in selected.members" :key="member.userId"><span>{{ member.name || '未命名' }} · 用户 ID {{ member.uid }}</span><button class="mg-text-button" type="button" :disabled="busy" @click="removeMember(member)">移除</button></li></ul>
            <label>添加账号的用户 ID<input v-model="memberForm.uid" inputmode="numeric" maxlength="8" /></label>
            <p v-if="memberForm.error" class="mg-error" role="alert">{{ memberForm.error }}</p>
            <div><button class="mg-button" type="submit" :disabled="busy">添加</button></div>
          </form>
        </div>
      </section>

      <section class="management-list" aria-labelledby="partner-tenants-title">
        <div class="mg-list-caption"><h2 id="partner-tenants-title">名下商户（最近 50 个）</h2></div>
        <div v-if="detailError" class="mg-state mg-error" role="alert"><p>{{ detailError }}</p></div>
        <div v-else-if="!tenants.length" class="mg-state"><p>还没有名下商户。</p></div>
        <div v-else class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>商户</th><th>联系人</th><th>会员</th><th class="code-number">烟火余额</th><th>归属方式</th></tr></thead>
            <tbody><tr v-for="item in tenants" :key="item.tenantId"><td><span>{{ item.tenantName || '—' }}</span><small>商户 #{{ item.tenantId }}</small></td><td><span>{{ item.ownerName || '—' }}</span><small>用户 ID {{ item.ownerUid || '—' }}</small></td><td>{{ membership(item) }}</td><td class="code-number mg-mono">{{ item.creditsAvailable == null ? '—' : number(item.creditsAvailable) }}</td><td><span>{{ tenantSources[item.source] || item.source }}</span><small>{{ managementDate(item.boundAt) }}</small></td></tr></tbody>
          </table>
        </div>
      </section>

      <section class="management-list" aria-labelledby="partner-ledger-title">
        <div class="mg-list-caption"><h2 id="partner-ledger-title">烟火流水（最近 20 条）</h2></div>
        <div v-if="!ledger.length" class="mg-state"><p>还没有流水。</p></div>
        <div v-else class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>时间</th><th>类型</th><th>商户</th><th class="code-number">变动</th><th class="code-number">变动后</th><th>备注</th></tr></thead>
            <tbody><tr v-for="item in ledger" :key="item.id"><td class="mg-time">{{ managementDate(item.createdAt) }}</td><td>{{ ledgerTypes[item.type] || item.type }}</td><td>{{ item.tenantName || '—' }}</td><td class="code-number mg-mono">{{ item.delta > 0 ? '+' : '' }}{{ number(item.delta) }}</td><td class="code-number mg-mono">{{ number(item.availableAfter) }}</td><td>{{ item.note || '—' }}</td></tr></tbody>
          </table>
        </div>
      </section>
    </template>

    <section v-if="!forbidden" class="management-list" aria-labelledby="assign-title">
      <div class="mg-list-caption"><h2 id="assign-title">改商户归属</h2></div>
      <form class="partner-admin-form partner-admin-inline" @submit.prevent="lookup">
        <label>商户任一账号的用户 ID<input v-model="assignForm.uid" inputmode="numeric" maxlength="8" /></label>
        <div><button class="mg-button" type="submit" :disabled="busy">查询</button></div>
      </form>
      <div v-if="assignForm.found" class="partner-admin-form partner-admin-inline">
        <p>「{{ assignForm.found.tenantName || '商户' }}」（{{ assignForm.found.userName || '账号' }}）现在{{ assignForm.found.partnerName ? `归属「${assignForm.found.partnerName}」，方式：${tenantSources[assignForm.found.source] || assignForm.found.source}` : '没有归属服务商' }}。</p>
        <label>改为<select v-model="assignForm.partnerId"><option value="">不归属任何服务商</option><option v-for="item in partners" :key="item.partner.id" :value="String(item.partner.id)">{{ item.partner.name }}</option></select></label>
        <div><button class="mg-button mg-primary" type="button" :disabled="busy || assignForm.partnerId === String(assignForm.found.partnerId || '')" @click="reassign">保存归属</button></div>
      </div>
      <p v-if="assignForm.error" class="mg-error partner-admin-form" role="alert">{{ assignForm.error }}</p>
    </section>
  </div>
</template>

<style scoped>
.partner-admin-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 0; }
.partner-admin-form { display: grid; align-content: start; gap: 12px; padding: 16px 20px; }
.partner-admin-grid .partner-admin-form + .partner-admin-form { border-left: 1px solid var(--mg-line); }
.partner-admin-form h3 { margin: 0; font-size: 14px; font-weight: 600; }
.partner-admin-form label { display: grid; gap: 6px; color: var(--mg-muted); font-size: 12px; }
.partner-admin-form :is(input, select) { min-height: 36px; padding: 6px 10px; border: 1px solid var(--mg-line); border-radius: 5px; background: #fff; color: var(--mg-ink); }
.partner-admin-form small { color: var(--mg-muted); font-size: 11px; }
.partner-admin-form p { margin: 0; font-size: 13px; }
.partner-admin-inline { grid-template-columns: minmax(0, 320px) auto; align-items: end; }
.partner-members { display: grid; gap: 6px; margin: 0; padding: 0; list-style: none; font-size: 13px; }
.partner-members li { display: flex; justify-content: space-between; gap: 12px; }
.partner-quota { display: block; font-size: 12px; }
.code-number { text-align: right; }
.management-table td > small { display: block; margin-top: 2px; color: var(--mg-muted); font-size: 11px; }
tr.is-selected td { background: var(--mg-soft); }
@media (max-width: 760px) { .partner-admin-grid .partner-admin-form + .partner-admin-form { border-left: 0; border-top: 1px solid var(--mg-line); } .partner-admin-inline { grid-template-columns: minmax(0, 1fr); } }
</style>
