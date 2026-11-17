<script setup>
import { computed, reactive, ref } from 'vue'
import { Dialog, DialogPanel, DialogTitle, DialogDescription } from '@headlessui/vue'
import { Check, ChevronLeft, ChevronRight, Flame, Handshake, Plus, RefreshCw, Search, Store, Trash2, UserPlus, Users, X } from 'lucide-vue-next'
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
const tab = ref('overview')
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
// 账号与成员页里展开哪个添加表单：'' / 'member' / 'owner'。
const adding = ref('')
// 页内确认：提交前先在页面里说清这次操作的结果，不用浏览器自带的确认框。
const pending = ref(null)
const actionError = ref('')

const partnerRoleNames = { OWNER: '负责人', MEMBER: '成员' }
const ownerOf = item => item?.members.find(member => member.role === 'OWNER') || null
const personName = member => member ? member.displayName || member.name || '未命名' : '—'

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

async function open(id) {
  selectedId.value = id
  tab.value = 'overview'
  pending.value = null
  actionError.value = ''
  adding.value = ''
  renameForm.open = false
  Object.assign(grantForm, { credits: '', note: '', requestKey: 'grant-' + crypto.randomUUID(), error: '' })
  Object.assign(memberForm, { uid: '', error: '' })
  resetTeamForm()
  tenantPaging.page = 0
  ledgerPaging.page = 0
  tenants.value = []
  ledger.value = []
  await loadDetail()
}

function close() { if (!busy.value) selectedId.value = null }

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
    detailError.value = failure.message || '名下商户和烟火记录暂时无法读取。'
  }
}

function turn(paging, next) { paging.page = next; loadDetail() }

async function run(work, done) {
  if (busy.value) return
  busy.value = true
  try { await work(); if (done) success.value = done }
  finally { busy.value = false }
}

function ask(kind, payload = null) { pending.value = { kind, payload }; actionError.value = '' }
const asking = (kind, payload = null) => pending.value?.kind === kind && (payload === null || pending.value.payload === payload)

async function create() {
  createForm.error = !createForm.name.trim() ? '请填写服务商名称。' : !validUid(createForm.adminUid) ? '负责人的 UID 是 8 位数字。' : ''
  if (createForm.error) return
  try {
    await run(async () => {
      const created = await api.create({ name: createForm.name.trim(), contact: createForm.contact.trim() || null, adminUid: Number(createForm.adminUid) })
      partners.value = [created, ...partners.value]
      Object.assign(createForm, { open: false, name: '', contact: '', adminUid: '', error: '' })
      await open(created.partner.id)
    }, '服务商已开通，负责人用自己的一方志账号登录 wy.yifangzhi.com/partner/')
  } catch (failure) { createForm.error = failure.message || '创建失败，请稍后再试。' }
}

const grantCredits = computed(() => Number(grantForm.credits))
function checkGrant() {
  const credits = grantCredits.value
  grantForm.error = !Number.isInteger(credits) || credits < 1 || credits > 10000000 ? '烟火须为 1 至 10,000,000 的整数。' : !grantForm.note.trim() ? '请填写备注（例如收款流水号）。' : ''
  if (!grantForm.error) ask('grant')
}

async function grant() {
  const credits = grantCredits.value
  try {
    await run(async () => {
      replace(await api.grant(selectedId.value, { requestKey: grantForm.requestKey, credits, note: grantForm.note.trim() }))
      Object.assign(grantForm, { credits: '', note: '', requestKey: 'grant-' + crypto.randomUUID(), error: '' })
      pending.value = null
      await loadDetail()
    }, `已发放 ${number(credits)} 烟火`)
  } catch (failure) { pending.value = null; grantForm.error = failure.message || '发放失败，请用同一金额再试一次。' }
}

async function addOwner() {
  memberForm.error = validUid(memberForm.uid) ? '' : 'UID 是 8 位数字。'
  if (memberForm.error) return
  try {
    await run(async () => { replace(await api.addMember(selectedId.value, memberForm.uid.trim())); memberForm.uid = ''; adding.value = '' }, '负责人已添加')
  } catch (failure) { memberForm.error = failure.message || '添加失败，请稍后再试。' }
}

