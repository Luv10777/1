<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ShieldCheck } from 'lucide-vue-next'
import { accountApi, validateAvatar } from '../services/accountApi'
import UserAvatar from '../components/UserAvatar.vue'
import { platformManagementApi as identityApi } from '../services/platformManagement'
import { identityValidation, managementDate } from '../domain/platformManagement'
import { setMyIdentity } from '../stores/identity'
import { LEGAL_ENTITY } from '../domain/legalPolicies'
import { auth } from '../stores/auth'
import { theme } from '../stores/theme'

const themeOptions = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
]

const route = useRoute()
const router = useRouter()

const profile = ref(null)
const loading = ref(true)
const loadError = ref('')

const nameDraft = ref('')
const savingName = ref(false)
const nameMessage = ref({ text: '', error: false })
const avatarInput = ref(null)
const avatarFile = ref(null)
const avatarPreview = ref('')
const savingAvatar = ref(false)
const avatarMessage = ref({ text: '', error: false })

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

// 实名：本人提交姓名和身份证号，服务端当场向权威数据源核验是否一致，不做人工审核。资料在服务端加密保存，这里只拿到脱敏后的结果。
const identity = ref(null)
const identityLoading = ref(true)
const identityError = ref('')
const identityOpen = ref(false)
const identitySubmitting = ref(false)
const identityFormError = ref('')
const identityNotice = ref('')
const identityForm = reactive({ legalName: '', idNumber: '', declarationAccepted: false })
const identityStates = {
  NONE: { label: '未实名', tone: '', text: '还没有提交实名资料。提交后当场核验姓名与身份证号码是否一致，结果会显示在这里。' },
  PENDING_REVIEW: { label: '审核中', tone: 'wait', text: '资料已提交，正在等待平台人工审核，请勿重复提交。' },
  VERIFIED: { label: '实名核验通过', tone: 'on', text: '你的实名资料已通过审核。' },
  REJECTED: { label: '未通过', tone: 'off', text: '核验没有通过。请根据下面的原因核对资料，再重新提交。' },
}
const identityState = computed(() => identityStates[identity.value?.status] || identityStates.NONE)
const canSubmitIdentity = computed(() => !identityLoading.value && !identityError.value && (!identity.value || identity.value.status === 'REJECTED'))

onMounted(async () => {
  document.querySelector('.page-scroll')?.classList.add('settings-scroll')
  await Promise.all([load(), loadIdentity()])
  // 旧的“用户实名”链接会带着 #identity 跳过来，直接定位到实名这一块。
  if (route.hash === '#identity') {
    await nextTick()
    document.getElementById('identity')?.scrollIntoView({ block: 'start' })
  }
})
onBeforeUnmount(() => {
  document.querySelector('.page-scroll')?.classList.remove('settings-scroll')
  clearIdentityForm()
  clearAvatarDraft()
})

function clearIdentityForm() {
  Object.assign(identityForm, { legalName: '', idNumber: '', declarationAccepted: false })
}

async function loadIdentity() {
  identityLoading.value = true
  identityError.value = ''
  identityNotice.value = ''
  try {
    identity.value = await identityApi.myIdentity()
    setMyIdentity(identity.value)
  } catch (error) {
    identityError.value = error.message || '实名状态暂时无法读取，请稍后重试'
  } finally {
    identityLoading.value = false
  }
}

function openIdentity() {
  clearIdentityForm()
  identityFormError.value = ''
  identityNotice.value = ''
  identityOpen.value = true
}
function closeIdentity() {
  if (identitySubmitting.value) return
  clearIdentityForm()
  identityOpen.value = false
}

