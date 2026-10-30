<script setup>
import AppSelect from '../components/AppSelect.vue'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChevronDown, Eye, EyeOff, ShieldCheck, X, LoaderCircle, Sun, Moon } from 'lucide-vue-next'
import { auth } from '../stores/auth'
import { theme } from '../stores/theme'
import { accountError, codeError, passwordError, phoneError, safeRedirect } from '../utils/authValidation.js'
import { pendingInviteCode, rememberInviteCode } from '../utils/inviteCode'

const router = useRouter()
const route = useRoute()
const isRegister = computed(() => route.query.mode === 'register')
const inviteCode = ref(rememberInviteCode(route.query.invite) || pendingInviteCode())
const activeTab = ref('sms')
const form = reactive({ country: '+86', phone: '', code: '', account: '', password: '', agreed: false })
const errors = reactive({ phone: '', code: '', account: '', password: '', agreed: '' })
const busy = ref(false)
const sending = ref(false)
const showPassword = ref(false)
const notice = ref('')
const error = ref('')
const dialog = ref(null)
const smsTab = ref(null)
const passwordTab = ref(null)
const pageForm = ref(null)
const agreedInput = ref(null)
const actionBusy = computed(() => busy.value || sending.value || wechatBusy.value || wechatVerifying.value)
const wechatTicket = ref('')
const wechatBinding = ref(false)
const wechatNickname = ref('')
const wechatPasswordRequired = ref(false)
const wechatPhone = ref('')
const wechatCode = ref('')
const wechatPassword = ref('')
const wechatPasswordConfirm = ref('')
const wechatBusy = ref(false)
const wechatVerifying = ref(false)
const wechatSending = ref(false)
const wechatForm = ref(null)
const reset = reactive({ phone: '', code: '', password: '', confirm: '' })
const resetError = ref('')
const resetNotice = ref('')
const resetSending = ref(false)
const resetBusy = ref(false)
const resetForm = ref(null)

function clearMessages() {
  notice.value = ''
  error.value = ''
  Object.keys(errors).forEach(key => { errors[key] = '' })
}

function selectTab(tab, focus = false) {
  if (actionBusy.value) return
  activeTab.value = tab
  showPassword.value = false
  clearMessages()
  if (focus) nextTick(() => (tab === 'sms' ? smsTab : passwordTab).value?.focus())
}

function tabKeydown(event) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  selectTab(event.key === 'Home' ? 'sms' : event.key === 'End' ? 'password' : activeTab.value === 'sms' ? 'password' : 'sms', true)
}

async function toggleRegistration() {
  if (actionBusy.value) return
  await router.replace({ name: 'login', query: { ...route.query, mode: isRegister.value ? undefined : 'register' } })
}

watch(isRegister, () => selectTab('sms'))

onMounted(async () => {
  const fragment = new URLSearchParams(route.hash.slice(1))
  const ticket = fragment.get('wechat_ticket') || (typeof route.query.wechat_ticket === 'string' ? route.query.wechat_ticket : '')
  const wechatError = typeof route.query.wechat_error === 'string' ? route.query.wechat_error : ''
  if (wechatError) {
    error.value = wechatError
    await router.replace({ query: { ...route.query, wechat_error: undefined } })
    return
  }
  if (!ticket) return
  wechatVerifying.value = true
  wechatTicket.value = ticket
  await router.replace({ query: { ...route.query, wechat_ticket: undefined }, hash: '' })
  try {
    const result = await auth.completeWechat({ ticket })
    if (result?.tokenPair) {
      await router.replace(safeRedirect(route.query.redirect))
      return
    }
    wechatNickname.value = result.nickname || '微信用户'
    wechatPasswordRequired.value = result.passwordRequired === true
    wechatBinding.value = true
    wechatVerifying.value = false
    await nextTick()
    wechatForm.value?.querySelector('#wechat-phone')?.focus()
  } catch (err) {
    error.value = err.message || '微信登录失败，请重新扫码。'
  } finally {
    wechatVerifying.value = false
  }
})