async function removeMember(member) {
  try {
    await run(async () => { replace(await api.removeMember(selectedId.value, member.userId)); pending.value = null }, `已移除「${personName(member)}」`)
  } catch (failure) { actionError.value = failure.message || '移除失败，请稍后再试。' }
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
      adding.value = ''
    }, '成员已添加')
  } catch (failure) { teamForm.error = failure.message || '添加失败，请稍后再试。' }
}

function startRename() {
  tab.value = 'overview'
  pending.value = null
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

async function setStatus(next) {
  try { await run(async () => { replace(await api.setStatus(selectedId.value, next)); pending.value = null }, next === 'ACTIVE' ? '已启用' : '已停用') }
  catch (failure) { actionError.value = failure.message || '操作失败，请稍后再试。' }
}

async function removePartner() {
  const item = selected.value
  try {
    await run(async () => {
      await api.remove(item.partner.id)
      partners.value = partners.value.filter(row => row.partner.id !== item.partner.id)
      pending.value = null
      selectedId.value = null
    }, `「${item.partner.name}」已删除`)
  } catch (failure) { actionError.value = failure.message || '删除失败，请稍后再试。' }
}

async function lookup() {
  assignForm.error = validUid(assignForm.uid) ? '' : 'UID 是 8 位数字。'
  assignForm.found = null
  if (assignForm.error) return
  try { assignForm.found = await api.lookup(assignForm.uid.trim()); assignForm.partnerId = assignForm.found.partnerId ? String(assignForm.found.partnerId) : '' }
  catch (failure) { assignForm.error = failure.message || '查询失败，请稍后再试。' }
}

const assignTarget = computed(() => partners.value.find(item => String(item.partner.id) === assignForm.partnerId) || null)

async function reassign() {
  const found = assignForm.found
  try {
    await run(async () => {
      await api.reassign(found.tenantId, assignTarget.value?.partner.id || null)
      pending.value = null
      await Promise.all([lookup(), load(), loadDetail()])
    }, '归属已更新')
  } catch (failure) { pending.value = null; assignForm.error = failure.message || '修改失败，请稍后再试。' }
}

const membership = item => item.memberActive == null ? '—' : item.memberActive ? `${item.planName || '会员'} · ${managementDate(item.memberUntil).slice(0, 10)} 到期` : '未开通'
load()
</script>

<template>
  <div class="management-page">
    <header class="management-heading">
      <div><div class="management-breadcrumb"><span>平台管理</span><ChevronRight :size="13" /><span>服务商</span></div><h1>服务商</h1><p>服务商是帮平台推广的合作公司。开通后，他们在自己的后台给商户开会员、分烟火。</p></div>
      <div class="management-heading-actions">
        <button class="mg-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'mg-spinning': loading }" />刷新</button>
        <button v-if="!forbidden" class="mg-button mg-primary" type="button" @click="createForm.open = true"><Plus :size="15" />新建服务商</button>
      </div>
    </header>

    <ol v-if="!forbidden" class="partner-steps" aria-label="怎么用">
      <li><b>1</b><div><strong>开通服务商</strong><p>填公司名和老板的 UID。老板的一方志商户就是这家服务商的「团队账户」。</p></div></li>
      <li><b>2</b><div><strong>收款后发烟火</strong><p>线下收到钱，在详情里发烟火，烟火进团队账户。</p></div></li>
      <li><b>3</b><div><strong>服务商自己经营</strong><p>老板在服务商后台加员工、加商户、生成激活码。平台也可以替他加人。</p></div></li>
    </ol>

    <div v-if="success" class="mg-feedback success" role="status"><Check :size="16" />{{ success }}<button type="button" aria-label="关闭提示" @click="success = ''"><X :size="15" /></button></div>

    <section class="management-list" :aria-busy="loading" aria-label="服务商列表">
      <div v-if="forbidden" class="mg-state mg-error" role="alert"><Store :size="30" /><h3>没有服务商管理权限</h3><p>当前账号未获服务商管理权限，请联系平台管理员授权。</p></div>
      <div v-else-if="error" class="mg-state mg-error" role="alert"><h3>暂时无法读取</h3><p>{{ error }}</p><button class="mg-button" type="button" @click="load">重新读取</button></div>
      <div v-else-if="!partners.length && !loading" class="mg-state"><Store :size="32" /><h3>还没有服务商</h3><p>点右上角「新建服务商」。</p></div>
      <div v-else class="management-table-wrap">
        <table class="management-table partner-table">
          <thead><tr><th>服务商</th><th>负责人</th><th>状态</th><th class="code-number">团队烟火</th><th class="code-number">成员</th><th class="code-number">名下商户</th><th class="mg-table-action">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in partners" :key="item.partner.id" @click="open(item.partner.id)">
              <td><span>{{ item.partner.name }}</span><small>{{ item.partner.contact || '未填联系方式' }}</small></td>
              <td><span>{{ personName(ownerOf(item)) }}</span><small>{{ ownerOf(item) ? 'UID ' + ownerOf(item).uid : '还没有负责人' }}</small></td>
              <td><span class="mg-status" :class="item.partner.status === 'ACTIVE' ? 'mg-active' : 'mg-suspended'"><i />{{ item.partner.status === 'ACTIVE' ? '启用中' : '已停用' }}</span></td>
              <td class="code-number mg-mono">{{ item.partner.creditsAvailable == null ? '—' : number(item.partner.creditsAvailable) }}</td>
              <td class="code-number mg-mono">{{ number(item.members.length) }}</td>
              <td class="code-number mg-mono">{{ number(item.tenantCount) }}</td>
              <td class="mg-table-action"><button class="mg-text-button" type="button" @click.stop="open(item.partner.id)">管理</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="!forbidden" class="management-list partner-assign" aria-labelledby="assign-title">
      <div class="mg-list-caption"><h2 id="assign-title">查询商户归属</h2></div>
      <div class="partner-section">
        <p class="mg-muted">商户归哪家服务商，以第一次添加或兑换激活码为准，服务商自己改不了。需要换归属或解除时，在这里按商户的 UID 查询后修改。</p>
        <form class="partner-row" @submit.prevent="lookup">
          <label class="partner-field">商户任一账号的 UID<input v-model="assignForm.uid" inputmode="numeric" maxlength="8" placeholder="8 位数字" /></label>
          <button class="mg-button" type="submit" :disabled="busy"><Search :size="15" />查询</button>
        </form>
        <div v-if="assignForm.found" class="partner-found">
          <p>「{{ assignForm.found.tenantName || '商户' }}」（{{ assignForm.found.userName || '账号' }}）现在{{ assignForm.found.partnerName ? `归「${assignForm.found.partnerName}」，方式：${tenantSources[assignForm.found.source] || assignForm.found.source}` : '不归任何服务商' }}。</p>
          <div class="partner-row">
            <label class="partner-field">改为<select v-model="assignForm.partnerId" @change="pending = null"><option value="">不归属任何服务商</option><option v-for="item in partners" :key="item.partner.id" :value="String(item.partner.id)">{{ item.partner.name }}</option></select></label>
            <button class="mg-button mg-primary" type="button" :disabled="busy || assignForm.partnerId === String(assignForm.found.partnerId || '')" @click="ask('reassign')">保存归属</button>
          </div>
          <div v-if="asking('reassign')" class="mg-confirm"><p>把「{{ assignForm.found.tenantName || '该商户' }}」{{ assignTarget ? `改归「${assignTarget.partner.name}」` : '解除归属' }}？之后原服务商看不到这个商户。</p><div><button class="mg-button" type="button" :disabled="busy" @click="pending = null">取消</button><button class="mg-button mg-primary" type="button" :disabled="busy" @click="reassign">确认修改</button></div></div>
        </div>
        <p v-if="assignForm.error" class="mg-inline-error" role="alert">{{ assignForm.error }}</p>
      </div>
    </section>

    <!-- 新建服务商 -->
    <Dialog :open="createForm.open" class="management-dialog" @close="!busy && (createForm.open = false)">
      <div class="mg-backdrop" aria-hidden="true" /><div class="mg-drawer-position">
        <DialogPanel class="mg-drawer management-page">
          <header class="mg-drawer-header"><div><span>平台开通</span><DialogTitle as="h2">新建服务商</DialogTitle></div><button class="mg-page-button" type="button" aria-label="关闭" @click="createForm.open = false"><X :size="20" /></button></header>
          <form class="partner-create" @submit.prevent="create">
            <div class="mg-drawer-body">
              <section class="mg-detail-section">
                <label class="partner-field">服务商名称<input v-model="createForm.name" maxlength="80" placeholder="例如：嘉兴某某科技" /><small>服务商后台左上角显示的公司名，以后可以改。</small></label>
                <label class="partner-field">联系方式（可选）<input v-model="createForm.contact" maxlength="120" placeholder="联系人、电话" /></label>
                <label class="partner-field">负责人（老板）的 UID<input v-model="createForm.adminUid" inputmode="numeric" maxlength="8" placeholder="8 位数字" /><small>让对方先注册一方志账号，在「套餐与权益」页能看到自己的 UID。他的商户会成为这家服务商的团队账户，平台发的烟火都进这里。</small></label>
                <p v-if="createForm.error" class="mg-inline-error" role="alert">{{ createForm.error }}</p>
              </section>
            </div>
            <footer class="mg-drawer-actions"><button class="mg-button" type="button" :disabled="busy" @click="createForm.open = false">取消</button><button class="mg-button mg-primary" type="submit" :disabled="busy">{{ busy ? '正在开通…' : '开通服务商' }}</button></footer>
          </form>
        </DialogPanel>
      </div>
    </Dialog>

    <!-- 服务商详情 -->
    <Dialog :open="Boolean(selected)" class="management-dialog" @close="close">
      <div class="mg-backdrop" aria-hidden="true" /><div class="mg-drawer-position">
        <DialogPanel class="mg-drawer management-page partner-drawer">
          <template v-if="selected">
            <header class="mg-drawer-header"><div><span>开通于 {{ managementDate(selected.partner.createdAt).slice(0, 10) }}</span><DialogTitle as="h2">{{ selected.partner.name }}</DialogTitle></div><button class="mg-page-button" type="button" :disabled="busy" aria-label="关闭详情" @click="close"><X :size="20" /></button></header>
            <DialogDescription class="mg-detail-description"><span class="mg-status" :class="selected.partner.status === 'ACTIVE' ? 'mg-active' : 'mg-suspended'"><i />{{ selected.partner.status === 'ACTIVE' ? '启用中' : '已停用' }}</span><span>{{ selected.partner.contact || '未填联系方式' }}</span></DialogDescription>
            <nav class="management-tabs partner-tabs" aria-label="详情分类">
              <button type="button" :class="{ active: tab === 'overview' }" @click="tab = 'overview'">概况</button>
              <button type="button" :class="{ active: tab === 'members' }" @click="tab = 'members'">账号与成员 {{ selected.members.length }}</button>
              <button type="button" :class="{ active: tab === 'tenants' }" @click="tab = 'tenants'">名下商户 {{ selected.tenantCount }}</button>
              <button type="button" :class="{ active: tab === 'ledger' }" @click="tab = 'ledger'">烟火记录</button>
            </nav>
            <div class="mg-drawer-body">
              <template v-if="tab === 'overview'">
                <section v-if="renameForm.open" class="mg-detail-section">
                  <h3>改名称</h3>
                  <form @submit.prevent="rename">
                    <label class="partner-field">服务商名称<input v-model="renameForm.name" maxlength="80" /><small>服务商后台里显示的公司名会同步改。</small></label>
                    <label class="partner-field">联系方式（可选）<input v-model="renameForm.contact" maxlength="120" /></label>
                    <p v-if="renameForm.error" class="mg-inline-error" role="alert">{{ renameForm.error }}</p>
                    <div class="partner-buttons"><button class="mg-button" type="button" :disabled="busy" @click="renameForm.open = false">取消</button><button class="mg-button mg-primary" type="submit" :disabled="busy">保存</button></div>
                  </form>
                </section>
                <section class="mg-detail-section">
                  <h3><Handshake :size="17" />基本情况</h3>
                  <dl class="mg-detail-grid">
                    <div><dt>负责人（老板）</dt><dd>{{ personName(ownerOf(selected)) }}{{ ownerOf(selected) ? ' · UID ' + ownerOf(selected).uid : '' }}</dd></div>
                    <div><dt>团队账户</dt><dd>{{ selected.teamTenantName || '—' }}<small class="partner-hint">负责人自己的一方志商户，烟火存在这里</small></dd></div>
                    <div><dt>团队烟火</dt><dd class="mg-mono">{{ selected.partner.creditsAvailable == null ? '—' : number(selected.partner.creditsAvailable) }}</dd></div>
                    <div><dt>成员 / 名下商户</dt><dd>{{ selected.members.length }} 人 / {{ selected.tenantCount }} 家</dd></div>
                  </dl>
                </section>
                <section class="mg-detail-section">
                  <h3><Flame :size="17" />发放烟火</h3>
                  <p class="mg-muted partner-lead">线下收到服务商的款项后，在这里把烟火发进团队账户（记为充值烟火）。</p>
                  <div class="partner-row">
                    <label class="partner-field">烟火数<input v-model="grantForm.credits" type="number" min="1" max="10000000" step="1" @input="pending = null" /></label>
                    <label class="partner-field partner-grow">备注<input v-model="grantForm.note" maxlength="200" placeholder="收款流水号、金额" @input="pending = null" /></label>
                    <button class="mg-button mg-primary" type="button" :disabled="busy" @click="checkGrant">发放</button>
                  </div>
                  <div v-if="asking('grant')" class="mg-confirm"><p>给「{{ selected.partner.name }}」发 <b>{{ number(grantCredits) }}</b> 烟火，团队烟火 {{ number(selected.partner.creditsAvailable) }} → {{ number((selected.partner.creditsAvailable || 0) + grantCredits) }}。请确认已经收到款项。</p><div><button class="mg-button" type="button" :disabled="busy" @click="pending = null">取消</button><button class="mg-button mg-primary" type="button" :disabled="busy" @click="grant">确认发放</button></div></div>
                  <p v-if="grantForm.error" class="mg-inline-error" role="alert">{{ grantForm.error }}</p>
                </section>
              </template>

              <template v-else-if="tab === 'members'">
                <section class="mg-detail-section">
                  <p class="mg-muted partner-lead"><b>负责人</b>是服务商的老板，有全部权限。<b>成员</b>是团队账户的员工，按勾选的权限使用服务商后台，在一方志创作也扣团队烟火。</p>
                  <ul class="partner-people">
                    <li v-for="member in selected.members" :key="member.userId">
                      <div class="partner-person">
                        <div><strong>{{ personName(member) }}</strong><span class="mg-status" :class="{ 'mg-active': member.role === 'OWNER' }">{{ partnerRoleNames[member.role] || member.role }}</span></div>
                        <small>UID {{ member.uid }} · 权限：{{ permissionText(member) }}<template v-if="member.monthlyCap"> · 每月最多分 {{ number(member.monthlyCap) }}</template></small>
                        <button class="mg-text-button" type="button" :disabled="busy" @click="ask('remove', member.userId)">移除</button>
                      </div>
                      <div v-if="asking('remove', member.userId)" class="mg-confirm"><p>{{ member.role === 'OWNER' ? `移除后「${personName(member)}」不能再进服务商后台，他自己的一方志账号不受影响。` : `移除后「${personName(member)}」移出团队，他在团队账户里的员工账号一并停用（不能再登录一方志，手机号释放）。` }}</p><div><button class="mg-button" type="button" :disabled="busy" @click="pending = null">取消</button><button class="mg-button mg-danger" type="button" :disabled="busy" @click="removeMember(member)">确认移除</button></div></div>
                    </li>
                  </ul>
                  <p v-if="actionError && pending?.kind === 'remove'" class="mg-inline-error" role="alert">{{ actionError }}</p>
                  <div class="partner-buttons partner-start"><button class="mg-button" :class="{ 'mg-primary': adding === 'member' }" type="button" @click="adding = adding === 'member' ? '' : 'member'"><UserPlus :size="15" />添加成员</button><button class="mg-button" :class="{ 'mg-primary': adding === 'owner' }" type="button" @click="adding = adding === 'owner' ? '' : 'owner'"><Users :size="15" />添加负责人</button></div>
                </section>

                <section v-if="adding === 'member'" class="mg-detail-section">
                  <h3>添加成员</h3>
                  <form @submit.prevent="addTeamMember">
                    <div class="partner-choice" role="radiogroup" aria-label="添加方式">
                      <label :class="{ active: teamForm.by === 'phone' }"><input v-model="teamForm.by" type="radio" value="phone" /><span><strong>按手机号</strong><small>没注册过的号，会在团队账户里新建一个员工账号</small></span></label>
                      <label :class="{ active: teamForm.by === 'uid' }"><input v-model="teamForm.by" type="radio" value="uid" /><span><strong>按 UID</strong><small>已经是团队账户员工的账号（例如在员工管理里加过的店员）</small></span></label>
                    </div>
                    <div v-if="teamForm.by === 'phone'" class="partner-row">
                      <label class="partner-field partner-grow">手机号<input v-model="teamForm.phone" inputmode="tel" maxlength="11" /></label>
                      <label class="partner-field partner-grow">姓名<input v-model="teamForm.name" maxlength="20" /></label>
                    </div>
                    <label v-else class="partner-field">UID<input v-model="teamForm.uid" inputmode="numeric" maxlength="8" placeholder="8 位数字" /></label>
                    <fieldset class="partner-perms"><legend>他能做什么</legend><label v-for="item in permissionItems" :key="item.key"><input v-model="teamForm.permissions[item.key]" type="checkbox" /><span><strong>{{ item.label }}</strong><small>{{ item.hint }}</small></span></label></fieldset>
                    <label v-if="teamForm.permissions.distribute" class="partner-field">每月最多分出多少烟火（可选）<input v-model="teamForm.monthlyCap" type="number" min="1" max="100000000" step="1" placeholder="不填表示不限" /></label>
                    <p v-if="teamForm.error" class="mg-inline-error" role="alert">{{ teamForm.error }}</p>
                    <div class="partner-buttons"><button class="mg-button" type="button" :disabled="busy" @click="adding = ''">取消</button><button class="mg-button mg-primary" type="submit" :disabled="busy">添加成员</button></div>
                  </form>
                </section>

                <section v-if="adding === 'owner'" class="mg-detail-section">
                  <h3>添加负责人</h3>
                  <form @submit.prevent="addOwner">
                    <label class="partner-field">负责人的 UID<input v-model="memberForm.uid" inputmode="numeric" maxlength="8" placeholder="8 位数字" /><small>负责人有全部权限。这个账号已是本服务商成员的话，会升为负责人。</small></label>
                    <p v-if="memberForm.error" class="mg-inline-error" role="alert">{{ memberForm.error }}</p>
                    <div class="partner-buttons"><button class="mg-button" type="button" :disabled="busy" @click="adding = ''">取消</button><button class="mg-button mg-primary" type="submit" :disabled="busy">添加负责人</button></div>
                  </form>
                </section>
              </template>

              <section v-else-if="tab === 'tenants'" class="mg-detail-section">
                <p class="mg-muted partner-lead">服务商按 UID 加的、或兑换了他家激活码的商户。要换归属，用列表页下方的「查询商户归属」。</p>
                <p v-if="detailError" class="mg-inline-error" role="alert">{{ detailError }}</p>
                <p v-else-if="!tenants.length" class="mg-muted">还没有名下商户。</p>
                <div v-else class="management-table-wrap">
                  <table class="management-table">
                    <thead><tr><th>商户</th><th>会员</th><th class="code-number">烟火余额</th><th>怎么来的</th></tr></thead>
                    <tbody><tr v-for="item in tenants" :key="item.tenantId"><td><span>{{ item.tenantName || '—' }}</span><small>{{ item.ownerName || '—' }} · UID {{ item.ownerUid || '—' }}</small></td><td>{{ membership(item) }}</td><td class="code-number mg-mono">{{ item.creditsAvailable == null ? '—' : number(item.creditsAvailable) }}</td><td><span>{{ tenantSources[item.source] || item.source }}</span><small>{{ item.addedByName ? item.addedByName + ' 添加 · ' : '' }}{{ managementDate(item.boundAt).slice(0, 10) }}</small></td></tr></tbody>
                  </table>
                </div>
                <footer v-if="pageCount(tenantPaging) > 1" class="management-pagination"><span>共 {{ number(tenantPaging.total) }} 个商户</span><div><button class="mg-page-button" type="button" :disabled="tenantPaging.page === 0" aria-label="上一页" @click="turn(tenantPaging, tenantPaging.page - 1)"><ChevronLeft :size="16" /></button><span>{{ tenantPaging.page + 1 }} / {{ pageCount(tenantPaging) }}</span><button class="mg-page-button" type="button" :disabled="tenantPaging.page + 1 >= pageCount(tenantPaging)" aria-label="下一页" @click="turn(tenantPaging, tenantPaging.page + 1)"><ChevronRight :size="16" /></button></div></footer>
              </section>

              <section v-else class="mg-detail-section">
                <p class="mg-muted partner-lead">平台给这家服务商发烟火的记录。</p>
                <p v-if="detailError" class="mg-inline-error" role="alert">{{ detailError }}</p>
                <p v-else-if="!ledger.length" class="mg-muted">还没有发过烟火。</p>
                <div v-else class="management-table-wrap">
                  <table class="management-table">
                    <thead><tr><th>时间</th><th>类型</th><th class="code-number">烟火</th><th>备注</th></tr></thead>
                    <tbody><tr v-for="item in ledger" :key="item.id"><td class="mg-time">{{ managementDate(item.createdAt) }}</td><td><span>{{ ledgerTypes[item.type] || item.type }}</span><small v-if="item.tenantName">{{ item.tenantName }}</small></td><td class="code-number mg-mono">{{ item.delta > 0 ? '+' : '' }}{{ number(item.delta) }}</td><td>{{ item.note || '—' }}</td></tr></tbody>
                  </table>
                </div>
                <footer v-if="pageCount(ledgerPaging) > 1" class="management-pagination"><span>共 {{ number(ledgerPaging.total) }} 条</span><div><button class="mg-page-button" type="button" :disabled="ledgerPaging.page === 0" aria-label="上一页" @click="turn(ledgerPaging, ledgerPaging.page - 1)"><ChevronLeft :size="16" /></button><span>{{ ledgerPaging.page + 1 }} / {{ pageCount(ledgerPaging) }}</span><button class="mg-page-button" type="button" :disabled="ledgerPaging.page + 1 >= pageCount(ledgerPaging)" aria-label="下一页" @click="turn(ledgerPaging, ledgerPaging.page + 1)"><ChevronRight :size="16" /></button></div></footer>
              </section>
            </div>

            <footer class="mg-drawer-actions partner-footer">
              <div v-if="asking('status')" class="mg-confirm"><p>停用后这家服务商的人都进不了服务商后台，不能分烟火、生成激活码；已生成的激活码照常能用，随时可以再启用。</p><div><button class="mg-button" type="button" :disabled="busy" @click="pending = null">取消</button><button class="mg-button mg-danger" type="button" :disabled="busy" @click="setStatus('DISABLED')">确认停用</button></div></div>
              <div v-if="asking('delete')" class="mg-confirm partner-danger-box">
                <p><b>删除「{{ selected.partner.name }}」后不能恢复：</b></p>
                <ul><li>{{ selected.members.length }} 个账号移出，不能再进服务商后台</li><li>{{ selected.tenantCount }} 个名下商户解除归属，别家可以再加</li><li>没兑换的激活码全部作废，已兑换的不受影响</li><li>团队账户「{{ selected.teamTenantName || '—' }}」的烟火不动，记录都保留</li></ul>
                <div><button class="mg-button" type="button" :disabled="busy" @click="pending = null">取消</button><button class="mg-button mg-danger" type="button" :disabled="busy" @click="removePartner">确认删除</button></div>
              </div>
              <p v-if="actionError && ['status', 'delete'].includes(pending?.kind)" class="mg-inline-error" role="alert">{{ actionError }}</p>
              <div class="partner-footer-buttons">
                <button class="mg-button mg-danger" type="button" :disabled="busy" @click="ask('delete')"><Trash2 :size="15" />删除</button>
                <span />
                <button class="mg-button" type="button" :disabled="busy" @click="startRename">改名称</button>
                <button v-if="selected.partner.status === 'ACTIVE'" class="mg-button" type="button" :disabled="busy" @click="ask('status')">停用</button>
                <button v-else class="mg-button mg-primary" type="button" :disabled="busy" @click="setStatus('ACTIVE')">启用</button>
              </div>
            </footer>
          </template>
        </DialogPanel>
      </div>
    </Dialog>
  </div>
</template>

<style scoped>
.partner-steps { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0; margin: 0 0 18px; padding: 0; list-style: none; border: 1px solid var(--mg-line); border-radius: 6px; background: #fff; }
.partner-steps li { display: flex; gap: 12px; padding: 16px 18px; }
.partner-steps li + li { border-left: 1px solid var(--mg-line); }
.partner-steps b { display: grid; place-items: center; flex-shrink: 0; width: 24px; height: 24px; border-radius: 50%; background: #e3f2eb; color: var(--mg-accent); font-size: 12px; }
.partner-steps strong { display: block; font-size: 13px; font-weight: 600; }
.partner-steps p { margin: 4px 0 0; color: var(--mg-muted); font-size: 12px; line-height: 1.7; }
.partner-table tbody tr { cursor: pointer; }
.partner-table tbody tr:hover td { background: var(--mg-soft); }
.code-number { text-align: right; }
.management-table td > small { display: block; margin-top: 2px; color: var(--mg-muted); font-size: 11px; }
.partner-section { display: grid; gap: 12px; padding: 18px 22px; }
.partner-section > p { margin: 0; font-size: 12px; }
.partner-found { display: grid; gap: 12px; padding: 14px; border-radius: 5px; background: var(--mg-soft); }
.partner-found > p { margin: 0; font-size: 13px; }
.partner-row { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 10px; }
.partner-field { display: grid; gap: 6px; margin-bottom: 14px; color: var(--mg-muted); font-size: 12px; }
.partner-row .partner-field { margin-bottom: 0; min-width: 160px; }
.partner-grow { flex: 1 1 200px; }
.partner-field :is(input, select) { min-height: 36px; padding: 6px 10px; border: 1px solid var(--mg-line); border-radius: 5px; background: #fff; color: var(--mg-ink); font-size: 13px; }
.partner-field :is(input, select):focus { border-color: var(--mg-accent); outline: 0; }
.partner-field small, .partner-hint { display: block; color: var(--mg-muted); font-size: 11px; line-height: 1.7; }
.partner-hint { margin-top: 2px; }
.partner-lead { margin: 0 0 16px; font-size: 12px; line-height: 1.8; }
.partner-lead b { color: var(--mg-ink); font-weight: 600; }
.partner-buttons { display: flex; justify-content: flex-end; gap: 8px; }
.partner-start { justify-content: flex-start; margin-top: 16px; }
.partner-drawer { width: min(760px, 100%); }
.partner-tabs { padding: 0 28px; }
.partner-tabs > button { min-height: 46px; }
.partner-people { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; border: 1px solid var(--mg-line); border-radius: 6px; }
.partner-people li { padding: 12px 14px; }
.partner-people li + li { border-top: 1px solid var(--mg-line); }
.partner-person { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 2px 12px; align-items: center; }
.partner-person > div { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.partner-person > small { grid-column: 1; color: var(--mg-muted); font-size: 12px; }
.partner-person > button { grid-row: 1 / span 2; grid-column: 2; color: var(--mg-alert); }
.partner-choice { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 14px; }
.partner-choice label, .partner-perms label { display: flex; align-items: flex-start; gap: 8px; padding: 10px 12px; border: 1px solid var(--mg-line); border-radius: 5px; cursor: pointer; }
.partner-choice label.active { border-color: var(--mg-accent); background: #f1f8f4; }
.partner-choice input, .partner-perms input { margin-top: 3px; accent-color: var(--mg-accent); }
.partner-choice span, .partner-perms span { display: grid; gap: 2px; }
.partner-choice strong, .partner-perms strong { font-size: 13px; font-weight: 500; }
.partner-choice small, .partner-perms small { color: var(--mg-muted); font-size: 11px; line-height: 1.6; }
.partner-perms { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin: 0 0 14px; padding: 0; border: 0; }
.partner-perms legend { margin-bottom: 8px; padding: 0; color: var(--mg-muted); font-size: 12px; }
.partner-perms label:has(input:checked) { border-color: var(--mg-accent); background: #f1f8f4; }
.partner-footer { flex-direction: column; align-items: stretch; }
.partner-footer .mg-confirm { margin: 0; }
.partner-footer-buttons { display: flex; align-items: center; gap: 8px; }
.partner-footer-buttons > span { flex: 1; }
.partner-danger-box { display: grid; grid-template-columns: minmax(0, 1fr); border-color: #f0cfcd; background: #fdf4f3; }
.partner-danger-box ul { margin: 0; padding-left: 18px; font-size: 12px; line-height: 1.9; }
.partner-danger-box > div { justify-self: end; }
.partner-assign { margin-top: 18px; }
.mg-detail-section form .partner-row { margin-bottom: 14px; }
@media (max-width: 760px) {
  .partner-steps { grid-template-columns: minmax(0, 1fr); }
  .partner-steps li + li { border-left: 0; border-top: 1px solid var(--mg-line); }
  .partner-tabs { gap: 16px; padding: 0 20px; overflow-x: auto; overflow-y: hidden; scrollbar-width: none; }
  .partner-tabs > button { flex-shrink: 0; }
  .partner-choice, .partner-perms { grid-template-columns: minmax(0, 1fr); }
}
</style>