async function submitIdentity() {
  if (identitySubmitting.value || !canSubmitIdentity.value) return
  identityFormError.value = identityValidation(identityForm)
  if (identityFormError.value) return
  identitySubmitting.value = true
  try {
    identity.value = await identityApi.submitIdentity({
      legalName: identityForm.legalName.trim(),
      idNumber: identityForm.idNumber.trim().toUpperCase(),
      declarationAccepted: identityForm.declarationAccepted,
    })
    setMyIdentity(identity.value)
    clearIdentityForm()
    identityOpen.value = false
    identityNotice.value = {
      VERIFIED: '实名认证已通过。',
      REJECTED: '姓名与身份证号码不一致，请核对后重新提交。',
    }[identity.value?.status] || '实名资料已提交。'
  } catch (error) {
    // 断网时核验可能其实已经完成；先刷新状态再决定要不要重交。
    identityFormError.value = error.status === 0
      ? `${error.message || '提交没有完成'}。请先点“刷新状态”确认结果，再决定是否重新提交。`
      : error.message || '提交失败，请稍后再试'
  } finally {
    identitySubmitting.value = false
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    profile.value = await accountApi.profile()
    nameDraft.value = profile.value.name || ''
    auth.updateAvatar(profile.value.avatarUrl, profile.value.id)
  } catch (error) {
    loadError.value = error.message || '账号资料加载失败，请重试'
  } finally {
    loading.value = false
  }
}

function clearAvatarDraft() {
  if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
  avatarPreview.value = ''
  avatarFile.value = null
  if (avatarInput.value) avatarInput.value.value = ''
}

function selectAvatar(event) {
  const file = event.target.files?.[0]
  if (!file) return
  avatarMessage.value = { text: '', error: false }
  try {
    validateAvatar(file)
    clearAvatarDraft()
    avatarFile.value = file
    avatarPreview.value = URL.createObjectURL(file)
  } catch (error) {
    event.target.value = ''
    avatarMessage.value = { text: error.message, error: true }
  }
}