async function focusInvalid() {
  await nextTick()
  pageForm.value?.querySelector('[aria-invalid="true"]')?.focus()
  if (errors.agreed && !Object.entries(errors).some(([key, value]) => key !== 'agreed' && value)) agreedInput.value?.focus()
}

function checkAgreement() {
  errors.agreed = form.agreed ? '' : '请先阅读并同意用户注册协议与隐私协议'
  if (errors.agreed) nextTick(() => agreedInput.value?.focus())
  return !errors.agreed
}

async function sendCode() {
  if (actionBusy.value || auth.state.cooldown > 0) return
  clearMessages()
  errors.phone = phoneError(form.phone, form.country)
  if (errors.phone) return focusInvalid()
  if (!checkAgreement()) return
  sending.value = true
  try {
    const result = await auth.sendCode(form.phone.trim())
    notice.value = result.developmentMode
      ? '当前为本地调试模式，不会发送短信。验证码请在后端控制台查看。'
      : '验证码短信已提交发送，请留意手机短信，5 分钟内有效。'
    await nextTick()
    pageForm.value?.querySelector('#login-code')?.focus()
  } catch (err) {
    error.value = err.message || '验证码发送失败，请稍后重试。'
  } finally {
    sending.value = false
  }
}

async function sendWechatCode() {
  if (actionBusy.value || wechatSending.value || auth.state.cooldown > 0) return
  clearMessages()
  const phoneMessage = phoneError(wechatPhone.value, '+86')
  if (phoneMessage) {
    error.value = phoneMessage
    return
  }
  if (!checkAgreement()) return
  wechatSending.value = true
  try {
    const result = await auth.sendCode(wechatPhone.value.trim())
    notice.value = result.developmentMode
      ? '当前为本地调试模式，验证码请在后端控制台查看。'
      : '验证码短信已发送，5 分钟内有效。'
    await nextTick()
    wechatForm.value?.querySelector('#wechat-code')?.focus()
  } catch (err) {
    error.value = err.message || '验证码发送失败，请稍后重试。'
  } finally {
    wechatSending.value = false
  }
}

async function completeWechatBinding() {
  if (actionBusy.value || wechatSending.value) return
  clearMessages()
  const phoneMessage = wechatPasswordRequired.value ? '' : phoneError(wechatPhone.value, '+86')
  const codeMessage = wechatPasswordRequired.value ? '' : codeError(wechatCode.value.trim())
  const passwordMessage = wechatPasswordRequired.value ? passwordError(wechatPassword.value) : ''
  if (phoneMessage || codeMessage || passwordMessage || (wechatPasswordRequired.value && wechatPassword.value !== wechatPasswordConfirm.value)) {
    error.value = phoneMessage || codeMessage || passwordMessage || '两次输入的密码不一致'
    return
  }
  if (!checkAgreement()) return
  wechatBusy.value = true
  try {
    const result = await auth.completeWechat({
      ticket: wechatTicket.value,
      ...(wechatPasswordRequired.value ? { password: wechatPassword.value } : {
        phone: wechatPhone.value.trim(),
        code: wechatCode.value.trim(),
      }),
    })
    if (result?.requiresBinding && result.passwordRequired) {
      wechatPasswordRequired.value = true
      wechatCode.value = ''
      notice.value = ''
      await nextTick()
      wechatForm.value?.querySelector('#wechat-password')?.focus()
      return
    }
    if (!result?.tokenPair) throw new Error('微信绑定未完成，请重新尝试。')
    await router.replace(safeRedirect(route.query.redirect))
  } catch (err) {
    error.value = err.message || '微信绑定失败，请稍后重试。'
  } finally {
    wechatBusy.value = false
  }
}

function cancelWechatBinding() {
  wechatBinding.value = false
  wechatTicket.value = ''
  wechatPhone.value = ''
  wechatCode.value = ''
  wechatPassword.value = ''
  wechatPasswordConfirm.value = ''
  wechatPasswordRequired.value = false
  clearMessages()
}

