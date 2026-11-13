<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { Check } from 'lucide-vue-next'
import SettingsDialog from './SettingsDialog.vue'
import CodeField from './CodeField.vue'
import { accountApi } from '../../services/accountApi.js'

// 改绑或绑定手机号，分两步：先证明是本人（原手机号验证码，或当前密码），再验证新手机号。
const props = defineProps({
  open: { type: Boolean, required: true },
  profile: { type: Object, required: true },
})
const emit = defineEmits(['close', 'changed'])

const step = ref(1)
const byCode = ref(true)
const busy = ref(false)
const error = ref('')
const proof = reactive({ code: '', currentPassword: '' })
const target = reactive({ phone: '', code: '' })
const savedPhone = ref('')

// 打开时记下原来的号码：换绑成功后父组件会更新资料，标题和说明不能跟着变。
const original = ref('')
const hasPhone = computed(() => Boolean(original.value))
const canUsePassword = computed(() => Boolean(props.profile.passwordSet))
const noProof = computed(() => !hasPhone.value && !canUsePassword.value)
const masked = phone => (phone || '').replace(/^(\d{3})\d{4}(\d{4})$/, '$1 **** $2')

watch(() => props.open, open => {
  if (!open) return
  original.value = props.profile.phone || ''
  step.value = 1
  byCode.value = hasPhone.value
  error.value = ''
  savedPhone.value = ''
  Object.assign(proof, { code: '', currentPassword: '' })
  Object.assign(target, { phone: '', code: '' })
})

function useCode(value) {
  byCode.value = value
  error.value = ''
}

function newPhoneProblem() {
  const phone = target.phone.trim()
  if (!/^1[3-9]\d{9}$/.test(phone)) return '请输入有效的 11 位手机号'
  if (phone === original.value) return '新手机号和现在绑定的一样'
  return ''
}

async function verify() {
  if (busy.value) return
  error.value = ''
  busy.value = true
  try {
    await accountApi.verifyPhoneChange(proof, byCode.value)
    step.value = 2
  } catch (failure) {
    error.value = failure.message || '验证没有通过，请重试'
  } finally {
    busy.value = false
  }
}

async function change() {
  if (busy.value) return
  error.value = ''
  busy.value = true
  try {
    const profile = await accountApi.changePhone(target, original.value)
    savedPhone.value = profile.phone
    step.value = 3
    emit('changed', profile)
  } catch (failure) {
    error.value = failure.message || '绑定没有完成，请重试'
    // 身份确认十分钟后失效，回到第一步重新验证。
    if (/身份确认/.test(error.value)) step.value = 1
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <SettingsDialog :open="open" :busy="busy" :title="hasPhone ? '更换手机号' : '绑定手机号'" :description="hasPhone ? `现在绑定的是 ${masked(original)}。换号后用新手机号登录，原号码随即释放。` : '绑定后可以用手机号收验证码登录、找回密码。'" @close="emit('close')">
    <div v-if="step < 3" class="sd-body" style="padding-bottom: 0">
      <ol class="sd-steps" aria-label="步骤">
        <li :class="{ on: step === 1, done: step > 1 }"><span><Check v-if="step > 1" :size="13" /><template v-else>1</template></span>验证身份</li>
        <i aria-hidden="true" />
        <li :class="{ on: step === 2 }"><span>2</span>{{ hasPhone ? '绑定新手机号' : '绑定手机号' }}</li>
      </ol>
    </div>

    <Transition name="sd-step" mode="out-in">
      <form v-if="step === 1" key="verify" novalidate @submit.prevent="verify">
        <div class="sd-body">
          <p v-if="noProof" class="sd-error">这个账号既没有手机号也没有密码，无法在这里验证身份，请联系客服处理。</p>
          <template v-else>
            <div v-if="hasPhone && canUsePassword" class="sd-segment" role="group" aria-label="验证方式">
              <button type="button" :aria-pressed="byCode" :disabled="busy" @click="useCode(true)">原手机号验证码</button>
              <button type="button" :aria-pressed="!byCode" :disabled="busy" @click="useCode(false)">当前密码</button>
            </div>
            <CodeField v-if="byCode" id="phone-proof-code" v-model="proof.code" :phone="original" :label="`发到 ${masked(original)} 的验证码`" :disabled="busy">
              <template v-if="canUsePassword" #hint><p class="sd-hint">原号码已经停用、收不到短信？改用当前密码验证。</p></template>
            </CodeField>
            <div v-else class="sd-field">
              <label for="phone-proof-password">当前登录密码</label>
              <div class="sd-input"><input id="phone-proof-password" v-model="proof.currentPassword" type="password" autocomplete="current-password" maxlength="64" :disabled="busy" /></div>
            </div>
          </template>
          <p v-if="error" class="sd-error" role="alert">{{ error }}</p>
        </div>
        <footer class="sd-footer">
          <button type="button" class="sd-btn" :disabled="busy" @click="emit('close')">取消</button>
          <button type="submit" class="sd-btn primary" :disabled="busy || noProof">{{ busy ? '验证中…' : '下一步' }}</button>
        </footer>
      </form>

      <form v-else-if="step === 2" key="change" novalidate @submit.prevent="change">
        <div class="sd-body">
          <div class="sd-field">
            <label for="phone-new">{{ hasPhone ? '新手机号' : '手机号' }}</label>
            <div class="sd-input"><span class="sd-prefix">+86</span><input id="phone-new" v-model="target.phone" type="tel" inputmode="numeric" maxlength="11" autocomplete="tel-national" placeholder="11 位手机号" :disabled="busy" /></div>
          </div>
          <CodeField id="phone-new-code" v-model="target.code" :phone="target.phone.trim()" label="新手机号收到的验证码" :disabled="busy" :validate="newPhoneProblem" />
          <p class="sd-hint">已经注册过一方志的手机号不能再绑定到这个账号。已登录的设备不受影响。</p>
          <p v-if="error" class="sd-error" role="alert">{{ error }}</p>
        </div>
        <footer class="sd-footer">
          <button type="button" class="sd-btn" :disabled="busy" @click="emit('close')">取消</button>
          <button type="submit" class="sd-btn primary" :disabled="busy">{{ busy ? '绑定中…' : '确认绑定' }}</button>
        </footer>
      </form>

      <div v-else key="done">
        <div class="sd-body">
          <div class="sd-done" role="status">
            <span class="sd-done-icon"><Check :size="26" /></span>
            <strong>{{ hasPhone ? '手机号已更换' : '手机号已绑定' }}</strong>
            <p>以后用 {{ masked(savedPhone) }} 收验证码登录{{ profile.passwordSet ? '，或用它加密码登录' : '' }}。</p>
          </div>
        </div>
        <footer class="sd-footer"><button type="button" class="sd-btn primary" @click="emit('close')">完成</button></footer>
      </div>
    </Transition>
  </SettingsDialog>
</template>
