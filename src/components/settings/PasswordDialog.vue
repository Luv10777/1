<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { Eye, EyeOff, Info } from 'lucide-vue-next'
import SettingsDialog from './SettingsDialog.vue'
import CodeField from './CodeField.vue'
import { accountApi } from '../../services/accountApi.js'

// 设置或修改登录密码。已设过密码的人默认用当前密码验证，也能改用短信；没设过的只能用短信。
const props = defineProps({
  open: { type: Boolean, required: true },
  profile: { type: Object, required: true },
})
const emit = defineEmits(['close', 'saved'])

const form = reactive({ currentPassword: '', code: '', newPassword: '', confirmPassword: '' })
const byCode = ref(false)
const reveal = ref(false)
const saving = ref(false)
const error = ref('')

const first = computed(() => !props.profile.passwordSet)
const canUseCode = computed(() => Boolean(props.profile.phone))
const mismatch = computed(() => form.confirmPassword && form.confirmPassword !== form.newPassword)
const lengthHint = computed(() => {
  const size = form.newPassword.length
  if (!size) return '8–64 位，建议字母、数字混用'
  return size < 8 ? `还差 ${8 - size} 位` : size > 64 ? '最多 64 位' : '长度可以'
})

watch(() => props.open, open => {
  if (!open) return
  Object.assign(form, { currentPassword: '', code: '', newPassword: '', confirmPassword: '' })
  byCode.value = first.value
  reveal.value = false
  error.value = ''
})

function useCode(value) {
  byCode.value = value
  error.value = ''
}

async function save() {
  if (saving.value) return
  error.value = ''
  saving.value = true
  try {
    const session = await accountApi.changePassword(form, byCode.value)
    emit('saved', { session, first: first.value })
  } catch (failure) {
    error.value = failure.message || '保存失败，请重试'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <SettingsDialog :open="open" :busy="saving" :title="first ? '设置登录密码' : '修改登录密码'" :description="first ? '设置后可以用手机号加密码登录，不用每次收验证码。' : '先验证身份，再输入新密码。'" @close="emit('close')">
    <form novalidate @submit.prevent="save">
      <div class="sd-body">
        <div v-if="!first && canUseCode" class="sd-segment" role="group" aria-label="验证方式">
          <button type="button" :aria-pressed="!byCode" :disabled="saving" @click="useCode(false)">用当前密码</button>
          <button type="button" :aria-pressed="byCode" :disabled="saving" @click="useCode(true)">用短信验证码</button>
        </div>

        <CodeField v-if="byCode" id="password-code" v-model="form.code" :phone="profile.phone" :disabled="saving" />
        <div v-else class="sd-field">
          <label for="password-current">当前密码</label>
          <div class="sd-input"><input id="password-current" v-model="form.currentPassword" :type="reveal ? 'text' : 'password'" autocomplete="current-password" maxlength="64" :disabled="saving" /></div>
        </div>

        <div class="sd-field">
          <label for="password-new">新密码</label>
          <div class="sd-input">
            <input id="password-new" v-model="form.newPassword" class="has-toggle" :type="reveal ? 'text' : 'password'" autocomplete="new-password" maxlength="64" placeholder="输入新密码" :disabled="saving" />
            <button type="button" class="sd-eye" :aria-label="reveal ? '隐藏密码' : '显示密码'" :aria-pressed="reveal" @click="reveal = !reveal"><EyeOff v-if="reveal" :size="17" /><Eye v-else :size="17" /></button>
          </div>
          <p class="sd-hint" :class="{ ok: form.newPassword.length >= 8 && form.newPassword.length <= 64 }">{{ lengthHint }}</p>
        </div>
        <div class="sd-field">
          <label for="password-confirm">确认新密码</label>
          <div class="sd-input"><input id="password-confirm" v-model="form.confirmPassword" :type="reveal ? 'text' : 'password'" autocomplete="new-password" maxlength="64" placeholder="再输一次" :disabled="saving" /></div>
          <p v-if="mismatch" class="sd-hint error">两次输入的密码不一致</p>
        </div>

        <p class="sd-note"><Info :size="15" aria-hidden="true" /><span>保存后，这个账号在其他电脑、手机和桌面端上的登录都会退出，这台设备保持登录。正在直播时建议下播后再改。</span></p>
        <p v-if="error" class="sd-error" role="alert">{{ error }}</p>
      </div>
      <footer class="sd-footer">
        <button type="button" class="sd-btn" :disabled="saving" @click="emit('close')">取消</button>
        <button type="submit" class="sd-btn primary" :disabled="saving">{{ saving ? '保存中…' : '保存密码' }}</button>
      </footer>
    </form>
  </SettingsDialog>
</template>
