<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const form = reactive({ phone: '', code: '' })
const busy = ref(false)
const notice = ref('')
const error = ref('')

const sendCode = async () => {
  notice.value = ''
  error.value = ''
  try {
    await auth.sendCode(form.phone)
    notice.value = '验证码已发送，演示环境可填写任意 6 位数字。'
  } catch (err) {
    error.value = err.message
  }
}

const submit = async () => {
  busy.value = true
  notice.value = ''
  error.value = ''
  try {
    await auth.login(form.phone, form.code)
    router.replace(typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard')
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-orbit orbit-one" />
    <div class="auth-orbit orbit-two" />
    <section class="auth-panel">
      <div class="auth-brand">
        <div class="brand-mark large" aria-hidden="true"><span /><span /><span /></div>
        <div><p class="brand-name">梧曜星枢</p><p class="brand-caption">AI GROWTH OS <span>·</span> V0.1</p></div>
      </div>
      <div class="auth-copy">
        <p class="eyebrow accent">WELCOME BACK / 01</p>
        <h1>让每一家门店，<br /><em>持续长出</em>好内容。</h1>
        <p>连接品牌、素材与 AI 模型，把增长动作变成每天都能执行的工作流。</p>
      </div>
      <form class="login-form" @submit.prevent="submit">
        <div class="field">
          <label for="phone">手机号</label>
          <div class="input-wrap"><span class="input-prefix">+86</span><input id="phone" v-model="form.phone" type="tel" inputmode="numeric" autocomplete="tel" placeholder="请输入手机号" /></div>
        </div>
        <div class="field">
          <label for="code">验证码</label>
          <div class="input-wrap"><input id="code" v-model="form.code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="6 位验证码" /><button type="button" class="send-code" :disabled="auth.state.cooldown > 0" @click="sendCode">{{ auth.state.cooldown > 0 ? `${auth.state.cooldown}s 后重试` : '获取验证码' }}</button></div>
        </div>
        <p v-if="error" class="form-message error">{{ error }}</p>
        <p v-if="notice" class="form-message success">{{ notice }}</p>
        <button class="primary-button login-submit" type="submit" :disabled="busy">{{ busy ? '正在进入…' : '进入梧曜星枢' }}<span>→</span></button>
      </form>
      <div class="auth-footnote"><span class="status-pulse" /> 当前为 Mock 认证模式 <span class="auth-footnote-separator">·</span> 数据仅用于演示</div>
      <p class="legal-copy">登录即代表你同意《用户协议》和《隐私政策》<br /><span>需要帮助？联系平台管理员</span></p>
    </section>
    <aside class="auth-aside">
      <div class="aside-topline"><span class="mono">SYS / READY</span><span class="live-label"><span class="status-pulse" /> LIVE PREVIEW</span></div>
      <div class="signal-visual" aria-hidden="true">
        <div class="signal-ring ring-large" /><div class="signal-ring ring-mid" /><div class="signal-ring ring-small" />
        <div class="signal-core"><span>W</span><small>星枢</small></div>
        <span class="signal-node node-a" /><span class="signal-node node-b" /><span class="signal-node node-c" />
      </div>
      <div class="aside-bottom"><p class="eyebrow">运营系统 / 01</p><h2>把灵感，变成<br /><span>可复用的增长节奏。</span></h2><div class="aside-meta"><span class="mono">TENANT_001</span><span>杭州 · 中国</span></div></div>
    </aside>
  </div>
</template>
