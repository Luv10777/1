<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { accountApi } from '../services/accountApi'
import { auth } from '../stores/auth'

const profile = ref(null)
const loading = ref(true)
const loadError = ref('')

const nameDraft = ref('')
const savingName = ref(false)
const nameMessage = ref({ text: '', error: false })

const passwordOpen = ref(false)
const byCode = ref(false)
const savingPassword = ref(false)
const sendingCode = ref(false)
const passwordError = ref('')
const passwordNotice = ref('')
const passwordDone = ref('')
const passwordForm = reactive({ currentPassword: '', code: '', newPassword: '', confirmPassword: '' })

const roleText = computed(() => (profile.value?.role === 'STAFF' ? '店员' : '管理员'))
const joinedAt = computed(() => (profile.value?.createdAt ? new Date(profile.value.createdAt).toLocaleDateString('zh-CN') : ''))
const nameChanged = computed(() => nameDraft.value.trim() && nameDraft.value.trim() !== profile.value?.name)
// 没设过密码的人只能用验证码；没绑手机号的账号只能用当前密码。
const codeOnly = computed(() => !profile.value?.passwordSet)
const canUseCode = computed(() => Boolean(profile.value?.phone))
const maskedPhone = computed(() => profile.value?.phone?.replace(/^(\d{3})\d{4}(\d{4})$/, '$1 **** $2') || '')

onMounted(async () => {
  document.querySelector('.page-scroll')?.classList.add('settings-scroll')
  await load()
})
onBeforeUnmount(() => document.querySelector('.page-scroll')?.classList.remove('settings-scroll'))

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    profile.value = await accountApi.profile()
    nameDraft.value = profile.value.name || ''
  } catch (error) {
    loadError.value = error.message || '账号资料加载失败，请重试'
  } finally {
    loading.value = false
  }
}

async function saveName() {
  if (savingName.value || !nameChanged.value) return
  savingName.value = true
  nameMessage.value = { text: '', error: false }
  try {
    profile.value = await accountApi.rename(nameDraft.value)
    nameDraft.value = profile.value.name
    auth.rename(profile.value.name)
    nameMessage.value = { text: '已保存', error: false }
  } catch (error) {
    nameMessage.value = { text: error.message || '保存失败，请重试', error: true }
  } finally {
    savingName.value = false
  }
}

function openPassword() {
  Object.assign(passwordForm, { currentPassword: '', code: '', newPassword: '', confirmPassword: '' })
  byCode.value = codeOnly.value
  passwordError.value = ''
  passwordNotice.value = ''
  passwordDone.value = ''
  passwordOpen.value = true
}
function closePassword() { if (!savingPassword.value) passwordOpen.value = false }
function switchProof(useCode) {
  byCode.value = useCode
  passwordError.value = ''
  passwordNotice.value = ''
}

async function sendCode() {
  if (sendingCode.value || auth.state.cooldown > 0) return
  passwordError.value = ''
  sendingCode.value = true
  try {
    const result = await auth.sendCode(profile.value.phone)
    passwordNotice.value = result?.developmentMode
      ? '当前为本地调试模式，不会发送短信。验证码请在后端控制台查看。'
      : `验证码已发送到 ${maskedPhone.value}，5 分钟内有效。`
  } catch (error) {
    passwordError.value = error.message || '验证码发送失败，请稍后重试'
  } finally {
    sendingCode.value = false
  }
}

async function savePassword() {
  if (savingPassword.value) return
  passwordError.value = ''
  savingPassword.value = true
  try {
    const session = await accountApi.changePassword(passwordForm, byCode.value)
    auth.replaceSession(session)
    const first = !profile.value.passwordSet
    profile.value = { ...profile.value, passwordSet: true }
    passwordOpen.value = false
    passwordDone.value = `${first ? '密码已设置，以后也可以用手机号加密码登录。' : '密码已修改。'}其他电脑、手机和桌面端上的登录已退出，需要重新登录。`
  } catch (error) {
    passwordError.value = error.message || '保存失败，请重试'
  } finally {
    savingPassword.value = false
  }
}
</script>

