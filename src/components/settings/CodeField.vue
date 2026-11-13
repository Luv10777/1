<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { authService } from '../../services/auth.js'

// 短信验证码输入框。倒计时按号码各算各的：先给原手机号发过，换新号时不用再等一分钟。
const props = defineProps({
  modelValue: { type: String, default: '' },
  phone: { type: String, default: '' },
  id: { type: String, required: true },
  label: { type: String, default: '短信验证码' },
  disabled: { type: Boolean, default: false },
  /** 发送前的检查，返回错误文字就不发 */
  validate: { type: Function, default: null },
})
const emit = defineEmits(['update:modelValue'])

const sending = ref(false)
const cooldown = ref(0)
const message = ref({ text: '', error: false })
const input = ref(null)
let timer = null
onBeforeUnmount(() => clearInterval(timer))

const masked = phone => phone.replace(/^(\d{3})\d{4}(\d{4})$/, '$1 **** $2')

async function send() {
  if (sending.value || cooldown.value > 0 || props.disabled) return
  const problem = props.validate?.()
  if (problem) { message.value = { text: problem, error: true }; return }
  sending.value = true
  message.value = { text: '', error: false }
  try {
    const result = await authService.sendCode(props.phone)
    cooldown.value = result?.retryAfterSeconds || 60
    clearInterval(timer)
    timer = setInterval(() => { cooldown.value -= 1; if (cooldown.value <= 0) clearInterval(timer) }, 1000)
    message.value = {
      text: result?.developmentMode ? '本地调试模式不发短信，验证码在后端控制台查看。' : `验证码已发到 ${masked(props.phone)}，5 分钟内有效。`,
      error: false,
    }
    input.value?.focus()
  } catch (error) {
    message.value = { text: error.message || '验证码发送失败，请稍后重试', error: true }
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <div class="sd-field">
    <label :for="id">{{ label }}</label>
    <div class="sd-code">
      <div class="sd-input">
        <input :id="id" ref="input" :value="modelValue" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="6 位数字" :disabled="disabled" @input="emit('update:modelValue', $event.target.value.replace(/\D/g, ''))" />
      </div>
      <button type="button" class="sd-btn" :disabled="disabled || sending || cooldown > 0" @click="send">
        {{ sending ? '发送中…' : cooldown > 0 ? `${cooldown} 秒后重发` : '获取验证码' }}
      </button>
    </div>
    <p v-if="message.text" class="sd-hint" :class="message.error ? 'error' : 'ok'" :role="message.error ? 'alert' : 'status'">{{ message.text }}</p>
    <slot v-else name="hint" />
  </div>
</template>
