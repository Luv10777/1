<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { teamApi } from '../services/teamApi'
import { stores, loadStores } from '../stores/merchantContext'

const members = ref([])
const loading = ref(true)
const notice = ref('')
const busyId = ref(null)
const showModal = ref(false)
const editing = ref(null)
const saving = ref(false)
const formError = ref('')
const form = reactive({ phone: '', name: '', storeIds: [] })

const storeNames = computed(() => new Map(stores.value.map(store => [store.id, store.name])))
const staffCount = computed(() => members.value.filter(member => member.role === 'STAFF').length)

onMounted(async () => {
  document.querySelector('.page-scroll')?.classList.add('team-scroll')
  // 门店列表在登录时已取过；这里再取一次，保证刚建的门店能出现在勾选项里。
  await Promise.all([load(), loadStores()])
})
onBeforeUnmount(() => document.querySelector('.page-scroll')?.classList.remove('team-scroll'))

async function load() {
  loading.value = true
  try { members.value = await teamApi.list() }
  catch (error) { notice.value = error.message || '成员加载失败，请重试' }
  finally { loading.value = false }
}

function scopeOf(member) {
  if (member.allStores) return '全部门店'
  const names = member.storeIds.map(id => storeNames.value.get(id)).filter(Boolean)
  return names.length ? names.join('、') : '尚未分配门店'
}
const lastLogin = member => (member.lastLoginAt ? new Date(member.lastLoginAt).toLocaleDateString('zh-CN') : '尚未登录')

function openCreate() {
  editing.value = null
  Object.assign(form, { phone: '', name: '', storeIds: [] })
  formError.value = ''
  showModal.value = true
}
function openEdit(member) {
  editing.value = member
  Object.assign(form, { phone: member.phone || '', name: member.name || '', storeIds: [...member.storeIds] })
  formError.value = ''
  showModal.value = true
}
function closeModal() { if (!saving.value) showModal.value = false }

async function submit() {
  if (saving.value) return
  formError.value = ''
  saving.value = true
  try {
    if (editing.value) await teamApi.update(editing.value.id, form)
    else await teamApi.add(form)
    showModal.value = false
    notice.value = ''
    await load()
  } catch (error) { formError.value = error.message || '保存失败，请重试' }
  finally { saving.value = false }
}

// 停用、启用、移除都只影响一行；做完重新取列表，以服务端为准。
async function act(member, action, question) {
  if (busyId.value || (question && !confirm(question))) return
  busyId.value = member.id
  notice.value = ''
  try { await action(member.id); await load() }
  catch (error) { notice.value = error.message || '操作失败，请重试' }
  finally { busyId.value = null }
}
const disable = member => act(member, teamApi.disable, `停用“${member.name}”？他会立即退出登录，停用期间无法再登录。`)
const enable = member => act(member, teamApi.enable)
const remove = member => act(member, teamApi.remove, `移除“${member.name}”？他将无法再进入本商户，手机号会被释放；他建过的商品、直播等资料会保留。`)
</script>