<template>
  <div class="settings-page">
    <header class="settings-header">
      <p class="eyebrow">SYSTEM / SETTINGS</p>
      <h1>系统设置</h1>
      <p class="header-desc">查看和修改你自己的账号资料与登录密码。只影响当前登录的这个账号。</p>
    </header>

    <div v-if="loading" class="loading-state">正在加载账号资料…</div>
    <div v-else-if="loadError" class="page-notice" role="alert">
      {{ loadError }} <button class="link-button" @click="load">重试</button>
    </div>
    <template v-else-if="profile">
      <section class="settings-card" aria-labelledby="profile-title">
        <header class="card-header">
          <div>
            <h2 id="profile-title">个人资料</h2>
            <p>名字会显示在右上角，以及管理员的员工列表里。</p>
          </div>
          <span class="role-badge" :class="{ owner: profile.role !== 'STAFF' }">{{ roleText }}</span>
        </header>
        <form class="name-row" @submit.prevent="saveName">
          <label class="field">
            <span>姓名</span>
            <input v-model="nameDraft" maxlength="80" placeholder="例如：王老板" :disabled="savingName" @input="nameMessage.text = ''" />
          </label>
          <button type="submit" class="primary-button" :disabled="savingName || !nameChanged">{{ savingName ? '保存中…' : '保存' }}</button>
        </form>
        <p v-if="nameMessage.text" class="inline-message" :class="{ error: nameMessage.error }" role="status">{{ nameMessage.text }}</p>
        <dl class="facts">
          <div><dt>手机号</dt><dd>{{ profile.phone || '未绑定' }}</dd></div>
          <div v-if="profile.username"><dt>登录账号</dt><dd>{{ profile.username }}</dd></div>
          <div><dt>角色</dt><dd>{{ roleText }}</dd></div>
          <div v-if="joinedAt"><dt>注册时间</dt><dd>{{ joinedAt }}</dd></div>
        </dl>
        <p class="card-foot">手机号和角色暂时不能在这里修改。{{ profile.role === 'STAFF' ? '需要调整请联系商户管理员。' : '' }}</p>
      </section>

      <section class="settings-card" aria-labelledby="password-title">
        <header class="card-header">
          <div>
            <h2 id="password-title">登录密码</h2>
            <p v-if="profile.passwordSet">已设置。登录时可以用{{ profile.phone ? '手机号' : '账号' }}加密码，不用每次收验证码。</p>
            <p v-else>还没有设置。目前只能用手机验证码登录；设置后也可以用手机号加密码登录。</p>
          </div>
          <span class="state-badge" :class="{ on: profile.passwordSet }">{{ profile.passwordSet ? '已设置' : '未设置' }}</span>
        </header>
        <p v-if="passwordDone" class="inline-message" role="status">{{ passwordDone }}</p>
        <button v-if="!passwordOpen" class="ghost-button" @click="openPassword">{{ profile.passwordSet ? '修改密码' : '设置密码' }}</button>

        <form v-else class="password-form" @submit.prevent="savePassword">
          <label v-if="!byCode" class="field">
            <span>当前密码</span>
            <input v-model="passwordForm.currentPassword" type="password" autocomplete="current-password" maxlength="64" :disabled="savingPassword" />
          </label>
          <div v-else class="field">
            <label for="settings-code">短信验证码</label>
            <div class="code-row">
              <input id="settings-code" v-model="passwordForm.code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="6 位验证码" :disabled="savingPassword" />
              <button type="button" class="ghost-button" :disabled="savingPassword || sendingCode || auth.state.cooldown > 0" @click="sendCode">
                {{ sendingCode ? '发送中…' : auth.state.cooldown > 0 ? `${auth.state.cooldown}s 后重试` : '获取验证码' }}
              </button>
            </div>
            <small>验证码会发到 {{ maskedPhone }}。</small>
          </div>
          <p v-if="!codeOnly && canUseCode" class="proof-switch">
            <button type="button" class="link-button" :disabled="savingPassword" @click="switchProof(!byCode)">
              {{ byCode ? '改用当前密码验证' : '忘记当前密码？改用短信验证码' }}
            </button>
          </p>
          <label class="field">
            <span>新密码</span>
            <input v-model="passwordForm.newPassword" type="password" autocomplete="new-password" maxlength="64" placeholder="8–64 位" :disabled="savingPassword" />
          </label>
          <label class="field">
            <span>再输一次新密码</span>
            <input v-model="passwordForm.confirmPassword" type="password" autocomplete="new-password" maxlength="64" :disabled="savingPassword" />
          </label>
          <p class="warning">保存后，这个账号在其他电脑、手机和桌面端上的登录都会退出。正在直播时，建议下播后再改。</p>
          <p v-if="passwordNotice" class="inline-message" role="status">{{ passwordNotice }}</p>
          <div class="form-footer">
            <p v-if="passwordError" class="form-notice" role="alert">{{ passwordError }}</p>
            <div>
              <button type="button" class="ghost-button" :disabled="savingPassword" @click="closePassword">取消</button>
              <button type="submit" class="primary-button" :disabled="savingPassword">{{ savingPassword ? '保存中…' : '保存密码' }}</button>
            </div>
          </div>
        </form>
      </section>
    </template>
  </div>
</template>

