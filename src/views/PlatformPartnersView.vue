<script setup>
import { computed, reactive, ref } from 'vue'
import { Check, ChevronLeft, ChevronRight, Plus, RefreshCw, Store, X } from 'lucide-vue-next'
import { partnerAdminApi as api, ledgerTypes, tenantSources, validUid } from '../services/partnerApi'
import { managementDate } from '../domain/platformManagement'
import { defaultPermissions, permissionItems, permissionText } from '../domain/partnerOps'
import '../platform-management.css'

const number = value => Number(value || 0).toLocaleString('zh-CN')
const partners = ref([])
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
// 名下商户和流水分别翻页，每页 20 条。
const tenantPaging = reactive({ page: 0, total: 0 })
const ledgerPaging = reactive({ page: 0, total: 0 })
const pageCount = paging => Math.ceil(paging.total / 20)

const createForm = reactive({ open: false, name: '', contact: '', adminUid: '', error: '' })
const grantForm = reactive({ credits: '', note: '', requestKey: '', error: '' })
const memberForm = reactive({ uid: '', error: '' })
const teamForm = reactive({ by: 'phone', phone: '', name: '', uid: '', permissions: defaultPermissions(), monthlyCap: '', error: '' })
const assignForm = reactive({ uid: '', found: null, partnerId: '', error: '' })
const renameForm = reactive({ open: false, name: '', contact: '', error: '' })

