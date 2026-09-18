<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, ArrowUpRight, ChevronDown, Eye, EyeOff, LockKeyhole, ShieldCheck, Sparkles, Workflow, ChartNoAxesCombined, X, LoaderCircle } from 'lucide-vue-next'
import { auth } from '../stores/auth'
import { accountError, codeError, passwordError, phoneError, safeRedirect } from '../utils/authValidation.js'

const router = useRouter()
const route = useRoute()
const isRegister = computed(() => route.query.mode === 'register')
const activeTab = ref('sms')
const form = reactive({ country: '+86', phone: '', code: '', account: '', password: '', agreed: false })
const errors = reactive({ phone: '', code: '', account: '', password: '', agreed: '' })
const busy = ref(false)
const sending = ref(false)
const showPassword = ref(false)
const notice = ref('')
const error = ref('')
const dialog = ref(null)
const dialogKind = ref('')
const dialogTitle = computed(() => ({ forgot: '找回账号访问权限', terms: '用户协议', privacy: '隐私政策' })[dialogKind.value])
const smsTab = ref(null)
const passwordTab = ref(null)
const pageForm = ref(null)
const agreedInput = ref(null)
const actionBusy = computed(() => busy.value || sending.value)
const legalUrls = { terms: import.meta.env.VITE_TERMS_URL, privacy: import.meta.env.VITE_PRIVACY_URL }

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

async function focusInvalid() {
  await nextTick()
  pageForm.value?.querySelector('[aria-invalid="true"]')?.focus()
  if (errors.agreed && !Object.entries(errors).some(([key, value]) => key !== 'agreed' && value)) agreedInput.value?.focus()
}

function checkAgreement() {
  errors.agreed = form.agreed ? '' : '请先阅读并勾选用户协议与隐私政策'
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
  errors.agreed = form.agreed ? '' : '请先阅读并勾选用户协议与隐私政策'
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
  const url = trustedLink(import.meta.env.VITE_WECHAT_LOGIN_URL)
  if (!url) {
    error.value = '微信登录暂未开放，请使用验证码登录。'
    return
  }
  window.location.assign(url)
}

function openDialog(kind) {
  if (legalUrls[kind]) {
    const url = trustedLink(legalUrls[kind])
    if (url) {
      window.open(url, '_blank', 'noopener,noreferrer')
      return
    }
  }
  dialogKind.value = kind
  dialog.value?.showModal()
}

function useSmsInstead() {
  dialog.value?.close()
  selectTab('sms', true)
  notice.value = '使用已绑定手机号接收验证码，即可登录账号。'
}
</script>