<style scoped>
:global(.page-scroll.settings-scroll) { background: var(--color-bg-canvas); color: var(--color-primary); }
.settings-page { max-width: 760px; margin: 0 auto; padding: 24px 32px 72px; font-family: var(--font-sans); color: var(--color-primary); }
.settings-header { margin-bottom: 28px; }
.eyebrow { margin: 0; color: var(--color-text-muted); font-size: 10px; font-weight: 700; letter-spacing: .14em; }
.settings-header h1 { margin: 8px 0 6px; font-family: var(--font-serif); font-size: 36px; font-weight: 750; line-height: 1.18; }
.header-desc { margin: 0; color: var(--color-text-muted); font-size: 13px; line-height: 1.75; }
.primary-button, .ghost-button, .link-button { font-family: var(--font-sans); font-size: 13px; font-weight: 700; cursor: pointer; }
.primary-button { flex: none; border: 1px solid var(--color-accent); border-radius: 4px; padding: 10px 17px; color: var(--color-on-accent); background: var(--color-accent); }
.primary-button:hover:not(:disabled) { background: var(--color-accent-hover); }
.ghost-button { flex: none; border: 1px solid var(--color-border-subtle); border-radius: 4px; padding: 10px 17px; color: var(--color-primary); background: transparent; }
.ghost-button:hover:not(:disabled) { background: var(--color-bg-subtle); }
.link-button { padding: 0; border: 0; background: transparent; color: var(--color-accent-text); }
.primary-button:disabled, .ghost-button:disabled, .link-button:disabled { opacity: .55; cursor: default; }
.page-notice { margin: 0 0 16px; padding: 10px 14px; border: 1px solid var(--color-border-subtle); border-radius: 6px; color: var(--color-error); background: var(--color-bg-surface); font-size: 13px; }
.loading-state { padding: 60px 0; color: var(--color-text-muted); text-align: center; font-size: 13px; }

.settings-card { margin-bottom: 20px; padding: 22px 24px; border: 1px solid var(--color-border-subtle); border-radius: 8px; background: var(--color-bg-surface); }
.card-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 18px; }
.card-header h2 { margin: 0 0 4px; font-family: var(--font-serif); font-size: 20px; font-weight: 750; }
.card-header p { margin: 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.7; }
.role-badge, .state-badge { flex: none; padding: 3px 8px; border-radius: 4px; color: var(--color-text-muted); background: var(--color-bg-subtle); font-size: 11px; font-weight: 700; }
.role-badge.owner { color: var(--color-accent-text); background: color-mix(in srgb, var(--color-accent-text) 8%, var(--color-bg-surface)); }
.state-badge.on { color: var(--color-success); background: color-mix(in srgb, var(--color-success) 10%, var(--color-bg-surface)); }

.name-row { display: flex; align-items: flex-end; gap: 10px; }
.name-row .field { flex: 1; margin: 0; }
.field { display: grid; gap: 7px; margin: 0 0 16px; }
.field > span, .field > label { color: var(--color-primary); font-size: 12px; font-weight: 700; }
.field input { width: 100%; height: 40px; box-sizing: border-box; padding: 10px 11px; border: 1px solid var(--color-border-subtle); border-radius: 4px; outline: 0; color: var(--color-primary); background: var(--color-bg-surface); font: 500 13px/1.5 inherit; }
.field input:focus { border-color: var(--color-accent); }
.field input:disabled { color: var(--color-text-muted); background: var(--color-bg-subtle); }
.field small { color: var(--color-text-muted); font-size: 11px; line-height: 1.6; }
.code-row { display: flex; gap: 10px; }
.code-row input { flex: 1; }
.inline-message { margin: 10px 0 0; color: var(--color-success); font-size: 12px; line-height: 1.7; }
.inline-message.error { color: var(--color-error); }
.settings-card > .inline-message + .ghost-button { margin-top: 14px; }

.facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 14px; margin: 20px 0 0; padding: 16px 0 0; border-top: 1px solid var(--color-border-subtle); }
.facts dt { color: var(--color-text-muted); font-size: 11px; font-weight: 700; }
.facts dd { margin: 4px 0 0; font-size: 14px; word-break: break-all; }
.card-foot { margin: 16px 0 0; color: var(--color-text-muted); font-size: 11px; line-height: 1.6; }

.password-form { max-width: 420px; }
.proof-switch { margin: -6px 0 16px; font-size: 12px; }
.warning { margin: 0 0 12px; padding: 10px 12px; border-radius: 4px; color: var(--color-warning); background: color-mix(in srgb, var(--color-warning) 8%, var(--color-bg-surface)); font-size: 12px; line-height: 1.7; }
.form-footer { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding-top: 4px; }
.form-footer > div { display: flex; gap: 9px; margin-left: auto; }
.form-notice { margin: 0; color: var(--color-error); font-size: 12px; line-height: 1.6; }

@media (max-width: 640px) {
  .settings-page { padding: 22px 16px 58px; }
  .settings-header h1 { font-size: 30px; }
  .settings-card { padding: 18px 16px; }
  .card-header { flex-direction: column-reverse; gap: 10px; }
  .form-footer { flex-direction: column; align-items: stretch; }
  .form-footer > div { margin-left: 0; justify-content: flex-end; }
}
</style>