async function saveAvatar() {
  if (!avatarFile.value || savingAvatar.value) return
  savingAvatar.value = true
  avatarMessage.value = { text: '', error: false }
  try {
    const saved = await accountApi.updateAvatar(avatarFile.value)
    profile.value = { ...profile.value, avatarUrl: saved.avatarUrl }
    auth.updateAvatar(saved.avatarUrl, saved.id)
    clearAvatarDraft()
    avatarMessage.value = { text: '头像已更新', error: false }
  } catch (error) {
    avatarMessage.value = { text: error.message || '头像保存失败，请重试', error: true }
  } finally {
    savingAvatar.value = false
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

/** 验证码发到本人手机；改密码和注销共用，提示写到各自表单下面。 */
async function sendCode(form = 'password') {
  const [notice, failure] = form === 'closure' ? [closureNotice, closureError] : [passwordNotice, passwordError]
  if (sendingCode.value || auth.state.cooldown > 0) return
  failure.value = ''
  sendingCode.value = true
  try {
    const result = await auth.sendCode(profile.value.phone)
    notice.value = result?.developmentMode
      ? '当前为本地调试模式，不会发送短信。验证码请在后端控制台查看。'
      : `验证码已发送到 ${maskedPhone.value}，5 分钟内有效。`
  } catch (error) {
    failure.value = error.message || '验证码发送失败，请稍后重试'
  } finally {
    sendingCode.value = false
  }
}

// 注销：先看服务端给的说明（只注销自己还是连商户一起关、会作废什么、现在能不能注销），再用验证码或当前密码确认。
const closureOpen = ref(false)
const closureLoading = ref(false)
const closure = ref(null)
const closing = ref(false)
const closureError = ref('')
const closureNotice = ref('')
const closureForm = reactive({ code: '', currentPassword: '', confirmed: false })

async function openClosure() {
  Object.assign(closureForm, { code: '', currentPassword: '', confirmed: false })
  closureError.value = ''
  closureNotice.value = ''
  closure.value = null
  closureOpen.value = true
  closureLoading.value = true
  try {
    closure.value = await accountApi.closurePreview()
  } catch (error) {
    closureError.value = error.message || '注销说明加载失败，请重试'
  } finally {
    closureLoading.value = false
  }
}
function closeClosure() { if (!closing.value) closureOpen.value = false }

async function confirmClosure() {
  if (closing.value || !closure.value || closure.value.blockedReason) return
  closureError.value = ''
  closing.value = true
  try {
    await accountApi.close(closureForm, closure.value.byCode)
    auth.clearSession()
    router.replace({ name: 'login' })
  } catch (error) {
    closureError.value = error.message || '注销没有完成，请重试'
    closing.value = false
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
      <h1>设置</h1>
      <p class="header-desc">查看和修改你自己的账号资料、登录密码、实名认证和外观。账号设置只影响当前登录的这个账号。</p>
    </header>

    <section class="settings-card" aria-labelledby="appearance-title">
      <header class="card-header">
        <div>
          <h2 id="appearance-title">外观</h2>
          <p>只在这台设备的这个浏览器里生效。</p>
        </div>
      </header>
      <div class="theme-options" role="radiogroup" aria-labelledby="appearance-title">
        <button v-for="option in themeOptions" :key="option.value" type="button" role="radio" class="theme-option" :class="{ active: theme.current.value === option.value }" :aria-checked="theme.current.value === option.value" @click="theme.set(option.value)">{{ option.label }}</button>
      </div>
    </section>

    <div v-if="loading" class="loading-state">正在加载账号资料…</div>
    <div v-else-if="loadError" class="page-notice" role="alert">
      {{ loadError }} <button class="link-button" @click="load">重试</button>
    </div>
    <template v-else-if="profile">
      <section class="settings-card" aria-labelledby="profile-title">
        <header class="card-header">
          <div>
            <h2 id="profile-title">个人资料</h2>
            <p>头像和名字用于识别你的账号。名字也会显示在管理员的员工列表里。</p>
          </div>
          <span class="role-badge" :class="{ owner: profile.role !== 'STAFF' }">{{ roleText }}</span>
        </header>
        <div class="avatar-section" :aria-busy="savingAvatar">
          <UserAvatar class="profile-avatar" :src="avatarPreview || profile.avatarUrl" :name="profile.name" />
          <div class="avatar-details">
            <h3>个人头像</h3>
            <p id="avatar-help">支持 JPG、PNG，最大 2 MB。头像将居中裁剪，保存后显示在顶部和侧栏。</p>
            <input ref="avatarInput" class="avatar-file-input" type="file" accept="image/jpeg,image/png" aria-label="选择头像图片" aria-describedby="avatar-help" :disabled="savingAvatar" @change="selectAvatar" />
            <div class="avatar-actions">
              <button type="button" class="ghost-button" :disabled="savingAvatar" @click="avatarInput?.click()">{{ avatarFile ? '重新选择' : '更换头像' }}</button>
              <template v-if="avatarFile">
                <button type="button" class="primary-button" :disabled="savingAvatar" @click="saveAvatar">{{ savingAvatar ? '保存中…' : '保存头像' }}</button>
                <button type="button" class="link-button" :disabled="savingAvatar" @click="clearAvatarDraft">取消</button>
              </template>
            </div>
            <p v-if="avatarMessage.text" class="inline-message" :class="{ error: avatarMessage.error }" :role="avatarMessage.error ? 'alert' : 'status'">{{ avatarMessage.text }}</p>
          </div>
        </div>
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
              <button type="button" class="ghost-button" :disabled="savingPassword || sendingCode || auth.state.cooldown > 0" @click="sendCode('password')">
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

      <section id="identity" class="settings-card" aria-labelledby="identity-title">
        <header class="card-header">
          <div>
            <h2 id="identity-title">实名认证</h2>
            <p>{{ identityLoading ? '正在查询实名状态…' : identityError ? '实名状态暂时无法确认。' : identityState.text }}</p>
          </div>
          <span v-if="!identityLoading && !identityError" class="state-badge" :class="identityState.tone"><ShieldCheck v-if="identity?.status === 'VERIFIED'" :size="14" aria-hidden="true" />{{ identityState.label }}</span>
        </header>
        <p v-if="identityNotice" class="inline-message" role="status">{{ identityNotice }}</p>
        <p v-if="identityError" class="inline-message error" role="alert">{{ identityError }}</p>

        <template v-if="identity">
          <dl class="facts">
            <div><dt>姓名</dt><dd>{{ identity.legalNameMasked }}</dd></div>
            <div><dt>身份证号</dt><dd>{{ identity.idNumberMasked }}</dd></div>
            <div><dt>提交时间</dt><dd>{{ managementDate(identity.submittedAt) }}</dd></div>
            <div v-if="identity.reviewedAt"><dt>核验时间</dt><dd>{{ managementDate(identity.reviewedAt) }}</dd></div>
          </dl>
          <div v-if="identity.status === 'REJECTED'" class="review-note">
            <strong>未通过原因</strong>
            <p v-if="identity.reviewNote">{{ identity.reviewNote }}</p>
            <p>如有疑问请拨打人工客服：<a :href="`tel:${LEGAL_ENTITY.phone}`">{{ LEGAL_ENTITY.phone }}</a></p>
          </div>
        </template>

        <div v-if="!identityOpen" class="card-actions">
          <button v-if="canSubmitIdentity" class="ghost-button" @click="openIdentity">{{ identity ? '重新提交实名资料' : '去实名' }}</button>
          <button class="link-button" :disabled="identityLoading" @click="loadIdentity">刷新状态</button>
        </div>

        <form v-else class="password-form" autocomplete="off" @submit.prevent="submitIdentity">
          <label class="field">
            <span>真实姓名</span>
            <input v-model="identityForm.legalName" name="legal-name" autocomplete="off" maxlength="40" placeholder="与身份证上的姓名一致" :disabled="identitySubmitting" />
          </label>
          <label class="field">
            <span>居民身份证号码</span>
            <input v-model="identityForm.idNumber" name="id-number" autocomplete="off" autocapitalize="characters" spellcheck="false" maxlength="18" placeholder="18 位身份证号码" :disabled="identitySubmitting" />
            <small>只用于平台核对身份，提交后这里只显示打码后的号码。</small>
          </label>
          <label class="consent">
            <input v-model="identityForm.declarationAccepted" type="checkbox" :disabled="identitySubmitting" />
            <span>我确认以上是本人真实、准确的身份信息，已阅读<a href="/policies/identity" target="_blank" rel="noopener noreferrer">《敏感个人信息处理说明》</a>，并单独同意平台为本次实名审核处理。不能替别人提交。</span>
          </label>
          <p class="hint">姓名和身份证号在服务端加密保存，只有平台授权人员能查看完整资料，每次查看都会留下记录。提交后经阿里云实人认证当场核验姓名与身份证号码是否一致。</p>
          <div class="form-footer">
            <p v-if="identityFormError" class="form-notice" role="alert">{{ identityFormError }}</p>
            <div>
              <button type="button" class="ghost-button" :disabled="identitySubmitting" @click="closeIdentity">取消</button>
              <button type="submit" class="primary-button" :disabled="identitySubmitting">{{ identitySubmitting ? '提交中…' : '提交实名' }}</button>
            </div>
          </div>
        </form>
      </section>

      <section class="settings-card danger-card" aria-labelledby="closure-title">
        <header class="card-header">
          <div>
            <h2 id="closure-title">注销账号</h2>
            <p>注销后这个账号立即停用，所有设备上的登录退出，不能恢复。{{ profile.role === 'STAFF' ? '只影响你自己，商户和其他成员不受影响。' : '如果你是商户里最后一位管理员，整个商户会一起关闭。' }}</p>
          </div>
        </header>
        <button v-if="!closureOpen" class="danger-button" @click="openClosure">注销账号</button>

        <div v-else class="closure-panel">
          <p v-if="closureLoading" class="hint">正在确认注销会影响什么…</p>
          <template v-else-if="closure">
            <div class="consequences">
              <strong v-if="closure.wholeTenant">你是商户里最后一位管理员，注销后整个商户会一起关闭：</strong>
              <strong v-else>只注销你自己的账号，商户和其他成员不受影响：</strong>
              <ul>
                <template v-if="closure.wholeTenant">
                  <li v-if="closure.otherMembers > 0">商户里另外 {{ closure.otherMembers }} 位成员的账号也会一起注销，并立即退出登录</li>
                  <li>所有门店关闭，对外的店铺页、商品和信息发布全部下线</li>
                  <li>商户里的商品、知识库、作品等资料都无法再查看</li>
                </template>
                <li v-for="loss in closure.losses" :key="loss">{{ loss }}</li>
                <li>{{ profile.phone ? '手机号会被释放，以后用它登录会注册成一个全新的商户' : '登录账号和密码会被清除' }}</li>
                <li>立即生效，不能撤销，也不能恢复</li>
              </ul>
            </div>
            <p v-if="closure.blockedReason" class="form-notice" role="alert">现在还不能注销：{{ closure.blockedReason }}</p>

            <form v-else class="password-form" @submit.prevent="confirmClosure">
              <div v-if="closure.byCode" class="field">
                <label for="closure-code">短信验证码</label>
                <div class="code-row">
                  <input id="closure-code" v-model="closureForm.code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="6 位验证码" :disabled="closing" />
                  <button type="button" class="ghost-button" :disabled="closing || sendingCode || auth.state.cooldown > 0" @click="sendCode('closure')">
                    {{ sendingCode ? '发送中…' : auth.state.cooldown > 0 ? `${auth.state.cooldown}s 后重试` : '获取验证码' }}
                  </button>
                </div>
                <small>验证码会发到 {{ maskedPhone }}，用来确认是你本人在操作。</small>
              </div>
              <label v-else class="field">
                <span>当前密码</span>
                <input v-model="closureForm.currentPassword" type="password" autocomplete="current-password" maxlength="64" :disabled="closing" />
              </label>
              <label class="consent">
                <input v-model="closureForm.confirmed" type="checkbox" :disabled="closing" />
                <span>我已了解上面的后果，确认注销{{ closure.wholeTenant ? '账号并关闭整个商户' : '我的账号' }}。</span>
              </label>
              <p v-if="closureNotice" class="inline-message" role="status">{{ closureNotice }}</p>
              <div class="form-footer">
                <p v-if="closureError" class="form-notice" role="alert">{{ closureError }}</p>
                <div>
                  <button type="button" class="ghost-button" :disabled="closing" @click="closeClosure">取消</button>
                  <button type="submit" class="danger-button solid" :disabled="closing || !closureForm.confirmed">{{ closing ? '注销中…' : '确认注销' }}</button>
                </div>
              </div>
            </form>
          </template>
          <p v-if="closureError && !closure" class="form-notice" role="alert">{{ closureError }}</p>
          <div v-if="!closure || closure.blockedReason" class="card-actions">
            <button class="ghost-button" @click="closeClosure">收起</button>
            <button v-if="!closureLoading" class="link-button" @click="openClosure">重新检查</button>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
:global(.page-scroll.settings-scroll) { background: var(--color-bg-canvas); color: var(--color-primary); }
.settings-page { padding-bottom: 72px; font-family: var(--font-sans); color: var(--color-primary); }
.settings-header { margin-bottom: 28px; }.eyebrow { margin: 0; color: var(--color-text-muted); font-size: 12px; font-weight: 700; letter-spacing: .14em; }
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
.role-badge, .state-badge { flex: none; padding: 3px 8px; border-radius: 4px; color: var(--color-text-muted); background: var(--color-bg-subtle); font-size: 12px; font-weight: 700; }
.role-badge.owner { color: var(--color-accent-text); background: color-mix(in srgb, var(--color-accent-text) 8%, var(--color-bg-surface)); }
.state-badge.on { display: inline-flex; align-items: center; gap: 4px; color: var(--color-success); background: color-mix(in srgb, var(--color-success) 10%, var(--color-bg-surface)); }

.theme-options { display: inline-flex; gap: 4px; padding: 4px; border: 1px solid var(--color-border-subtle); border-radius: 6px; background: var(--color-bg-subtle); }
.theme-option { min-width: 72px; padding: 8px 14px; border: 0; border-radius: 4px; color: var(--color-text-muted); background: transparent; font-family: var(--font-sans); font-size: 13px; font-weight: 700; cursor: pointer; }
.theme-option.active { color: var(--color-primary); background: var(--color-bg-surface); box-shadow: 0 1px 2px color-mix(in srgb, var(--color-primary) 12%, transparent); }

.name-row { display: flex; align-items: flex-end; gap: 10px; }
.avatar-section { display: flex; align-items: flex-start; gap: 18px; padding-bottom: 22px; margin-bottom: 20px; border-bottom: 1px solid var(--color-border-subtle); }
.profile-avatar { width: 72px; height: 72px; font-size: 28px; }
.avatar-details { min-width: 0; }
.avatar-details h3 { margin: 0 0 4px; font-size: 13px; }
.avatar-details > p:not(.inline-message) { margin: 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.7; }
.avatar-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-top: 12px; }
.avatar-file-input { display: none; }
.name-row .field { flex: 1; margin: 0; }
.field { display: grid; gap: 7px; margin: 0 0 16px; }
.field > span, .field > label { color: var(--color-primary); font-size: 12px; font-weight: 700; }
.field input { width: 100%; height: 40px; box-sizing: border-box; padding: 10px 11px; border: 1px solid var(--color-border-subtle); border-radius: 4px; outline: 0; color: var(--color-primary); background: var(--color-bg-surface); font: 500 13px/1.5 inherit; }
.field input:focus { border-color: var(--color-accent); }
.field input:disabled { color: var(--color-text-muted); background: var(--color-bg-subtle); }
.field small { color: var(--color-text-muted); font-size: 12px; line-height: 1.6; }
.code-row { display: flex; gap: 10px; }
.code-row input { flex: 1; }
.inline-message { margin: 10px 0 0; color: var(--color-success); font-size: 12px; line-height: 1.7; }
.inline-message.error { color: var(--color-error); }
.settings-card > .inline-message + .ghost-button { margin-top: 14px; }

.facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 14px; margin: 20px 0 0; padding: 16px 0 0; border-top: 1px solid var(--color-border-subtle); }
.facts dt { color: var(--color-text-muted); font-size: 12px; font-weight: 700; }
.facts dd { margin: 4px 0 0; font-size: 14px; word-break: break-all; }
.card-foot { margin: 16px 0 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.6; }

.password-form { max-width: 420px; }
.proof-switch { margin: -6px 0 16px; font-size: 12px; }
.warning { margin: 0 0 12px; padding: 10px 12px; border-radius: 4px; color: var(--color-warning); background: color-mix(in srgb, var(--color-warning) 8%, var(--color-bg-surface)); font-size: 12px; line-height: 1.7; }
.form-footer { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding-top: 4px; }
.form-footer > div { display: flex; gap: 9px; margin-left: auto; }
.form-notice { margin: 0; color: var(--color-error); font-size: 12px; line-height: 1.6; }

.state-badge.wait { color: var(--color-warning); background: color-mix(in srgb, var(--color-warning) 10%, var(--color-bg-surface)); }
.state-badge.off { color: var(--color-error); background: color-mix(in srgb, var(--color-error) 8%, var(--color-bg-surface)); }
.card-actions { display: flex; align-items: center; gap: 16px; margin-top: 18px; }
.review-note { margin-top: 16px; padding: 12px 14px; border-radius: 4px; background: var(--color-bg-subtle); font-size: 12px; line-height: 1.7; }
.review-note p { margin: 4px 0 0; color: var(--color-text-muted); white-space: pre-wrap; }
.consent { display: flex; gap: 9px; align-items: flex-start; margin: 0 0 14px; color: var(--color-text-muted); font-size: 12px; line-height: 1.7; cursor: pointer; }
.consent input { margin-top: 4px; accent-color: var(--color-accent); }
.consent a { color: var(--color-accent-text); text-decoration: underline; text-underline-offset: 2px; }
.hint { margin: 0 0 14px; color: var(--color-text-muted); font-size: 12px; line-height: 1.7; }
#identity { scroll-margin-top: 20px; }

.danger-card { border-color: color-mix(in srgb, var(--color-error) 28%, var(--color-border-subtle)); }
.danger-button { flex: none; border: 1px solid var(--color-error); border-radius: 4px; padding: 10px 17px; color: var(--color-error); background: transparent; font-family: var(--font-sans); font-size: 13px; font-weight: 700; cursor: pointer; }
.danger-button:hover:not(:disabled) { background: color-mix(in srgb, var(--color-error) 8%, var(--color-bg-surface)); }
.danger-button.solid { color: var(--color-on-accent); background: var(--color-error); }
.danger-button.solid:hover:not(:disabled) { background: color-mix(in srgb, var(--color-error) 86%, black); }
.danger-button:disabled { opacity: .55; cursor: default; }
.consequences { margin: 0 0 16px; padding: 12px 14px; border-radius: 4px; color: var(--color-primary); background: color-mix(in srgb, var(--color-error) 6%, var(--color-bg-surface)); font-size: 12px; line-height: 1.7; }
.consequences ul { margin: 6px 0 0; padding-left: 18px; color: var(--color-text-muted); }
.closure-panel > .form-notice { margin-bottom: 4px; }

@media (max-width: 640px) {
  .avatar-section { gap: 12px; }
  .profile-avatar { width: 56px; height: 56px; font-size: 24px; }
  .settings-page { padding-bottom: 58px; }
  .settings-header h1 { font-size: 30px; }
  .settings-card { padding: 18px 16px; }
  .card-header { flex-direction: column-reverse; gap: 10px; }
  .form-footer { flex-direction: column; align-items: stretch; }
  .form-footer > div { margin-left: 0; justify-content: flex-end; }
}
</style>