<template>
  <main class="access-page">
    <section class="access-entry" aria-labelledby="access-title">
      <header class="access-header">
        <a class="access-brand" href="/login" aria-label="梧曜星枢首页">
          <span class="access-mark" aria-hidden="true"><i /><i /><i /></span>
          <span><strong>梧曜星枢</strong><small>AI GROWTH OS</small></span>
        </a>
        <div class="access-register">
          <span>{{ isRegister ? '已有账号？' : '没有账号？' }}</span>
          <button type="button" :disabled="actionBusy" @click="toggleRegistration">{{ isRegister ? '立即登录' : '立即注册' }}<ArrowUpRight :size="13" aria-hidden="true" /></button>
        </div>
      </header>
      <div class="access-form-area">
        <div class="access-intro">
          <p class="access-kicker">{{ isRegister ? 'YOUR NEXT CHAPTER' : 'WELCOME BACK' }}<span /></p>
          <h1 id="access-title">{{ isRegister ? '开启你的增长之旅' : '欢迎回到梧曜星枢' }}</h1>
          <p>{{ isRegister ? '一个账号，连接灵感与增长的每一步。' : '连接你的灵感，让增长从这里发生。' }}</p>
        </div>
        <div v-if="!isRegister" class="access-tabs" role="tablist" aria-label="登录方式" @keydown="tabKeydown">
          <span class="access-tab-indicator" :class="{ 'is-password': activeTab === 'password' }" aria-hidden="true" />
          <button id="sms-tab" ref="smsTab" type="button" role="tab" :aria-selected="activeTab === 'sms'" aria-controls="login-panel" :tabindex="activeTab === 'sms' ? 0 : -1" :disabled="actionBusy" @click="selectTab('sms')">验证码登录</button>
          <button id="password-tab" ref="passwordTab" type="button" role="tab" :aria-selected="activeTab === 'password'" aria-controls="login-panel" :tabindex="activeTab === 'password' ? 0 : -1" :disabled="actionBusy" @click="selectTab('password')">密码登录</button>
        </div>
        <p v-else class="access-registration-note">通过手机号验证，创建或登录你的账号。</p>
        <form ref="pageForm" class="access-form" novalidate :aria-busy="busy" @submit.prevent="submit">
          <div id="login-panel" :role="isRegister ? 'group' : 'tabpanel'" :aria-labelledby="isRegister ? 'access-title' : `${activeTab}-tab`">
            <Transition name="access-fields" mode="out-in">
              <div v-if="activeTab === 'sms'" key="sms" class="access-fields">
                <div class="access-field">
                  <label for="login-phone">手机号</label>
                  <div class="access-input-shell" :class="{ 'has-error': errors.phone }">
                    <div class="access-country">
                      <select v-model="form.country" aria-label="国家或地区区号" :disabled="actionBusy">
                        <option value="+86">+86 中国大陆</option>
                        <option value="+852" disabled>+852 中国香港（暂未开放）</option>
                        <option value="+853" disabled>+853 中国澳门（暂未开放）</option>
                        <option value="+886" disabled>+886 中国台湾（暂未开放）</option>
                      </select>
                      <span aria-hidden="true">{{ form.country }}<ChevronDown :size="13" /></span>
                    </div>
                    <input id="login-phone" v-model="form.phone" class="focus:ring-2 focus:ring-blue-500" name="phone" type="tel" inputmode="numeric" autocomplete="tel-national" placeholder="请输入手机号" :disabled="actionBusy" :aria-invalid="Boolean(errors.phone)" :aria-describedby="errors.phone ? 'phone-error' : undefined" @input="errors.phone = ''" @blur="form.phone && (errors.phone = phoneError(form.phone, form.country))" />
                  </div>
                  <p v-if="errors.phone" id="phone-error" class="access-field-error">{{ errors.phone }}</p>
                </div>
                <div class="access-field">
                  <label for="login-code">短信验证码</label>
                  <div class="access-input-shell" :class="{ 'has-error': errors.code }">
                    <input id="login-code" v-model="form.code" class="focus:ring-2 focus:ring-blue-500" name="code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="请输入 6 位验证码" :disabled="busy" :aria-invalid="Boolean(errors.code)" :aria-describedby="errors.code ? 'code-error' : undefined" @input="errors.code = ''" />
                    <button type="button" class="access-send" :disabled="actionBusy || auth.state.cooldown > 0" @click="sendCode">{{ sending ? '发送中…' : auth.state.cooldown > 0 ? `${auth.state.cooldown}s 后重试` : '获取验证码' }}</button>
                  </div>
                  <p v-if="errors.code" id="code-error" class="access-field-error">{{ errors.code }}</p>
                </div>
              </div>
              <div v-else key="password" class="access-fields">
                <div class="access-field">
                  <label for="login-account">用户名 / 手机号</label>
                  <div class="access-input-shell" :class="{ 'has-error': errors.account }">
                    <input id="login-account" v-model="form.account" class="focus:ring-2 focus:ring-blue-500" name="username" autocomplete="username" placeholder="请输入用户名或手机号" :disabled="busy" :aria-invalid="Boolean(errors.account)" :aria-describedby="errors.account ? 'account-error' : undefined" @input="errors.account = ''" />
                  </div>
                  <p v-if="errors.account" id="account-error" class="access-field-error">{{ errors.account }}</p>
                </div>
                <div class="access-field">
                  <div class="access-label-row"><label for="login-password">密码</label><button type="button" @click="openDialog('forgot')">忘记密码？</button></div>
                  <div class="access-input-shell" :class="{ 'has-error': errors.password }">
                    <input id="login-password" v-model="form.password" class="focus:ring-2 focus:ring-blue-500" name="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入 8–64 位密码" :disabled="busy" :aria-invalid="Boolean(errors.password)" :aria-describedby="errors.password ? 'password-error' : undefined" @input="errors.password = ''" />
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
            <ArrowRight v-if="!busy" :size="18" aria-hidden="true" />
          </button>
        </form>
        <div class="access-divider"><span>其他登录方式</span></div>
        <button type="button" class="access-wechat w-full transition-colors" :disabled="actionBusy" @click="loginWithWechat">
          <svg viewBox="0 0 28 24" width="25" height="23" aria-hidden="true"><path fill="#07C160" d="M11 1C5.5 1 1 4.5 1 8.8c0 2.5 1.5 4.8 3.8 6.2l-1 3.2 3.7-1.9c1.1.3 2.3.5 3.5.5h.5a7.2 7.2 0 0 1-.6-2.8c0-4.3 4.1-7.8 9.2-7.8h.5C19.2 3.2 15.5 1 11 1Z" /><path fill="#07C160" d="M27 14c0-3.6-3.5-6.5-7.8-6.5s-7.8 2.9-7.8 6.5 3.5 6.5 7.8 6.5c.9 0 1.8-.1 2.6-.4l3.1 1.6-.8-2.7c1.8-1.2 2.9-3 2.9-5Z" /><g fill="white"><circle cx="7.5" cy="6.9" r="1.15" /><circle cx="14.2" cy="6.9" r="1.15" /><circle cx="16.5" cy="12.4" r="1" /><circle cx="22" cy="12.4" r="1" /></g></svg>
          微信授权登录
        </button>
        <div class="access-consent">
          <div class="access-consent-row">
            <input id="login-agreed" ref="agreedInput" v-model="form.agreed" type="checkbox" :disabled="actionBusy" :aria-invalid="Boolean(errors.agreed)" :aria-describedby="errors.agreed ? 'agreement-error' : undefined" @change="errors.agreed = ''" />
            <div><label for="login-agreed">我已阅读并同意</label><button type="button" @click="openDialog('terms')">《用户协议》</button><span>和</span><button type="button" @click="openDialog('privacy')">《隐私政策》</button></div>
          </div>
          <p v-if="errors.agreed" id="agreement-error" class="access-field-error" role="alert">{{ errors.agreed }}</p>
        </div>
      </div>
      <footer class="access-footer"><ShieldCheck :size="14" aria-hidden="true" /><span>梧曜星枢 · 让每一次创作，成为增长的起点</span></footer>
    </section>
    <aside class="access-board hidden md:flex" aria-label="梧曜星枢 AI 增长操作系统">
      <div class="access-board-grid" aria-hidden="true" />
      <div class="access-board-top"><span><i /> 灵感有迹，增长有序</span><span class="access-board-index">WU YAO / 01</span></div>
      <div class="access-board-content">
        <div class="access-network" aria-hidden="true">
          <div class="access-orbit orbit-outer" /><div class="access-orbit orbit-middle" /><div class="access-orbit orbit-inner" />
          <div class="access-network-axis axis-horizontal" /><div class="access-network-axis axis-vertical" />
          <svg class="access-connections" viewBox="0 0 520 370" fill="none"><path d="M108 112H176L260 185M412 115H347L260 185M260 185L337 278H403M260 185L182 278H110" stroke="#4987DB" stroke-opacity=".5" stroke-dasharray="4 6" /><circle cx="176" cy="112" r="3" fill="#83B7FF" /><circle cx="337" cy="278" r="3" fill="#83B7FF" /></svg>
          <div class="access-core-halo" />
          <div class="access-core"><span class="access-core-symbol"><i /><i /><i /></span><strong>星枢</strong><small>GROWTH ENGINE</small></div>
          <div class="access-node node-inspiration"><span><Sparkles :size="18" /></span><div><strong>捕捉灵感</strong><small>INSPIRATION</small></div></div>
          <div class="access-node node-content"><span><Workflow :size="18" /></span><div><strong>内容共创</strong><small>CREATION</small></div></div>
          <div class="access-node node-growth"><span><ChartNoAxesCombined :size="18" /></span><div><strong>持续增长</strong><small>GROWTH</small></div></div>
          <span class="access-orbit-dot dot-one" /><span class="access-orbit-dot dot-two" /><span class="access-orbit-dot dot-three" />
          <span class="access-coordinate">IDEA → IMPACT</span>
        </div>
        <div class="access-board-copy"><p>从一个灵感，到每一次增长</p><h2>把灵感，变成<br /><span>可复用的增长节奏。</span></h2><p class="access-board-description">让品牌、内容与 AI 协同，<br class="access-tablet-break" />把每一个好想法，变成持续发生的行动。</p></div>
        <div class="access-board-features"><span>品牌资产</span><i /><span>智能创作</span><i /><span>增长协同</span></div>
      </div>
      <div class="access-board-bottom"><span>YOUR IDEAS. CONNECTED.</span><span>梧曜星枢 <ArrowUpRight :size="13" /></span></div>
    </aside>
    <dialog ref="dialog" class="access-dialog" aria-labelledby="access-dialog-title" @click="event => event.target === dialog && dialog.close()">
      <button class="access-dialog-close" type="button" aria-label="关闭" @click="dialog.close()"><X :size="20" /></button>
      <LockKeyhole :size="25" class="access-dialog-icon" aria-hidden="true" />
      <h2 id="access-dialog-title">{{ dialogTitle }}</h2>
      <template v-if="dialogKind === 'forgot'">
        <p>忘记密码也可以通过已绑定的手机号登录。若手机号已停用，请联系平台管理员协助处理。</p>
        <button class="access-submit w-full" type="button" @click="useSmsInstead">使用验证码登录<ArrowRight :size="18" /></button>
      </template>
      <template v-else><p>{{ dialogTitle }}全文暂未发布，请联系平台管理员获取并阅读后再继续。</p><button class="access-submit w-full" type="button" @click="dialog.close()">我知道了</button></template>
    </dialog>
  </main>
</template>

<style scoped src="../login.css"></style>
