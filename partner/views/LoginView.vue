<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, LoaderCircle, Store } from 'lucide-vue-next'
import { auth } from '../../src/stores/auth.js'
import { accountError, codeError, passwordError, phoneError } from '../../src/utils/authValidation.js'
import { partnerDestination } from '../router.js'

const route = useRoute()
const router = useRouter()
const account = ref('')
const password = ref('')
const code = ref('')
const mode = ref('sms')
const sending = ref(false)
const busy = ref(false)
const notice = ref('')
const error = ref('')

function selectMode(value) {
  mode.value = value
  error.value = ''; notice.value = ''; password.value = ''; code.value = ''
}

async function sendCode() {
  if (busy.value || sending.value || auth.state.cooldown > 0) return
  error.value = phoneError(account.value)
  notice.value = ''
  if (error.value) return
  sending.value = true
  try {
    await auth.sendCode(account.value.trim())
    notice.value = '验证码已发送。'
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
  if (error.value) return
  busy.value = true
  try {
    if (mode.value === 'sms') await auth.login(account.value.trim(), code.value.trim())
    else await auth.loginWithPassword(account.value.trim(), password.value)
    password.value = ''; code.value = ''
    await router.replace(partnerDestination(route.query.redirect))
  } catch (failure) {
    error.value = ['NETWORK_ERROR', 'HTTP_ERROR', 'BAD_ENVELOPE'].includes(failure.code)
      ? '暂时无法连接服务，请稍后重试。'
      : failure.message || '登录失败，请重新尝试。'
  } finally { busy.value = false }
}
</script>

<template>
  <div class="admin-access">
    <header class="admin-access-header"><span class="admin-brand-mark"><Store :size="19" /></span><strong>服务商后台</strong><span>一方志</span></header>
    <main class="admin-login" aria-labelledby="partner-login-title">
      <div class="admin-login-kicker"><span />服务商入口</div>
      <h1 id="partner-login-title">登录服务商后台</h1>
      <p class="admin-login-intro">使用平台为你开通的一方志账号登录，管理名下商户、烟火和激活码。</p>
      <div class="admin-login-methods" aria-label="登录方式"><button type="button" :class="{ active: mode === 'sms' }" :aria-pressed="mode === 'sms'" :disabled="busy || sending" @click="selectMode('sms')">验证码登录</button><button type="button" :class="{ active: mode === 'password' }" :aria-pressed="mode === 'password'" :disabled="busy || sending" @click="selectMode('password')">密码登录</button></div>
      <form class="admin-login-form" :aria-busy="busy" novalidate @submit.prevent="submit">
        <label for="partner-account">{{ mode === 'sms' ? '手机号' : '账号' }}</label>
        <input id="partner-account" v-model="account" name="username" :type="mode === 'sms' ? 'tel' : 'text'" :inputmode="mode === 'sms' ? 'tel' : 'text'" autocomplete="username" :placeholder="mode === 'sms' ? '请输入手机号' : '手机号或用户名'" :disabled="busy" />
        <template v-if="mode === 'password'"><label for="partner-password">登录密码</label><div class="admin-password"><input id="partner-password" v-model="password" name="password" type="password" autocomplete="current-password" placeholder="请输入密码" :disabled="busy" /></div></template>
        <template v-else><label for="partner-code">短信验证码</label><div class="admin-password"><input id="partner-code" v-model="code" name="code" inputmode="numeric" autocomplete="one-time-code" placeholder="请输入 6 位验证码" maxlength="6" :disabled="busy" /><button type="button" class="admin-send-code" :disabled="busy || sending || auth.state.cooldown > 0" @click="sendCode">{{ auth.state.cooldown > 0 ? `${auth.state.cooldown} 秒` : sending ? '发送中…' : '获取验证码' }}</button></div></template>
        <p v-if="error" class="admin-login-error" role="alert">{{ error }}</p>
        <p v-if="notice" class="admin-login-notice" role="status">{{ notice }}</p>
        <button class="admin-login-submit" type="submit" :disabled="busy || sending"><LoaderCircle v-if="busy" :size="17" class="admin-spinning" />{{ busy ? '正在登录…' : '登录' }}<ArrowRight v-if="!busy" :size="17" /></button>
      </form>
    </main>
    <p class="admin-access-footer">服务商账号由一方志平台开通</p>
  </div>
</template>
