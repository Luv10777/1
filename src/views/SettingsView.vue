<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Check, ChevronRight, CircleCheck, Coins, KeyRound, Lock, MessageCircle, RefreshCw, ShieldCheck, Smartphone, Ticket, User } from 'lucide-vue-next'
import { accountApi, validateAvatar } from '../services/accountApi'
import UserAvatar from '../components/UserAvatar.vue'
import PasswordDialog from '../components/settings/PasswordDialog.vue'
import PhoneDialog from '../components/settings/PhoneDialog.vue'
import WechatDialog from '../components/settings/WechatDialog.vue'
import { platformManagementApi as identityApi } from '../services/platformManagement'
import { referralApi } from '../services/referralApi'
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

// 账号与安全：登录密码、手机号、微信三项，各自在弹窗里改。
const dialog = ref('')
const sendingCode = ref(false)
const securityNotice = ref({ text: '', error: false })
const wechat = ref(null)
const wechatError = ref('')

const roleText = computed(() => (profile.value?.role === 'STAFF' ? '店员' : '管理员'))
const joinedAt = computed(() => (profile.value?.createdAt ? new Date(profile.value.createdAt).toLocaleDateString('zh-CN') : ''))
const nameChanged = computed(() => nameDraft.value.trim() && nameDraft.value.trim() !== profile.value?.name)
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
  NONE: { label: '未实名', tone: '', text: '核验本人身份，解锁推广返利与提现。' },
  PENDING_REVIEW: { label: '审核中', tone: 'wait', text: '资料已提交，正在等待平台人工审核，请勿重复提交。' },
  VERIFIED: { label: '已通过', tone: 'on', text: '你的身份已核验，无需再次提交。' },
  REJECTED: { label: '未通过', tone: 'off', text: '核验没有通过。请根据下面的原因核对资料，再重新提交。' },
}
// 实名通过后的「已解锁」列表要知道邀请码领了没有、银行卡绑了没有；读不到时按未领取、未绑定显示，链接照常可点。
const referral = ref(null)
const identityState = computed(() => identityStates[identity.value?.status] || identityStates.NONE)
const canSubmitIdentity = computed(() => !identityLoading.value && !identityError.value && (!identity.value || identity.value.status === 'REJECTED'))

onMounted(async () => {
  document.querySelector('.page-scroll')?.classList.add('settings-scroll')
  readWechatResult()
  await Promise.all([load(), loadIdentity(), loadWechat()])
  // 旧的“用户实名”链接会带着 #identity 跳过来，直接定位到实名这一块；扫码绑定微信回来定位到账号与安全。
  const anchor = route.hash === '#identity' ? 'identity' : securityNotice.value.text ? 'security' : ''
  if (anchor) {
    await nextTick()
    document.getElementById(anchor)?.scrollIntoView({ block: 'start' })
  }
})

/** 扫码绑定微信后，回调把结果放在地址栏里带回来；读完就从地址栏去掉，刷新不会重复提示。 */
function readWechatResult() {
  const { wechat_bound: bound, wechat_error: failure, ...rest } = route.query
  if (!bound && !failure) return
  securityNotice.value = bound
    ? { text: '微信已绑定。以后可以在登录页用这个微信扫码登录。', error: false }
    : { text: `微信没有绑定成功：${failure}`, error: true }
  router.replace({ query: rest, hash: route.hash })
}

async function loadWechat() {
  wechatError.value = ''
  try {
    wechat.value = await accountApi.wechat()
  } catch (error) {
    wechatError.value = error.message || '微信绑定状态暂时无法读取'
  }
}

function onPasswordSaved({ session, first }) {
  auth.replaceSession(session)
  profile.value = { ...profile.value, passwordSet: true }
  dialog.value = ''
  securityNotice.value = { text: `${first ? '密码已设置，以后也可以用手机号加密码登录。' : '密码已修改。'}其他电脑、手机和桌面端上的登录已退出。`, error: false }
}

function onPhoneChanged(saved) {
  profile.value = { ...profile.value, ...saved }
  auth.restore()
}
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
    if (identity.value?.status === 'VERIFIED') loadReferral()
  } catch (error) {
    identityError.value = error.message || '实名状态暂时无法读取，请稍后重试'
  } finally {
    identityLoading.value = false
  }
}