<template>
  <div class="team-page">
    <header class="team-header">
      <div>
        <p class="eyebrow">TEAM / 01</p>
        <h1>员工管理</h1>
        <p class="header-desc">添加店员并分配门店。店员用登记的手机号和验证码登录，只能进入分配给他的门店。</p>
      </div>
      <button class="primary-button" @click="openCreate"><span>＋</span> 添加店员</button>
    </header>

    <p v-if="notice" class="page-notice" role="alert">{{ notice }}</p>
    <div v-if="loading" class="loading-state">正在加载成员…</div>
    <template v-else>
      <section class="member-list" aria-label="成员列表">
        <div class="member-row member-head" aria-hidden="true">
          <span>成员</span><span>角色</span><span>可进入的门店</span><span>状态</span><span>最近登录</span><span />
        </div>
        <article v-for="member in members" :key="member.id" class="member-row" :class="{ disabled: member.status === 'DISABLED' }">
          <div class="member-who">
            <span class="member-avatar" aria-hidden="true">{{ (member.name || '员').slice(0, 1) }}</span>
            <div><strong>{{ member.name || '未填写姓名' }}<em v-if="member.self">当前账号</em></strong><small>{{ member.phone || '未绑定手机号' }}</small></div>
          </div>
          <span class="role-badge" :class="{ owner: member.role === 'OWNER' }">{{ member.role === 'OWNER' ? '老板' : '店员' }}</span>
          <span class="member-scope" :class="{ empty: !member.allStores && !member.storeIds.length }">{{ scopeOf(member) }}</span>
          <span class="member-status" :class="{ off: member.status === 'DISABLED' }">{{ member.status === 'DISABLED' ? '已停用' : '正常' }}</span>
          <span class="member-login">{{ lastLogin(member) }}</span>
          <div v-if="member.role === 'STAFF'" class="member-actions">
            <button class="link-button" :disabled="busyId === member.id" @click="openEdit(member)">编辑</button>
            <button v-if="member.status === 'DISABLED'" class="link-button" :disabled="busyId === member.id" @click="enable(member)">启用</button>
            <button v-else class="link-button" :disabled="busyId === member.id" @click="disable(member)">停用</button>
            <button class="link-button danger" :disabled="busyId === member.id" @click="remove(member)">移除</button>
          </div>
          <span v-else />
        </article>
        <p v-if="!staffCount" class="member-empty">还没有店员。点击右上角“添加店员”，输入对方的手机号即可。</p>
      </section>
      <ul class="team-notes">
        <li>老板能进入全部门店，并管理品牌、门店、员工和声音样本；店员只能在分配给他的门店里管理商品、知识库和直播。</li>
        <li>一个手机号目前只能属于一个商户：已经注册过的手机号无法添加。</li>
        <li>移除店员后，他的手机号会被释放，可以重新注册或被其他商户添加。</li>
      </ul>
    </template>

    <Transition name="team-dialog" appear>
      <div v-if="showModal" class="modal-overlay" @click.self="closeModal">
        <section class="team-modal" role="dialog" aria-modal="true" :aria-label="editing ? '编辑店员' : '添加店员'">
          <header class="modal-header">
            <h2>{{ editing ? '编辑店员' : '添加店员' }}</h2>
            <button class="close-button" aria-label="关闭" :disabled="saving" @click="closeModal">×</button>
          </header>
          <form class="team-form" @submit.prevent="submit">
            <label class="field">
              <span>手机号 <b v-if="!editing">*</b></span>
              <input v-model="form.phone" type="tel" inputmode="numeric" maxlength="11" placeholder="店员本人用于登录的手机号" :disabled="saving || !!editing" />
              <small>{{ editing ? '手机号不能修改；换号请移除后重新添加。' : '对方用这个手机号获取验证码登录，无需另设密码。' }}</small>
            </label>
            <label class="field">
              <span>姓名 <b>*</b></span>
              <input v-model="form.name" maxlength="80" placeholder="例如：小李" :disabled="saving" />
            </label>
            <fieldset class="field store-field">
              <legend>可进入的门店</legend>
              <label v-for="store in stores" :key="store.id" class="store-option">
                <input v-model="form.storeIds" type="checkbox" :value="store.id" :disabled="saving" />
                <span>{{ store.name }}</span>
              </label>
              <small v-if="!stores.length">还没有门店。可以先添加店员，建店后再回来分配。</small>
              <small v-else-if="!form.storeIds.length">一家都不选时，他登录后看不到任何门店。</small>
            </fieldset>
            <div class="form-footer">
              <p v-if="formError" class="form-notice" role="alert">{{ formError }}</p>
              <div>
                <button type="button" class="ghost-button" :disabled="saving" @click="closeModal">取消</button>
                <button type="submit" class="primary-button" :disabled="saving">{{ saving ? '保存中…' : editing ? '保存修改' : '添加店员' }}</button>
              </div>
            </div>
          </form>
        </section>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
:global(.page-scroll.team-scroll) { background: var(--color-bg-canvas); color: var(--color-primary); }
.team-page { max-width: 1200px; margin: 0 auto; padding: 24px 32px 72px; font-family: var(--font-sans); color: var(--color-primary); }
.team-header { display: flex; justify-content: space-between; align-items: flex-end; gap: 20px; margin-bottom: 28px; }
.eyebrow { margin: 0; color: var(--color-text-muted); font-size: 10px; font-weight: 700; letter-spacing: .14em; }
.team-header h1 { margin: 8px 0 6px; font-family: var(--font-serif); font-size: 36px; font-weight: 750; line-height: 1.18; }
.header-desc { margin: 0; color: var(--color-text-muted); font-size: 13px; line-height: 1.75; }
.primary-button, .ghost-button, .link-button { font-family: var(--font-sans); font-size: 13px; font-weight: 700; cursor: pointer; }
.primary-button { flex: none; border: 1px solid var(--color-accent); border-radius: 4px; padding: 11px 17px; color: var(--color-on-accent); background: var(--color-accent); }
.primary-button:hover { background: var(--color-accent-hover); }
.primary-button:disabled, .ghost-button:disabled, .link-button:disabled { opacity: .55; cursor: default; }
.primary-button span { margin-right: 3px; font-size: 16px; }
.ghost-button { border: 1px solid var(--color-border-subtle); border-radius: 4px; padding: 11px 17px; color: var(--color-primary); background: transparent; }
.link-button { padding: 5px 8px; border: 0; background: transparent; color: var(--color-accent-text); }
.link-button.danger { color: var(--color-error); }
.page-notice { margin: 0 0 16px; padding: 10px 14px; border: 1px solid var(--color-border-subtle); border-radius: 6px; color: var(--color-error); background: var(--color-bg-surface); font-size: 13px; }
.loading-state { padding: 60px 0; color: var(--color-text-muted); text-align: center; font-size: 13px; }

