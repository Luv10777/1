<script setup>
import { reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Dialog, DialogDescription, DialogPanel, DialogTitle } from '@headlessui/vue'
import { ShieldCheck } from 'lucide-vue-next'
import { auth } from '../stores/auth'
import { identityMissing, loadMyIdentity, myIdentity, setMyIdentity } from '../stores/identity'
import { platformManagementApi } from '../services/platformManagement'
import { identityValidation, shouldPromptIdentity } from '../domain/platformManagement'
import { LEGAL_ENTITY } from '../domain/legalPolicies'

// 登录后提醒还没实名的账号去实名。可以「稍后再说」，平台其他功能照常使用；推广返利上线后以实名为前提。
// 实名只由阿里云当场核验，不做人工审核。
const route = useRoute()
const open = ref(false)
const result = ref('')
const submitting = ref(false)
const error = ref('')
const mismatch = ref(false)
const form = reactive({ legalName: '', idNumber: '', declarationAccepted: false })
// 浏览器禁用本地存储时，至少保证本次打开期间不会每换一页就弹一次。
let dismissedInMemory = 0

const dismissKey = () => `wuyao-identity-prompt-dismissed:${auth.user?.id}`
function dismissedAt() {
  try { return Number(localStorage.getItem(dismissKey())) || dismissedInMemory } catch { return dismissedInMemory }
}

watch(() => auth.user?.id, id => {
  open.value = false
  dismissedInMemory = 0
  if (id) loadMyIdentity()
}, { immediate: true })

watch(() => [myIdentity.loaded, myIdentity.application?.status, route.name], () => {
  if (open.value || !shouldPromptIdentity({ missing: identityMissing(), routeName: route.name, dismissedAt: dismissedAt() })) return
  clearForm()
  result.value = ''
  error.value = ''
  mismatch.value = false
  open.value = true
}, { immediate: true })

function clearForm() {
  form.legalName = ''
  form.idNumber = ''
  form.declarationAccepted = false
}

function close() {
  clearForm()
  open.value = false
}

function later() {
  if (submitting.value) return
  if (result.value) return close()
  dismissedInMemory = Date.now()
  try { localStorage.setItem(dismissKey(), String(dismissedInMemory)) } catch { /* 只在内存里记住 */ }
  close()
}

async function submit() {
  if (submitting.value) return
  error.value = identityValidation(form)
  mismatch.value = false
  if (error.value) return
  submitting.value = true
  try {
    const application = await platformManagementApi.submitIdentity({
      legalName: form.legalName.trim(),
      idNumber: form.idNumber.trim().toUpperCase(),
      declarationAccepted: form.declarationAccepted,
    })
    setMyIdentity(application)
    if (application.status === 'REJECTED') {
      mismatch.value = true
      error.value = '姓名与身份证号码不一致，请核对后重新提交。'
      return
    }
    clearForm()
    result.value = application.status
    setTimeout(() => { if (result.value === 'VERIFIED') close() }, 1500)
  } catch (failure) {
    // 断网时核验可能其实已经完成，重新读一次状态，避免让人重复提交。
    loadMyIdentity()
    error.value = failure.message || '提交失败，请稍后再试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog :open="open" class="identity-prompt" @close="later">
    <div class="identity-backdrop" aria-hidden="true" />
    <div class="identity-position">
      <DialogPanel class="identity-panel">
        <template v-if="result === 'VERIFIED'">
          <ShieldCheck class="identity-done" :size="32" aria-hidden="true" />
          <DialogTitle as="h2">实名核验通过</DialogTitle>
          <DialogDescription as="p">姓名与身份证号码核验一致。</DialogDescription>
          <div class="identity-actions"><button type="button" class="primary-button" @click="close">完成</button></div>
        </template>
        <form v-else autocomplete="off" @submit.prevent="submit">
          <DialogTitle as="h2">完成实名认证</DialogTitle>
          <DialogDescription as="p">推广返利即将上线，参与推广和提现需要先完成实名。这里只核验姓名与身份证号码是否一致，资料加密保存。</DialogDescription>
          <label class="identity-field">
            <span>真实姓名</span>
            <input v-model="form.legalName" name="legal-name" autocomplete="off" maxlength="40" placeholder="与身份证上的姓名一致" :disabled="submitting" />
          </label>
          <label class="identity-field">
            <span>居民身份证号码</span>
            <input v-model="form.idNumber" name="id-number" autocomplete="off" autocapitalize="characters" spellcheck="false" maxlength="18" placeholder="18 位身份证号码" :disabled="submitting" />
          </label>
          <label class="identity-consent">
            <input v-model="form.declarationAccepted" type="checkbox" :disabled="submitting" />
            <span>我确认以上是本人真实、准确的身份信息，已阅读<a href="/policies/identity" target="_blank" rel="noopener noreferrer">《敏感个人信息处理说明》</a>，并单独同意平台为本次实名核验处理。不能替别人提交。</span>
          </label>
          <p v-if="mismatch" class="identity-hotline">如有疑问请拨打人工客服：<a :href="`tel:${LEGAL_ENTITY.phone}`">{{ LEGAL_ENTITY.phone }}</a></p>
          <p v-if="error" class="identity-error" role="alert">{{ error }}</p>
          <div class="identity-actions">
            <button type="button" class="secondary-button" :disabled="submitting" @click="later">稍后再说</button>
            <button type="submit" class="primary-button" :disabled="submitting">{{ submitting ? '核验中…' : '立即核验' }}</button>
          </div>
        </form>
      </DialogPanel>
    </div>
  </Dialog>
</template>

<style scoped>
:global(.identity-prompt) { position: fixed; inset: 0; z-index: 1100; }
.identity-backdrop { position: fixed; inset: 0; background: rgb(30 27 23 / 38%); }
.identity-position { position: fixed; inset: 0; display: grid; place-items: center; overflow-y: auto; padding: 24px 16px; }
.identity-panel { width: min(100%, 440px); padding: 28px; border: 1px solid var(--line); border-radius: 8px; background: var(--night-panel); color: var(--ink); }
.identity-panel h2 { margin: 0 0 10px; font-size: 20px; font-weight: 500; }
.identity-panel p { margin: 0; color: var(--ink-muted); font-size: 13px; line-height: 1.8; }
.identity-done { color: var(--color-success); margin-bottom: 10px; }
.identity-field { display: grid; gap: 6px; margin-top: 16px; font-size: 13px; font-weight: 600; }
.identity-field input { width: 100%; min-height: 40px; padding: 0 12px; border: 1px solid var(--color-border-subtle); border-radius: 4px; background: var(--color-bg-surface); color: var(--ink); font: inherit; font-weight: 400; }
.identity-consent { display: flex; gap: 8px; align-items: flex-start; margin-top: 16px; color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
.identity-consent input { margin-top: 4px; accent-color: var(--color-accent); }
.identity-consent a { color: var(--color-accent-text); }
.identity-panel .identity-hotline { margin-top: 12px; }
.identity-hotline a { color: var(--color-accent-text); }
.identity-panel .identity-error { margin-top: 12px; color: var(--color-error); }
.identity-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 12px; margin-top: 24px; }
</style>
