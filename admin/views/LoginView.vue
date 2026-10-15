<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Eye, EyeOff, LoaderCircle, ShieldCheck } from 'lucide-vue-next'
import { auth } from '../../src/stores/auth.js'
import { accountError, passwordError, phoneError, codeError } from '../../src/utils/authValidation.js'
import { adminDestination } from '../router.js'

const route = useRoute()
const router = useRouter()
const account = ref('')
const password = ref('')
const code = ref('')
const mode = ref('sms')
const sending = ref(false)
const notice = ref('')
const showPassword = ref(false)
const busy = ref(false)
const error = ref('')
const accountInput = ref(null)
const passwordInput = ref(null)

function selectMode(value) {
  mode.value = value
  error.value = ''; notice.value = ''; password.value = ''; code.value = ''
}

async function sendCode() {
  if (busy.value || sending.value || auth.state.cooldown > 0) return
  error.value = phoneError(account.value)
  notice.value = ''
  if (error.value) { accountInput.value?.focus(); return }
  sending.value = true
  try {
    await auth.sendCode(account.value.trim())
    notice.value = '验证码已发送，请使用该手机号收到的验证码登录。'
  } catch (failure) {
    error.value = failure.message || '验证码发送失败，请稍后重试。'
  } finally { sending.value = false }
}

async function submit() {
  if (busy.value || sending.value) return
  notice.value = ''
  error.value = mode.value === 'sms'
    ? phoneError(account.value) || codeError(code.value)
    : accountError(account.value.trim()) || passwordError(password.value)
  if (error.value) {
    if (accountError(account.value.trim())) accountInput.value?.focus()
    else passwordInput.value?.focus()
    return
  }
  busy.value = true
  try {
    if (mode.value === 'sms') await auth.login(account.value.trim(), code.value.trim())
    else await auth.loginWithPassword(account.value.trim(), password.value)
    password.value = ''
    code.value = ''
    await router.replace(adminDestination(route.query.redirect))
  } catch (failure) {
    error.value = ['NETWORK_ERROR', 'HTTP_ERROR', 'BAD_ENVELOPE'].includes(failure.code)
      ? '暂时无法连接管理服务，请确认后端已启动后重试。'
      : failure.message || '登录失败，请重新尝试。'
  } finally { busy.value = false }
}
</script>

<template>
  <div class="admin-access">
    <header class="admin-access-header"><span class="admin-brand-mark"><ShieldCheck :size="21" /></span><strong>管理后台</strong><span>内部运营</span></header>
    <main class="admin-login" aria-labelledby="admin-login-title">
      <div class="admin-login-kicker"><span />管理员入口</div>
      <h1 id="admin-login-title">登录管理后台</h1>
      <p class="admin-login-intro">使用已获授权的管理员账号，处理平台信息与审核。</p>
      <div class="admin-login-methods" aria-label="管理员登录方式"><button type="button" :class="{ active: mode === 'sms' }" :aria-pressed="mode === 'sms'" :disabled="busy || sending" @click="selectMode('sms')">验证码登录</button><button type="button" :class="{ active: mode === 'password' }" :aria-pressed="mode === 'password'" :disabled="busy || sending" @click="selectMode('password')">密码登录</button></div>
      <form class="admin-login-form" :aria-busy="busy" novalidate @submit.prevent="submit">
        <label for="admin-account">{{ mode === 'sms' ? '管理员手机号' : '管理员账号' }}</label>
        <input id="admin-account" ref="accountInput" v-model="account" name="username" :type="mode === 'sms' ? 'tel' : 'text'" :inputmode="mode === 'sms' ? 'tel' : 'text'" autocomplete="username" :placeholder="mode === 'sms' ? '请输入已授权手机号' : '请输入用户名或手机号'" maxlength="64" :disabled="busy || sending" required />
        <template v-if="mode === 'password'"><label for="admin-password">登录密码</label><div class="admin-password"><input id="admin-password" ref="passwordInput" v-model="password" name="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入登录密码" maxlength="64" :disabled="busy" required /><button type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" :aria-pressed="showPassword" @click="showPassword = !showPassword"><component :is="showPassword ? EyeOff : Eye" :size="17" /></button></div></template>
        <template v-else><label for="admin-code">短信验证码</label><div class="admin-password"><input id="admin-code" ref="passwordInput" v-model="code" name="code" inputmode="numeric" autocomplete="one-time-code" placeholder="请输入 6 位验证码" maxlength="6" :disabled="busy" required /><button class="admin-send-code" type="button" :disabled="busy || sending || auth.state.cooldown > 0" @click="sendCode">{{ sending ? '发送中…' : auth.state.cooldown > 0 ? `${auth.state.cooldown}s 后重试` : '获取验证码' }}</button></div></template>
        <p v-if="error" class="admin-login-error" role="alert">{{ error }}</p>
        <p v-if="notice" class="admin-login-notice" role="status">{{ notice }}</p>
        <button class="admin-login-submit" type="submit" :disabled="busy || sending"><LoaderCircle v-if="busy" :size="17" class="admin-spinning" />{{ busy ? '正在验证账号与权限…' : '登录后台' }}<ArrowRight v-if="!busy" :size="17" /></button>
      </form>
      <div class="admin-login-policy"><ShieldCheck :size="16" /><p>仅限平台管理员访问。账号开通、权限变更或密码重置，请联系后台负责人。</p></div>
      <footer class="admin-login-footer"><span>信息管理</span><i /><span>商家档案</span><i /><span>实名审核</span></footer>
    </main>
    <p class="admin-access-footer">内部管理系统 · 请妥善保管账号</p>
  </div>
</template>