async function load() {
  loading.value = true
  error.value = ''
  forbidden.value = false
  try {
    partners.value = await api.list()
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
  Object.assign(memberForm, { uid: '', error: '' })
  resetTeamForm()
  tenantPaging.page = 0
  ledgerPaging.page = 0
  await loadDetail()
}

async function loadDetail() {
  if (!selectedId.value) return
  detailError.value = ''
  try {
    const [tenantPage, ledgerPage] = await Promise.all([api.tenants(selectedId.value, { page: tenantPaging.page, size: 20 }),
      api.ledger(selectedId.value, { page: ledgerPaging.page, size: 20 })])
    tenants.value = tenantPage.items
    ledger.value = ledgerPage.items
    tenantPaging.total = tenantPage.total
    ledgerPaging.total = ledgerPage.total
  } catch (failure) {
    detailError.value = failure.message || '名下商户和流水暂时无法读取。'
  }
}

function turn(paging, next) { paging.page = next; loadDetail() }

async function run(work, done) {
  if (busy.value) return
  busy.value = true
  try { await work(); if (done) success.value = done }
  finally { busy.value = false }
}

async function create() {
  createForm.error = !createForm.name.trim() ? '请填写服务商名称。' : !validUid(createForm.adminUid) ? '登录账号的 UID 是 8 位数字。' : ''
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

const partnerRoleNames = { OWNER: '负责人', MEMBER: '成员' }

async function addMember() {
  memberForm.error = validUid(memberForm.uid) ? '' : 'UID 是 8 位数字。'
  if (memberForm.error) return
  try {
    await run(async () => { replace(await api.addMember(selectedId.value, memberForm.uid.trim())); memberForm.uid = '' }, '登录账号已添加')
  } catch (failure) { memberForm.error = failure.message || '添加失败，请稍后再试。' }
}

async function removeMember(member) {
  const who = member.displayName || member.name || member.uid
  const effect = member.role === 'OWNER' ? '这个账号不能再登录服务商后台，他自己的一方志账号不受影响' : '他会移出团队，团队账户里的员工账号一并停用（不能再登录一方志、手机号释放）'
  if (!window.confirm(`移除「${who}」后，${effect}。确定移除吗？`)) return
  try { await run(async () => replace(await api.removeMember(selectedId.value, member.userId)), '已移除') }
  catch (failure) { memberForm.error = failure.message || '移除失败，请稍后再试。' }
}

function resetTeamForm() {
  Object.assign(teamForm, { by: 'phone', phone: '', name: '', uid: '', permissions: defaultPermissions(), monthlyCap: '', error: '' })
}

async function addTeamMember() {
  const cap = teamForm.permissions.distribute && String(teamForm.monthlyCap).trim() ? Number(teamForm.monthlyCap) : null
  teamForm.error = teamForm.by === 'uid'
    ? (validUid(teamForm.uid) ? '' : 'UID 是 8 位数字。')
    : !/^1\d{10}$/.test(teamForm.phone.trim()) ? '请填写 11 位手机号。' : !teamForm.name.trim() ? '请填写姓名。' : ''
  if (!teamForm.error && cap !== null && (!Number.isInteger(cap) || cap < 1 || cap > 100000000)) teamForm.error = '每月上限须为 1 至 100,000,000 的整数，不限就留空。'
  if (teamForm.error) return
  const who = teamForm.by === 'uid' ? { uid: Number(teamForm.uid) } : { phone: teamForm.phone.trim(), name: teamForm.name.trim() }
  try {
    await run(async () => {
      replace(await api.addTeamMember(selectedId.value, { ...who, permissions: { ...teamForm.permissions }, monthlyCap: cap }))
      resetTeamForm()
    }, '成员已添加')
  } catch (failure) { teamForm.error = failure.message || '添加失败，请稍后再试。' }
}

async function removePartner() {
  const item = selected.value
  const effects = [
    `全部 ${item.members.length} 个登录账号移出，不能再进服务商后台`,
    `${item.tenantCount} 个名下商户解除归属，别家可以再加`,
    '没兑换的激活码全部作废（已兑换的不受影响）',
    `团队账户「${item.teamTenantName || '—'}」的烟火不动，发放和分配记录保留`,
  ]
  if (!window.confirm(`删除「${item.partner.name}」？\n\n${effects.map(line => '· ' + line).join('\n')}\n\n删除后这里看不到这家服务商，也不能恢复。`)) return
  try {
    await run(async () => {
      await api.remove(item.partner.id)
      partners.value = partners.value.filter(row => row.partner.id !== item.partner.id)
      selectedId.value = null
    }, `「${item.partner.name}」已删除`)
  } catch (failure) { error.value = failure.message || '删除失败，请稍后再试。' }
}

function startRename() {
  Object.assign(renameForm, { open: true, name: selected.value.partner.name, contact: selected.value.partner.contact || '', error: '' })
}

async function rename() {
  renameForm.error = renameForm.name.trim() ? '' : '请填写服务商名称。'
  if (renameForm.error) return
  try {
    await run(async () => {
      replace(await api.rename(selectedId.value, { name: renameForm.name.trim(), contact: renameForm.contact.trim() || null }))
      renameForm.open = false
    }, '名称已更新，服务商后台里同步显示')
  } catch (failure) { renameForm.error = failure.message || '保存失败，请稍后再试。' }
}

async function toggleStatus() {
  const next = selected.value.partner.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (next === 'DISABLED' && !window.confirm('停用后这家服务商不能登录后台、分配烟火和生成激活码；已生成的激活码照常可用。确定停用吗？')) return
  try { await run(async () => replace(await api.setStatus(selectedId.value, next)), next === 'ACTIVE' ? '已启用' : '已停用') }
  catch (failure) { error.value = failure.message || '操作失败，请稍后再试。' }
}

async function lookup() {
  assignForm.error = validUid(assignForm.uid) ? '' : 'UID 是 8 位数字。'
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
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>服务商</span></div><h1>服务商</h1><p>管理服务商的负责人账号、烟火发放及商户归属。负责人的一方志商户就是服务商的团队账户：平台发的烟火进它，成员在一方志创作和分给商户都扣它。服务商生成激活码不限个数。商户以首次建立的服务商归属为准，跨服务商调整由平台管理。</p></div>
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
        <label>登录账号的 UID<input v-model="createForm.adminUid" inputmode="numeric" maxlength="8" placeholder="8 位数字" required /><small>对方先注册一方志账号，在「套餐与权益」页能看到 UID。</small></label>
        <p v-if="createForm.error" class="mg-error" role="alert">{{ createForm.error }}</p>
        <div><button class="mg-button mg-primary" type="submit" :disabled="busy">创建</button></div>
      </form>
    </section>

    <section class="management-list" :aria-busy="loading" aria-label="服务商列表">
      <div v-if="forbidden" class="mg-state mg-error" role="alert"><Store :size="30" /><h3>没有服务商管理权限</h3><p>当前账号未获服务商管理权限，请联系平台管理员授权。</p></div>
      <div v-else-if="error" class="mg-state mg-error" role="alert"><h3>暂时无法读取</h3><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="!partners.length && !loading" class="mg-state"><Store :size="32" /><h3>还没有服务商</h3><p>点右上角「新建服务商」。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table">
          <thead><tr><th>服务商</th><th>状态</th><th>团队账户</th><th class="code-number">团队烟火</th><th class="code-number">成员</th><th class="code-number">名下商户</th><th class="mg-table-action">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in partners" :key="item.partner.id" :class="{ 'is-selected': item.partner.id === selectedId }">
              <td><span>{{ item.partner.name }}</span><small>{{ item.partner.contact || '—' }}</small></td>
              <td>{{ item.partner.status === 'ACTIVE' ? '启用中' : '已停用' }}</td>
              <td>{{ item.teamTenantName || '—' }}</td>
              <td class="code-number mg-mono">{{ item.partner.creditsAvailable == null ? '—' : number(item.partner.creditsAvailable) }}</td>
              <td class="code-number mg-mono">{{ number(item.members.length) }}</td>
              <td class="code-number mg-mono">{{ number(item.tenantCount) }}</td>
              <td class="mg-table-action"><button class="mg-text-button" type="button" @click="select(item.partner.id)">管理</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <template v-if="selected">
      <section class="management-list" aria-labelledby="partner-detail-title">
        <div class="mg-list-caption"><h2 id="partner-detail-title">{{ selected.partner.name }}</h2><span><button class="mg-text-button" type="button" :disabled="busy" @click="startRename">改名称</button> · <button class="mg-text-button" type="button" :disabled="busy" @click="toggleStatus">{{ selected.partner.status === 'ACTIVE' ? '停用' : '启用' }}</button> · <button class="mg-text-button partner-danger" type="button" :disabled="busy" @click="removePartner">删除</button> · <button class="mg-text-button" type="button" @click="selectedId = null">收起</button></span></div>
        <form v-if="renameForm.open" class="partner-admin-form partner-admin-inline" @submit.prevent="rename">
          <label>服务商名称<input v-model="renameForm.name" maxlength="80" required /><small>就是服务商后台（wy.yifangzhi.com/partner/）里显示的公司名。</small></label>
          <label>联系方式（可选）<input v-model="renameForm.contact" maxlength="120" /></label>
          <p v-if="renameForm.error" class="mg-error" role="alert">{{ renameForm.error }}</p>
          <div><button class="mg-button mg-primary" type="submit" :disabled="busy">保存</button> <button class="mg-button" type="button" :disabled="busy" @click="renameForm.open = false">取消</button></div>
        </form>
        <div class="partner-admin-grid">
          <form class="partner-admin-form" @submit.prevent="grant">
            <h3>发放烟火</h3>
            <p class="mg-muted">进团队账户「{{ selected.teamTenantName || '—' }}」，记为充值烟火。</p>
            <label>烟火数<input v-model="grantForm.credits" type="number" min="1" max="10000000" step="1" /></label>
            <label>备注<input v-model="grantForm.note" maxlength="200" placeholder="收款流水号、金额" /></label>
            <p v-if="grantForm.error" class="mg-error" role="alert">{{ grantForm.error }}</p>
            <div><button class="mg-button mg-primary" type="submit" :disabled="busy">发放</button></div>
          </form>
          <form class="partner-admin-form" @submit.prevent="addMember">
            <h3>登录账号</h3>
            <ul class="partner-members"><li v-for="member in selected.members" :key="member.userId"><span>{{ partnerRoleNames[member.role] || member.role }} · {{ member.displayName || member.name || '未命名' }} · UID {{ member.uid }}<small>权限：{{ permissionText(member) }}</small></span><button class="mg-text-button" type="button" :disabled="busy" @click="removeMember(member)">移除</button></li></ul>
            <label>添加负责人账号的 UID<input v-model="memberForm.uid" inputmode="numeric" maxlength="8" /><small>负责人有全部权限；他的一方志商户就是团队账户。</small></label>
            <p v-if="memberForm.error" class="mg-error" role="alert">{{ memberForm.error }}</p>
            <div><button class="mg-button" type="submit" :disabled="busy">添加</button></div>
          </form>
          <form class="partner-admin-form" @submit.prevent="addTeamMember">
            <h3>添加成员</h3>
            <p class="mg-muted">成员是团队账户「{{ selected.teamTenantName || '—' }}」的员工，在一方志创作扣团队烟火。</p>
            <label>添加方式<select v-model="teamForm.by"><option value="phone">按手机号</option><option value="uid">按 UID</option></select></label>
            <template v-if="teamForm.by === 'phone'">
              <label>手机号<input v-model="teamForm.phone" inputmode="tel" maxlength="11" /><small>没注册过的在团队账户里新建员工账号；注册了别的商户的号加不进来。</small></label>
              <label>姓名<input v-model="teamForm.name" maxlength="20" /></label>
            </template>
            <label v-else>UID<input v-model="teamForm.uid" inputmode="numeric" maxlength="8" /><small>只能加已经是团队账户员工的账号（例如在一方志员工管理里加过的店员）。</small></label>
            <fieldset class="partner-perms"><legend>权限</legend><label v-for="item in permissionItems" :key="item.key" :title="item.hint"><input v-model="teamForm.permissions[item.key]" type="checkbox" />{{ item.label }}</label></fieldset>
            <label v-if="teamForm.permissions.distribute">每月分配上限（可选）<input v-model="teamForm.monthlyCap" type="number" min="1" max="100000000" step="1" placeholder="不填表示不限" /></label>
            <p v-if="teamForm.error" class="mg-error" role="alert">{{ teamForm.error }}</p>
            <div><button class="mg-button" type="submit" :disabled="busy">添加成员</button></div>
          </form>
        </div>
      </section>

      <section class="management-list" aria-labelledby="partner-tenants-title">
        <div class="mg-list-caption"><h2 id="partner-tenants-title">名下商户</h2></div>
        <div v-if="detailError" class="mg-state mg-error" role="alert"><p>{{ detailError }}</p></div>
        <div v-else-if="!tenants.length" class="mg-state"><p>还没有名下商户。</p></div>
        <div v-else class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>商户</th><th>联系人</th><th>会员</th><th class="code-number">烟火余额</th><th>加入时间</th><th>来源</th></tr></thead>
            <tbody><tr v-for="item in tenants" :key="item.tenantId"><td><span>{{ item.tenantName || '—' }}</span><small>商户 #{{ item.tenantId }}</small></td><td><span>{{ item.ownerName || '—' }}</span><small>UID {{ item.ownerUid || '—' }}</small></td><td>{{ membership(item) }}</td><td class="code-number mg-mono">{{ item.creditsAvailable == null ? '—' : number(item.creditsAvailable) }}</td><td>{{ managementDate(item.boundAt) }}</td><td><span>{{ tenantSources[item.source] || item.source }}</span><small v-if="item.addedByName">{{ item.addedByName }} 添加</small></td></tr></tbody>
          </table>
        </div>
        <footer v-if="pageCount(tenantPaging) > 1" class="management-pagination"><span>共 {{ number(tenantPaging.total) }} 个商户</span><div><button class="mg-page-button" type="button" :disabled="tenantPaging.page === 0" aria-label="上一页" @click="turn(tenantPaging, tenantPaging.page - 1)"><ChevronLeft :size="16" /></button><span>{{ tenantPaging.page + 1 }} / {{ pageCount(tenantPaging) }}</span><button class="mg-page-button" type="button" :disabled="tenantPaging.page + 1 >= pageCount(tenantPaging)" aria-label="下一页" @click="turn(tenantPaging, tenantPaging.page + 1)"><ChevronRight :size="16" /></button></div></footer>
      </section>

      <section class="management-list" aria-labelledby="partner-ledger-title">
        <div class="mg-list-caption"><h2 id="partner-ledger-title">烟火流水</h2></div>
        <div v-if="!ledger.length" class="mg-state"><p>还没有流水。</p></div>
        <div v-else class="management-table-wrap">
          <table class="management-table">
            <thead><tr><th>时间</th><th>类型</th><th>商户</th><th class="code-number">变动</th><th class="code-number">变动后</th><th>备注</th></tr></thead>
            <tbody><tr v-for="item in ledger" :key="item.id"><td class="mg-time">{{ managementDate(item.createdAt) }}</td><td>{{ ledgerTypes[item.type] || item.type }}</td><td>{{ item.tenantName || '—' }}</td><td class="code-number mg-mono">{{ item.delta > 0 ? '+' : '' }}{{ number(item.delta) }}</td><td class="code-number mg-mono">{{ item.availableAfter == null ? '—' : number(item.availableAfter) }}</td><td>{{ item.note || '—' }}</td></tr></tbody>
          </table>
        </div>
        <footer v-if="pageCount(ledgerPaging) > 1" class="management-pagination"><span>共 {{ number(ledgerPaging.total) }} 条</span><div><button class="mg-page-button" type="button" :disabled="ledgerPaging.page === 0" aria-label="上一页" @click="turn(ledgerPaging, ledgerPaging.page - 1)"><ChevronLeft :size="16" /></button><span>{{ ledgerPaging.page + 1 }} / {{ pageCount(ledgerPaging) }}</span><button class="mg-page-button" type="button" :disabled="ledgerPaging.page + 1 >= pageCount(ledgerPaging)" aria-label="下一页" @click="turn(ledgerPaging, ledgerPaging.page + 1)"><ChevronRight :size="16" /></button></div></footer>
      </section>
    </template>

    <section v-if="!forbidden" class="management-list" aria-labelledby="assign-title">
      <div class="mg-list-caption"><h2 id="assign-title">改商户归属</h2></div>
      <form class="partner-admin-form partner-admin-inline" @submit.prevent="lookup">
        <label>商户任一账号的 UID<input v-model="assignForm.uid" inputmode="numeric" maxlength="8" /></label>
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
.partner-members li small { display: block; margin-top: 2px; }
.partner-perms { display: flex; flex-wrap: wrap; gap: 6px 14px; margin: 0; padding: 0; border: 0; }
.partner-perms legend { margin-bottom: 6px; padding: 0; color: var(--mg-muted); font-size: 12px; }
.partner-perms label { display: inline-flex; align-items: center; gap: 4px; color: var(--mg-ink); font-size: 13px; }
.partner-perms input { min-height: 0; }
.partner-danger { color: #c0392b; }
.code-number { text-align: right; }
.management-table td > small { display: block; margin-top: 2px; color: var(--mg-muted); font-size: 11px; }
tr.is-selected td { background: var(--mg-soft); }
@media (max-width: 760px) { .partner-admin-grid .partner-admin-form + .partner-admin-form { border-left: 0; border-top: 1px solid var(--mg-line); } .partner-admin-inline { grid-template-columns: minmax(0, 1fr); } }
</style>