async function loadReferral() {
  try {
    referral.value = await referralApi.me()
  } catch { /* 读不到就按未领取、未绑定显示 */ }
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

function openDialog(name) {
  securityNotice.value = { text: '', error: false }
  dialog.value = name
}

/** 注销确认用的验证码，发到本人手机。 */
async function sendCode() {
  if (sendingCode.value || auth.state.cooldown > 0) return
  closureError.value = ''
  sendingCode.value = true
  try {
    const result = await auth.sendCode(profile.value.phone)
    closureNotice.value = result?.developmentMode
      ? '当前为本地调试模式，不会发送短信。验证码请在后端控制台查看。'
      : `验证码已发送到 ${maskedPhone.value}，5 分钟内有效。`
  } catch (error) {
    closureError.value = error.message || '验证码发送失败，请稍后重试'
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

</script>

<template>
  <div class="settings-page">
    <header class="settings-header">
      <h1>设置</h1>
      <p class="header-desc">查看和修改你自己的账号资料、登录方式、实名认证和外观。账号设置只影响当前登录的这个账号。</p>
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
          <div v-if="profile.username"><dt>登录账号</dt><dd>{{ profile.username }}</dd></div>
          <div><dt>角色</dt><dd>{{ roleText }}</dd></div>
          <div v-if="joinedAt"><dt>注册时间</dt><dd>{{ joinedAt }}</dd></div>
        </dl>
        <p v-if="profile.role === 'STAFF'" class="card-foot">角色由商户管理员设置，需要调整请联系管理员。</p>
      </section>

      <section id="security" class="settings-card" aria-labelledby="security-title">
        <header class="card-header">
          <div>
            <h2 id="security-title">账号与安全</h2>
            <p>管理登录这个账号的方式。改动只影响你自己。</p>
          </div>
        </header>
        <Transition name="notice">
          <p v-if="securityNotice.text" class="security-notice" :class="{ error: securityNotice.error }" :role="securityNotice.error ? 'alert' : 'status'">
            <Check v-if="!securityNotice.error" :size="15" aria-hidden="true" />{{ securityNotice.text }}
          </p>
        </Transition>
        <ul class="security-list">
          <li>
            <span class="security-icon" aria-hidden="true"><KeyRound :size="19" /></span>
            <div class="security-main">
              <strong>登录密码</strong>
              <span>{{ profile.passwordSet ? `可以用${profile.phone ? '手机号' : '账号'}加密码登录，不用每次收验证码` : '还没有设置，目前只能用验证码登录' }}</span>
            </div>
            <span class="state-badge" :class="{ on: profile.passwordSet }"><i class="state-dot" aria-hidden="true" />{{ profile.passwordSet ? '已设置' : '未设置' }}</span>
            <button type="button" class="ghost-button" :disabled="!profile.passwordSet && !profile.phone" :title="!profile.passwordSet && !profile.phone ? '请先绑定手机号' : undefined" @click="openDialog('password')">{{ profile.passwordSet ? '修改' : '设置' }}</button>
          </li>
          <li>
            <span class="security-icon" aria-hidden="true"><Smartphone :size="19" /></span>
            <div class="security-main">
              <strong>手机号</strong>
              <span v-if="profile.phone" class="security-value">{{ maskedPhone }}</span>
              <span v-else>未绑定。绑定后可以收验证码登录、找回密码</span>
            </div>
            <span class="state-badge" :class="{ on: profile.phone }"><i class="state-dot" aria-hidden="true" />{{ profile.phone ? '已绑定' : '未绑定' }}</span>
            <button type="button" :class="profile.phone ? 'ghost-button' : 'primary-button'" @click="openDialog('phone')">{{ profile.phone ? '更换' : '绑定' }}</button>
          </li>
          <li>
            <span class="security-icon wechat" aria-hidden="true"><MessageCircle :size="19" /></span>
            <div class="security-main">
              <strong>微信</strong>
              <span v-if="wechatError">{{ wechatError }}</span>
              <span v-else-if="!wechat">正在查询…</span>
              <span v-else-if="wechat.bound">已绑定{{ wechat.nickname ? `「${wechat.nickname}」` : '' }}，可以在登录页扫码登录</span>
              <span v-else-if="wechat.available">微信尚未绑定。绑定后可以用微信扫码登录</span>
              <span v-else>微信尚未绑定。扫码绑定暂未开放</span>
            </div>
            <span v-if="wechat" class="state-badge" :class="{ on: wechat.bound }"><i class="state-dot" aria-hidden="true" />{{ wechat.bound ? '已绑定' : '未绑定' }}</span>
            <button v-if="wechatError" type="button" class="ghost-button" @click="loadWechat">重试</button>
            <button v-else-if="wechat && !wechat.bound" type="button" class="primary-button" :disabled="!wechat.available" @click="openDialog('wechat')">扫码绑定</button>
          </li>
        </ul>
      </section>

      <PasswordDialog :open="dialog === 'password'" :profile="profile" @close="dialog = ''" @saved="onPasswordSaved" />
      <PhoneDialog :open="dialog === 'phone'" :profile="profile" @close="dialog = ''" @changed="onPhoneChanged" />
      <WechatDialog :open="dialog === 'wechat'" @close="dialog = ''" />

      <section id="identity" class="settings-card" aria-labelledby="identity-title">
        <header class="card-header identity-header">
          <span class="identity-icon" :class="{ ok: identity?.status === 'VERIFIED' }" aria-hidden="true"><ShieldCheck :size="24" /></span>
          <div>
            <h2 id="identity-title">实名认证</h2>
            <p>{{ identityLoading ? '正在查询实名状态…' : identityError ? '实名状态暂时无法确认。' : identityState.text }}</p>
          </div>
          <span v-if="!identityLoading && !identityError" class="state-badge" :class="identityState.tone"><Check v-if="identity?.status === 'VERIFIED'" :size="14" aria-hidden="true" /><i v-else class="state-dot" aria-hidden="true" />{{ identityState.label }}</span>
        </header>
        <p v-if="identityNotice" class="inline-message" role="status">{{ identityNotice }}</p>
        <p v-if="identityError" class="inline-message error" role="alert">{{ identityError }}</p>

        <template v-if="identity?.status === 'VERIFIED'">
          <div class="id-card">
            <span class="id-avatar" aria-hidden="true"><User :size="24" /><i><Check :size="12" /></i></span>
            <div class="id-main">
              <strong>{{ identity.legalNameMasked }}</strong>
              <span class="id-number">{{ identity.idNumberMasked }}</span>
            </div>
            <div v-if="identity.reviewedAt" class="id-time">核验时间<span>{{ managementDate(identity.reviewedAt) }}</span></div>
          </div>
          <div class="unlocked">
            <p>已为你解锁</p>
            <div class="unlocked-row">
              <CircleCheck :size="18" aria-hidden="true" />
              <span>专属邀请码<small>邀请商户注册，赚取充值与会员返利</small></span>
              <span v-if="referral?.code" class="unlocked-done">已领取</span>
              <RouterLink v-else :to="{ name: 'referral' }">去领取<ChevronRight :size="14" aria-hidden="true" /></RouterLink>
            </div>
            <div class="unlocked-row">
              <CircleCheck :size="18" aria-hidden="true" />
              <span>返利提现<small>{{ referral?.bankCard ? `收款卡：${referral.bankCard.bankName} 尾号 ${referral.bankCard.cardLast4}` : '还需绑定一张本人名下的银行卡' }}</small></span>
              <span v-if="referral?.bankCard" class="unlocked-done">已绑定</span>
              <RouterLink v-else :to="{ name: 'referral' }">去绑定<ChevronRight :size="14" aria-hidden="true" /></RouterLink>
            </div>
          </div>
        </template>

        <template v-else-if="identity">
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

        <template v-else-if="!identityLoading && !identityError && !identityOpen">
          <ol class="id-steps">
            <li class="on"><span>1</span><div><b>填写信息</b><small>姓名与身份证号</small></div></li>
            <li><span>2</span><div><b>当场核验</b><small>阿里云实人认证</small></div></li>
            <li><span>3</span><div><b>解锁权益</b><small>邀请码与提现</small></div></li>
          </ol>
          <div class="id-perks">
            <div><Ticket :size="20" aria-hidden="true" /><span><b>专属邀请码</b><small>邀请商户注册，对方充值、开通会员按比例返现</small></span></div>
            <div><Coins :size="20" aria-hidden="true" /><span><b>返利提现</b><small>绑定本人银行卡，平台审核后转账到账</small></span></div>
          </div>
        </template>

        <div v-if="!identityOpen" class="id-foot">
          <p class="id-secure"><Lock :size="15" aria-hidden="true" />{{ identity?.status === 'VERIFIED' ? '姓名与号码仅脱敏显示，完整资料加密保存' : '资料加密保存，只核验姓名与号码是否一致，不做人工审核' }}</p>
          <div class="id-foot-actions">
            <button class="link-button" :disabled="identityLoading" @click="loadIdentity"><RefreshCw :size="13" aria-hidden="true" /> 刷新状态</button>
            <button v-if="canSubmitIdentity" class="primary-button" @click="openIdentity">{{ identity ? '重新提交实名资料' : '去实名' }}</button>
          </div>
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
          <p class="hint">姓名和身份证号在服务端加密保存，只有平台授权人员能查看完整资料，每次查看都会留下记录。</p>
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
                  <button type="button" class="ghost-button" :disabled="closing || sendingCode || auth.state.cooldown > 0" @click="sendCode">
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

.security-list { margin: 0; padding: 0; list-style: none; border: 1px solid var(--color-border-subtle); border-radius: 10px; }
.security-list li { display: flex; align-items: center; gap: 14px; padding: 16px 18px; transition: background-color .15s; }
.security-list li + li { border-top: 1px solid var(--color-border-subtle); }
.security-list li:hover { background: color-mix(in srgb, var(--color-bg-subtle) 55%, transparent); }
.security-icon { flex: none; display: grid; place-items: center; width: 40px; height: 40px; border-radius: 10px; color: var(--color-accent-text); background: color-mix(in srgb, var(--color-accent) 10%, var(--color-bg-surface)); }
.security-icon.wechat { color: #07a35a; background: color-mix(in srgb, #07c160 12%, var(--color-bg-surface)); }
.security-main { flex: 1; min-width: 0; }
.security-main strong { display: block; font-size: 14px; }
.security-main span { display: block; margin-top: 3px; color: var(--color-text-muted); font-size: 12px; line-height: 1.6; }
.security-main .security-value { color: var(--color-primary); font-family: var(--font-mono); font-size: 13px; letter-spacing: .04em; }
.security-list .state-badge { display: inline-flex; align-items: center; border-radius: 99px; padding: 3px 10px; }
.security-list .ghost-button, .security-list .primary-button { min-width: 84px; border-radius: 6px; padding: 8px 14px; }
.security-notice { display: flex; align-items: flex-start; gap: 8px; margin: 0 0 14px; padding: 10px 14px; border-radius: 8px; color: var(--color-success); background: color-mix(in srgb, var(--color-success) 8%, var(--color-bg-surface)); font-size: 13px; line-height: 1.6; }
.security-notice svg { flex: none; margin-top: 3px; }
.security-notice.error { color: var(--color-error); background: color-mix(in srgb, var(--color-error) 8%, var(--color-bg-surface)); }
.notice-enter-active, .notice-leave-active { transition: opacity .2s ease, transform .2s ease; }
.notice-enter-from, .notice-leave-to { opacity: 0; transform: translateY(-4px); }
#security { scroll-margin-top: 20px; }
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
.identity-header { align-items: center; margin-bottom: 0; }
.identity-header > div { flex: 1; min-width: 0; }
.identity-icon { flex: none; display: grid; place-items: center; width: 44px; height: 44px; border-radius: 10px; color: var(--color-accent-text); background: color-mix(in srgb, var(--color-accent) 10%, var(--color-bg-surface)); }
.identity-icon.ok { color: var(--color-success); background: color-mix(in srgb, var(--color-success) 10%, var(--color-bg-surface)); }
.identity-header .state-badge { border-radius: 99px; padding: 3px 10px; }
.state-dot { display: inline-block; width: 6px; height: 6px; margin-right: 5px; border-radius: 50%; background: currentColor; vertical-align: 1px; }
.id-steps { display: grid; grid-template-columns: repeat(3, 1fr); margin: 22px 0 0; padding: 0; list-style: none; }
.id-steps li { position: relative; display: flex; gap: 10px; align-items: flex-start; padding-right: 10px; }
.id-steps li:not(:last-child)::after { content: ""; position: absolute; top: 13px; right: 8px; left: 34px; height: 1px; background: var(--color-border-subtle); }
.id-steps li > span { position: relative; z-index: 1; flex: none; display: grid; place-items: center; width: 26px; height: 26px; border: 1px solid var(--color-border-strong); border-radius: 50%; color: var(--color-text-muted); background: var(--color-bg-surface); font-size: 12px; font-weight: 700; }
.id-steps li.on > span { border-color: var(--color-accent); color: var(--color-on-accent); background: var(--color-accent); }
.id-steps b { position: relative; z-index: 1; display: block; width: max-content; padding-right: 6px; font-size: 13px; background: var(--color-bg-surface); }
.id-steps small { display: block; margin-top: 1px; color: var(--color-text-muted); font-size: 12px; }
.id-perks { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; margin-top: 20px; }
.id-perks > div { display: flex; gap: 12px; padding: 14px; border-radius: 8px; background: var(--color-bg-subtle); }
.id-perks svg { flex: none; margin-top: 1px; color: var(--color-accent-text); }
.id-perks b { display: block; font-size: 13px; }
.id-perks small { display: block; margin-top: 2px; color: var(--color-text-muted); font-size: 12px; line-height: 1.6; }
.id-card { display: flex; flex-wrap: wrap; align-items: center; gap: 16px; margin-top: 20px; padding: 18px; border-radius: 8px; background: var(--color-bg-subtle); }
.id-avatar { position: relative; flex: none; display: grid; place-items: center; width: 52px; height: 52px; border: 1px solid var(--color-border-subtle); border-radius: 50%; color: var(--color-text-muted); background: var(--color-bg-surface); }
.id-avatar i { position: absolute; right: -3px; bottom: -3px; display: grid; place-items: center; width: 20px; height: 20px; border: 2px solid var(--color-bg-subtle); border-radius: 50%; color: #fff; background: var(--color-success); }
.id-main { flex: 1; min-width: 150px; }
.id-main strong { display: block; font-size: 17px; }
.id-number { color: var(--color-text-muted); font-family: var(--font-mono, ui-monospace, monospace); font-size: 13px; letter-spacing: .06em; }
.id-time { color: var(--color-text-muted); font-size: 12px; text-align: right; }
.id-time span { display: block; color: var(--color-primary); font-size: 13px; }
.unlocked { margin-top: 16px; border: 1px solid var(--color-border-subtle); border-radius: 8px; }
.unlocked > p { margin: 0; padding: 10px 14px; border-bottom: 1px solid var(--color-border-subtle); color: var(--color-text-muted); font-size: 12px; }
.unlocked-row { display: flex; align-items: center; gap: 12px; padding: 12px 14px; font-size: 13px; }
.unlocked-row + .unlocked-row { border-top: 1px solid var(--color-border-subtle); }
.unlocked-row > svg { flex: none; color: var(--color-success); }
.unlocked-row > span { flex: 1; min-width: 0; }
.unlocked-row small { display: block; color: var(--color-text-muted); font-size: 12px; }
.unlocked-row > .unlocked-done { flex: none; color: var(--color-success); font-weight: 700; }
.unlocked-row a { display: inline-flex; align-items: center; gap: 2px; color: var(--color-accent-text); text-decoration: none; }
.id-foot { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px 16px; margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--color-border-subtle); }
.id-secure { display: flex; gap: 6px; align-items: flex-start; flex: 1 1 260px; margin: 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.6; }
.id-secure svg { flex: none; margin-top: 2px; }
.id-foot-actions { display: flex; align-items: center; gap: 16px; margin-left: auto; }
.id-foot .link-button { display: inline-flex; align-items: center; gap: 4px; color: var(--color-text-muted); font-weight: 500; }
.identity-header ~ .password-form { margin-top: 20px; }
.id-foot .link-button:hover:not(:disabled) { color: var(--color-accent-text); }

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
  .identity-header { flex-direction: row; flex-wrap: wrap; }
  .id-steps { grid-template-columns: 1fr; gap: 12px; }
  .id-steps li::after { display: none; }
  .form-footer { flex-direction: column; align-items: stretch; }
  .form-footer > div { margin-left: 0; justify-content: flex-end; }
  .security-list li { flex-wrap: wrap; padding: 14px; gap: 10px 12px; }
  .security-main { flex-basis: calc(100% - 52px); }
  .security-list .state-badge { margin-left: 52px; }
  .security-list li > button { margin-left: auto; }
}
</style>