async function submit() {
  if (actionBusy.value) return
  clearMessages()
  if (activeTab.value === 'sms') {
    errors.phone = phoneError(form.phone, form.country)
    errors.code = codeError(form.code)
  } else {
    errors.account = accountError(form.account)
    errors.password = passwordError(form.password)
  }
  errors.agreed = form.agreed ? '' : '请先阅读并同意用户注册协议与隐私协议'
  if (Object.values(errors).some(Boolean)) return focusInvalid()
  busy.value = true
  try {
    if (activeTab.value === 'sms') await auth.login(form.phone.trim(), form.code)
    else await auth.loginWithPassword(form.account.trim(), form.password)
    await router.replace(safeRedirect(route.query.redirect))
  } catch (err) {
    error.value = err.message || '登录失败，请稍后重试。'
  } finally {
    busy.value = false
  }
}

function trustedLink(raw) {
  if (!raw) return null
  try {
    const url = new URL(raw, window.location.origin)
    return url.protocol === 'https:' || (url.origin === window.location.origin && url.protocol === 'http:') ? url.href : null
  } catch {
    return null
  }
}

function loginWithWechat() {
  clearMessages()
  if (!checkAgreement()) return
  const url = trustedLink(import.meta.env.VITE_WECHAT_LOGIN_URL || '/api/auth/wechat/start')
  if (!url) {
    error.value = '微信扫码登录暂未开放，请使用验证码登录。'
    return
  }
  const loginUrl = new URL(url, window.location.origin)
  loginUrl.searchParams.set('redirect', safeRedirect(route.query.redirect))
  window.location.assign(loginUrl.href)
}

function openDialog() {
  Object.assign(reset, { phone: phoneError(form.account) ? '' : form.account.trim(), code: '', password: '', confirm: '' })
  resetError.value = ''
  resetNotice.value = ''
  dialog.value?.showModal()
  nextTick(() => resetForm.value?.querySelector(reset.phone ? '#reset-code' : '#reset-phone')?.focus())
}

function closeDialog() {
  if (!resetBusy.value) dialog.value?.close()
}

async function sendResetCode() {
  if (resetSending.value || resetBusy.value || auth.state.cooldown > 0) return
  resetNotice.value = ''
  resetError.value = phoneError(reset.phone)
  if (resetError.value) return
  resetSending.value = true
  try {
    const result = await auth.sendCode(reset.phone.trim())
    resetNotice.value = result.developmentMode
      ? '当前为本地调试模式，验证码请在后端控制台查看。'
      : '验证码短信已发送，5 分钟内有效。'
    await nextTick()
    resetForm.value?.querySelector('#reset-code')?.focus()
  } catch (err) {
    resetError.value = err.message || '验证码发送失败，请稍后重试。'
  } finally {
    resetSending.value = false
  }
}

async function submitReset() {
  if (resetSending.value || resetBusy.value) return
  resetNotice.value = ''
  resetError.value = phoneError(reset.phone) || codeError(reset.code.trim()) || passwordError(reset.password)
    || (reset.password === reset.confirm ? '' : '两次输入的密码不一致')
  if (resetError.value) return
  resetBusy.value = true
  try {
    await auth.resetPassword(reset.phone.trim(), reset.code.trim(), reset.password)
  } catch (err) {
    resetError.value = err.message || '密码重置失败，请稍后重试。'
    return
  } finally {
    resetBusy.value = false
  }
  dialog.value?.close()
  selectTab('password')
  Object.assign(form, { account: reset.phone.trim(), password: '' })
  Object.assign(reset, { code: '', password: '', confirm: '' })
  notice.value = '密码已重置，请使用新密码登录。'
  await nextTick()
  pageForm.value?.querySelector('#login-password')?.focus()
}
</script>