.member-list { border: 1px solid var(--color-border-subtle); border-radius: 8px; background: var(--color-bg-surface); overflow: hidden; }
.member-row { display: grid; grid-template-columns: minmax(180px, 1.5fr) 70px minmax(140px, 1.6fr) 70px 100px 170px; align-items: center; gap: 14px; padding: 15px 20px; border-top: 1px solid var(--color-border-subtle); font-size: 13px; }
.member-head { border-top: 0; padding-block: 11px; color: var(--color-text-muted); font-size: 11px; font-weight: 700; background: var(--color-bg-subtle); }
.member-row.disabled .member-who, .member-row.disabled .member-scope { opacity: .55; }
.member-who { display: flex; align-items: center; gap: 12px; min-width: 0; }
.member-avatar { display: grid; place-items: center; flex: none; width: 36px; height: 36px; border-radius: 8px; color: var(--color-on-accent); background: var(--color-accent); font-weight: 700; }
.member-who strong { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.member-who em { padding: 2px 6px; border-radius: 4px; color: var(--color-text-muted); background: var(--color-bg-subtle); font-size: 10px; font-style: normal; font-weight: 600; }
.member-who small { display: block; margin-top: 2px; color: var(--color-text-muted); font-size: 12px; }
.role-badge { justify-self: start; padding: 3px 8px; border-radius: 4px; color: var(--color-text-muted); background: var(--color-bg-subtle); font-size: 11px; font-weight: 700; }
.role-badge.owner { color: var(--color-accent-text); background: color-mix(in srgb, var(--color-accent-text) 8%, var(--color-bg-surface)); }
.member-scope { line-height: 1.6; }
.member-scope.empty { color: var(--color-text-muted); }
.member-status { color: var(--color-success); font-size: 12px; font-weight: 700; }
.member-status.off { color: var(--color-text-muted); }
.member-login { color: var(--color-text-muted); font-size: 12px; }
.member-actions { display: flex; justify-content: flex-end; }
.member-empty { margin: 0; padding: 26px 20px; border-top: 1px solid var(--color-border-subtle); color: var(--color-text-muted); font-size: 13px; text-align: center; }
.team-notes { margin: 18px 0 0; padding-left: 18px; color: var(--color-text-muted); font-size: 12px; line-height: 1.9; }

.modal-overlay { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; padding: 20px; background: rgba(24, 42, 42, .42); backdrop-filter: blur(5px); }
.team-modal { width: min(520px, 100%); max-height: 92vh; overflow: auto; border: 1px solid var(--color-border-subtle); border-radius: 8px; background: var(--color-bg-surface); box-shadow: var(--shadow-paper); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 22px 26px 18px; border-bottom: 1px solid var(--color-border-subtle); }
.modal-header h2 { margin: 0; font-family: var(--font-serif); font-size: 22px; font-weight: 750; }
.close-button { width: 34px; height: 34px; border: 0; border-radius: 6px; color: var(--color-text-muted); background: transparent; font-size: 22px; cursor: pointer; }
.close-button:hover { background: var(--color-bg-subtle); }
.team-form { padding: 22px 26px 24px; }
.field { display: grid; gap: 7px; margin: 0 0 18px; padding: 0; border: 0; }
.field > span, .field > legend { padding: 0; color: var(--color-primary); font-size: 12px; font-weight: 700; }
.field b { color: var(--color-text-muted); }
.field input:not([type='checkbox']) { width: 100%; height: 40px; box-sizing: border-box; padding: 10px 11px; border: 1px solid var(--color-border-subtle); border-radius: 4px; outline: 0; color: var(--color-primary); background: var(--color-bg-surface); font: 500 13px/1.5 inherit; }
.field input:not([type='checkbox']):focus { border-color: var(--color-accent); }
.field input:disabled { color: var(--color-text-muted); background: var(--color-bg-subtle); }
.field small { color: var(--color-text-muted); font-size: 11px; line-height: 1.6; }
.store-field legend { margin-bottom: 8px; }
.store-option { display: flex; align-items: center; gap: 9px; padding: 8px 10px; border: 1px solid var(--color-border-subtle); border-radius: 4px; font-size: 13px; cursor: pointer; }
.store-option input { margin: 0; }
.form-footer { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding-top: 4px; }
.form-footer > div { display: flex; gap: 9px; margin-left: auto; }
.form-notice { margin: 0; color: var(--color-error); font-size: 12px; line-height: 1.6; }

.team-dialog-enter-active, .team-dialog-leave-active { transition: opacity .22s ease; }
.team-dialog-enter-from, .team-dialog-leave-to { opacity: 0; }

@media (max-width: 900px) {
  .team-page { padding: 22px 20px 58px; }
  .team-header { display: block; }
  .team-header .primary-button { margin-top: 16px; }
  .member-head { display: none; }
  .member-row { grid-template-columns: 1fr auto; row-gap: 8px; }
  .member-scope, .member-login { grid-column: 1 / -1; }
  .member-actions { grid-column: 1 / -1; justify-content: flex-start; }
}
</style>