<template>
  <main class="access-page">
    <section class="access-entry" aria-labelledby="access-title">
      <header class="access-header">
        <a class="access-brand" href="/login" aria-label="一方志首页">
          <span class="access-mark" aria-hidden="true" />
          <span><strong>一方志</strong><small>为每一方商家立传</small></span>
        </a>
        <div class="access-header-actions">
          <div class="access-register">
            <span>{{ isRegister ? '已有账号？' : '没有账号？' }}</span>
            <button type="button" :disabled="actionBusy" @click="toggleRegistration">{{ isRegister ? '立即登录' : '立即注册' }}</button>
          </div>
          <button class="access-theme-toggle" type="button" :aria-label="theme.isLight ? '切换到深色模式' : '切换到浅色模式'" :title="theme.isLight ? '切换到深色模式' : '切换到浅色模式'" @click="theme.toggle()">
            <component :is="theme.isLight ? Moon : Sun" :size="18" aria-hidden="true" />
          </button>
        </div>
      </header>
      <div class="access-form-area">
        <div v-if="!wechatBinding" class="access-intro">
          <h1 id="access-title">{{ isRegister ? '从一方烟火开始' : '欢迎回到一方志' }}</h1>
          <p>{{ isRegister ? '一个账号，记录门店的每一天。' : '记录一方水土，讲述万家故事。' }}</p>
        </div>
        <p v-if="wechatVerifying" class="access-message" role="status"><LoaderCircle class="access-spinner" :size="18" aria-hidden="true" /> 正在确认微信登录…</p>
        <form v-else-if="wechatBinding" ref="wechatForm" class="wechat-binding" novalidate :aria-busy="wechatBusy" aria-labelledby="wechat-title" @submit.prevent="completeWechatBinding">
          <p class="access-kicker">微信已验证<span /></p>
          <h2 id="wechat-title">{{ wechatPasswordRequired ? '设置登录密码' : '绑定手机号' }}</h2>
          <p>{{ wechatPasswordRequired ? `手机号 ${wechatPhone} 已验证。设置密码后，也可使用手机号和密码登录。` : `已确认微信账号「${wechatNickname}」。验证手机号后，将绑定已有账号或创建新账号。` }}</p>
          <div class="access-fields">
            <div v-if="!wechatPasswordRequired" class="access-field"><label for="wechat-phone">手机号</label><div class="access-input-shell"><input id="wechat-phone" v-model="wechatPhone" type="tel" inputmode="numeric" autocomplete="tel-national" maxlength="11" placeholder="请输入手机号" :disabled="wechatBusy || wechatSending" /></div></div>
            <div v-if="!wechatPasswordRequired" class="access-field"><label for="wechat-code">短信验证码</label><div class="access-input-shell"><input id="wechat-code" v-model="wechatCode" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="请输入 6 位验证码" :disabled="wechatBusy" /><button type="button" class="access-send" :disabled="wechatBusy || wechatSending || auth.state.cooldown > 0" @click="sendWechatCode">{{ wechatSending ? '发送中…' : auth.state.cooldown > 0 ? `${auth.state.cooldown}s 后重试` : '获取验证码' }}</button></div></div>
            <div v-if="wechatPasswordRequired" class="access-field"><label for="wechat-password">登录密码</label><div class="access-input-shell"><input id="wechat-password" v-model="wechatPassword" type="password" autocomplete="new-password" maxlength="64" placeholder="请输入 8–64 位密码" :disabled="wechatBusy" /></div></div>
            <div v-if="wechatPasswordRequired" class="access-field"><label for="wechat-password-confirm">确认登录密码</label><div class="access-input-shell"><input id="wechat-password-confirm" v-model="wechatPasswordConfirm" type="password" autocomplete="new-password" maxlength="64" placeholder="请再次输入密码" :disabled="wechatBusy" /></div></div>
          </div>
          <p v-if="error" class="access-message is-error" role="alert">{{ error }}</p>
          <p v-if="notice" class="access-message is-success" role="status">{{ notice }}</p>
          <button class="access-submit w-full" type="submit" :disabled="wechatBusy || wechatSending"><LoaderCircle v-if="wechatBusy" class="access-spinner" :size="18" aria-hidden="true" />{{ wechatBusy ? '正在处理…' : wechatPasswordRequired ? '完成设置并登录' : '验证手机号并继续' }}</button>
          <button class="wechat-back" type="button" :disabled="wechatBusy || wechatSending" @click="cancelWechatBinding">返回其他登录方式</button>
        </form>
        <div v-else-if="!isRegister" class="access-tabs" role="tablist" aria-label="登录方式" @keydown="tabKeydown">
          <span class="access-tab-indicator" :class="{ 'is-password': activeTab === 'password' }" aria-hidden="true" />
          <button id="sms-tab" ref="smsTab" type="button" role="tab" :aria-selected="activeTab === 'sms'" aria-controls="login-panel" :tabindex="activeTab === 'sms' ? 0 : -1" :disabled="actionBusy" @click="selectTab('sms')">验证码登录</button>
          <button id="password-tab" ref="passwordTab" type="button" role="tab" :aria-selected="activeTab === 'password'" aria-controls="login-panel" :tabindex="activeTab === 'password' ? 0 : -1" :disabled="actionBusy" @click="selectTab('password')">密码登录</button>
        </div>
        <p v-else class="access-registration-note">通过手机号验证，创建或登录你的账号。</p>
        <form v-if="!wechatBinding && !wechatVerifying" ref="pageForm" class="access-form" novalidate :aria-busy="busy" @submit.prevent="submit">
          <div id="login-panel" :role="isRegister ? 'group' : 'tabpanel'" :aria-labelledby="isRegister ? 'access-title' : `${activeTab}-tab`">
            <Transition name="access-fields" mode="out-in">
              <div v-if="activeTab === 'sms'" key="sms" class="access-fields">
                <div class="access-field">
                  <label for="login-phone">手机号</label>
                  <div class="access-input-shell" :class="{ 'has-error': errors.phone }">
                    <div class="access-country">
                      <AppSelect v-model="form.country" aria-label="国家或地区区号" :disabled="actionBusy">
                        <option value="+86">+86 中国大陆</option>
                        <option value="+852" disabled>+852 中国香港（暂未开放）</option>
                        <option value="+853" disabled>+853 中国澳门（暂未开放）</option>
                        <option value="+886" disabled>+886 中国台湾（暂未开放）</option>
                      </AppSelect>
                      <span aria-hidden="true">{{ form.country }}<ChevronDown :size="13" /></span>
                    </div>
                    <input id="login-phone" v-model="form.phone" class="focus:ring-2 focus:ring-brand-primary" name="phone" type="tel" inputmode="numeric" autocomplete="tel-national" placeholder="请输入手机号" :disabled="actionBusy" :aria-invalid="Boolean(errors.phone)" :aria-describedby="errors.phone ? 'phone-error' : undefined" @input="errors.phone = ''" @blur="form.phone && (errors.phone = phoneError(form.phone, form.country))" />
                  </div>
                  <p v-if="errors.phone" id="phone-error" class="access-field-error">{{ errors.phone }}</p>
                </div>
                <div class="access-field">
                  <label for="login-code">短信验证码</label>
                  <div class="access-input-shell" :class="{ 'has-error': errors.code }">
                    <input id="login-code" v-model="form.code" class="focus:ring-2 focus:ring-brand-primary" name="code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="请输入 6 位验证码" :disabled="busy" :aria-invalid="Boolean(errors.code)" :aria-describedby="errors.code ? 'code-error' : undefined" @input="errors.code = ''" />
                    <button type="button" class="access-send" :disabled="actionBusy || auth.state.cooldown > 0" @click="sendCode">{{ sending ? '发送中…' : auth.state.cooldown > 0 ? `${auth.state.cooldown}s 后重试` : '获取验证码' }}</button>
                  </div>
                  <p v-if="errors.code" id="code-error" class="access-field-error">{{ errors.code }}</p>
                </div>
              </div>
              <div v-else key="password" class="access-fields">
                <div class="access-field">
                  <label for="login-account">用户名 / 手机号</label>
                  <div class="access-input-shell" :class="{ 'has-error': errors.account }">
                    <input id="login-account" v-model="form.account" class="focus:ring-2 focus:ring-brand-primary" name="username" autocomplete="username" placeholder="请输入用户名或手机号" :disabled="busy" :aria-invalid="Boolean(errors.account)" :aria-describedby="errors.account ? 'account-error' : undefined" @input="errors.account = ''" />
                  </div>
                  <p v-if="errors.account" id="account-error" class="access-field-error">{{ errors.account }}</p>
                </div>
                <div class="access-field">
                  <div class="access-label-row"><label for="login-password">密码</label><button type="button" :disabled="actionBusy" @click="openDialog">忘记密码？</button></div>
                  <div class="access-input-shell" :class="{ 'has-error': errors.password }">
                    <input id="login-password" v-model="form.password" class="focus:ring-2 focus:ring-brand-primary" name="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入 8–64 位密码" :disabled="busy" :aria-invalid="Boolean(errors.password)" :aria-describedby="errors.password ? 'password-error' : undefined" @input="errors.password = ''" />
                    <button class="access-eye" type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" :aria-pressed="showPassword" @click="showPassword = !showPassword"><component :is="showPassword ? EyeOff : Eye" :size="18" aria-hidden="true" /></button>
                  </div>
                  <p v-if="errors.password" id="password-error" class="access-field-error">{{ errors.password }}</p>
                </div>
              </div>
            </Transition>
          </div>
          <p v-if="error" class="access-message is-error" role="alert">{{ error }}</p>
          <p v-if="notice" class="access-message is-success" role="status">{{ notice }}</p>
          <button class="access-submit w-full transition-colors disabled:cursor-not-allowed" type="submit" :disabled="actionBusy">
            <LoaderCircle v-if="busy" class="access-spinner" :size="18" aria-hidden="true" />
            {{ busy ? '正在登录…' : isRegister ? '注册并登录' : '登录' }}
          </button>
        </form>
        <div v-if="!wechatBinding && !wechatVerifying" class="access-divider"><span>其他登录方式</span></div>
        <button v-if="!wechatBinding && !wechatVerifying" type="button" class="access-wechat w-full transition-colors" :disabled="actionBusy" @click="loginWithWechat">
          <svg viewBox="0 0 28 24" width="25" height="23" aria-hidden="true"><path fill="#07C160" d="M11 1C5.5 1 1 4.5 1 8.8c0 2.5 1.5 4.8 3.8 6.2l-1 3.2 3.7-1.9c1.1.3 2.3.5 3.5.5h.5a7.2 7.2 0 0 1-.6-2.8c0-4.3 4.1-7.8 9.2-7.8h.5C19.2 3.2 15.5 1 11 1Z" /><path fill="#07C160" d="M27 14c0-3.6-3.5-6.5-7.8-6.5s-7.8 2.9-7.8 6.5 3.5 6.5 7.8 6.5c.9 0 1.8-.1 2.6-.4l3.1 1.6-.8-2.7c1.8-1.2 2.9-3 2.9-5Z" /><g fill="white"><circle cx="7.5" cy="6.9" r="1.15" /><circle cx="14.2" cy="6.9" r="1.15" /><circle cx="16.5" cy="12.4" r="1" /><circle cx="22" cy="12.4" r="1" /></g></svg>
          微信扫码登录
        </button>
        <div v-if="!wechatVerifying" class="access-consent">
          <div class="access-consent-row">
            <input id="login-agreed" ref="agreedInput" v-model="form.agreed" type="checkbox" :disabled="actionBusy" :aria-invalid="Boolean(errors.agreed)" :aria-describedby="errors.agreed ? 'agreement-error' : undefined" @change="errors.agreed = ''" />
            <div><label for="login-agreed">我已阅读并同意</label><RouterLink to="/policies/terms" target="_blank" rel="noopener noreferrer">《用户注册协议》</RouterLink><span>和</span><RouterLink to="/policies/privacy" target="_blank" rel="noopener noreferrer">《隐私协议》</RouterLink></div>
          </div>
          <p v-if="errors.agreed" id="agreement-error" class="access-field-error" role="alert">{{ errors.agreed }}</p>
          <p v-if="inviteCode" class="access-invite">通过邀请码 {{ inviteCode }} 注册。新账号会归属邀请人，邀请人能看到你打码后的手机号、注册时间和充值返利记录。</p>
        </div>
      </div>
      <footer class="access-footer"><ShieldCheck :size="14" aria-hidden="true" /><span>一方志 · 为每一方商家立传</span></footer>
    </section>
    <aside class="access-board" aria-label="一方水土，一方志">
      <div class="access-board-center">
        <span class="access-board-logo" role="img" aria-label="一方志 YIFANGZHI" />
        <span class="access-board-rule" aria-hidden="true" />
        <p class="access-board-line">一方水土，一方志。</p>
      </div>
      <p class="access-board-foot">记录一方水土，讲述万家故事</p>
    </aside>
    <dialog ref="dialog" class="access-dialog" aria-labelledby="access-dialog-title" @click="event => event.target === dialog && closeDialog()" @cancel="event => resetBusy && event.preventDefault()">
      <button class="access-dialog-close" type="button" aria-label="关闭" :disabled="resetBusy" @click="closeDialog"><X :size="20" /></button>
      <h2 id="access-dialog-title">找回密码</h2>
      <p>验证账号绑定的手机号后设置新密码，其他设备上的登录会同时退出。若手机号已停用，请联系平台管理员协助处理。</p>
      <form ref="resetForm" class="access-reset" novalidate :aria-busy="resetBusy" @submit.prevent="submitReset">
        <div class="access-fields">
          <div class="access-field"><label for="reset-phone">手机号</label><div class="access-input-shell"><input id="reset-phone" v-model="reset.phone" type="tel" inputmode="numeric" autocomplete="tel-national" maxlength="11" placeholder="请输入账号绑定的手机号" :disabled="resetBusy || resetSending" /></div></div>
          <div class="access-field"><label for="reset-code">短信验证码</label><div class="access-input-shell"><input id="reset-code" v-model="reset.code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="请输入 6 位验证码" :disabled="resetBusy" /><button type="button" class="access-send" :disabled="resetBusy || resetSending || auth.state.cooldown > 0" @click="sendResetCode">{{ resetSending ? '发送中…' : auth.state.cooldown > 0 ? `${auth.state.cooldown}s 后重试` : '获取验证码' }}</button></div></div>
          <div class="access-field"><label for="reset-password">新密码</label><div class="access-input-shell"><input id="reset-password" v-model="reset.password" type="password" autocomplete="new-password" maxlength="64" placeholder="请输入 8–64 位密码" :disabled="resetBusy" /></div></div>
          <div class="access-field"><label for="reset-confirm">确认新密码</label><div class="access-input-shell"><input id="reset-confirm" v-model="reset.confirm" type="password" autocomplete="new-password" maxlength="64" placeholder="请再次输入新密码" :disabled="resetBusy" /></div></div>
        </div>
        <p v-if="resetError" class="access-message is-error" role="alert">{{ resetError }}</p>
        <p v-if="resetNotice" class="access-message is-success" role="status">{{ resetNotice }}</p>
        <button class="access-submit w-full" type="submit" :disabled="resetBusy || resetSending"><LoaderCircle v-if="resetBusy" class="access-spinner" :size="18" aria-hidden="true" />{{ resetBusy ? '正在重置…' : '重置密码' }}</button>
      </form>
    </dialog>
  </main>
</template>

<style scoped src="../login.css"></style>
