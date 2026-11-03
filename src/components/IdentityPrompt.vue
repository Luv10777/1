<script setup>
import { reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Dialog, DialogDescription, DialogPanel, DialogTitle } from '@headlessui/vue'
import { CircleAlert, Check, IdCard, Lock, ShieldCheck, User } from 'lucide-vue-next'
import { auth } from '../stores/auth'
import { identityMissing, loadMyIdentity, myIdentity, setMyIdentity } from '../stores/identity'
import { platformManagementApi } from '../services/platformManagement'
import { identityValidation, shouldPromptIdentity } from '../domain/platformManagement'
import { LEGAL_ENTITY } from '../domain/legalPolicies'

// 登录后提醒还没实名的账号去实名。可以「稍后再说」，平台其他功能照常使用；推广返利上线后以实名为前提。
// 实名只由阿里云当场核验，不做人工审核。
const route = useRoute()
const router = useRouter()
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

function goReferral() {
  close()
  router.push({ name: 'referral' })
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
        <div v-if="result === 'VERIFIED'" class="identity-done">
          <span class="identity-done-icon" aria-hidden="true"><Check :size="34" /></span>
          <DialogTitle as="h2">实名核验通过</DialogTitle>
          <DialogDescription as="p">姓名与身份证号码核验一致，邀请码与返利提现已解锁。</DialogDescription>
          <div class="identity-actions">
            <button type="button" class="secondary-button" @click="goReferral">去领取邀请码</button>
            <button type="button" class="primary-button" @click="close">完成</button>
          </div>
        </div>
        <form v-else autocomplete="off" @submit.prevent="submit">
          <div class="identity-head">
            <span class="identity-icon" aria-hidden="true"><ShieldCheck :size="24" /></span>
            <div>
              <DialogTitle as="h2">完成实名认证</DialogTitle>
              <DialogDescription as="p">领取专属邀请码，参与推广返利</DialogDescription>
            </div>
          </div>
          <div v-if="error" class="identity-error" role="alert">
            <CircleAlert :size="18" aria-hidden="true" />
            <div>
              {{ error }}
              <small v-if="mismatch">仍有疑问？联系人工客服：<a :href="`tel:${LEGAL_ENTITY.phone}`">{{ LEGAL_ENTITY.phone }}</a></small>
            </div>
          </div>
          <label class="identity-field">
            <span>真实姓名</span>
            <span class="identity-input" :class="{ bad: mismatch }">
              <User :size="18" aria-hidden="true" />
              <input v-model="form.legalName" name="legal-name" autocomplete="off" maxlength="40" placeholder="与身份证上的姓名一致" :disabled="submitting" />
            </span>
          </label>
          <label class="identity-field">
            <span>居民身份证号码<small>18 位</small></span>
            <span class="identity-input" :class="{ bad: mismatch }">
              <IdCard :size="18" aria-hidden="true" />
              <input v-model="form.idNumber" name="id-number" autocomplete="off" autocapitalize="characters" spellcheck="false" maxlength="18" placeholder="请输入身份证号码" :disabled="submitting" />
            </span>
          </label>
          <label class="identity-consent">
            <input v-model="form.declarationAccepted" type="checkbox" :disabled="submitting" />
            <span>我确认以上是本人真实、准确的身份信息，已阅读<a href="/policies/identity" target="_blank" rel="noopener noreferrer">《敏感个人信息处理说明》</a>，并单独同意平台为本次实名核验处理。不能替别人提交。</span>
          </label>
          <div class="identity-actions between">
            <button type="button" class="text-button" :disabled="submitting" @click="later">稍后再说</button>
            <button type="submit" class="primary-button" :disabled="submitting">{{ submitting ? '核验中…' : mismatch ? '重新核验' : '立即核验' }}</button>
          </div>
          <p class="identity-secure"><Lock :size="13" aria-hidden="true" />资料加密保存，仅用于本次核验</p>
        </form>
      </DialogPanel>
    </div>
  </Dialog>
</template>

<style scoped>
:global(.identity-prompt) { position: fixed; inset: 0; z-index: 1100; }
.identity-backdrop { position: fixed; inset: 0; background: rgb(15 23 42 / 50%); }
.identity-position { position: fixed; inset: 0; display: grid; place-items: center; overflow-y: auto; padding: 24px 16px; }
.identity-panel { width: min(100%, 440px); padding: 26px 28px; border: 1px solid var(--line); border-radius: 12px; background: var(--night-panel); color: var(--ink); }
.identity-panel h2 { margin: 0; font-family: var(--font-serif); font-size: 18px; font-weight: 600; }
.identity-panel p { margin: 0; color: var(--ink-muted); font-size: 13px; line-height: 1.8; }
.identity-head { display: flex; align-items: center; gap: 14px; }
.identity-head p { margin-top: 2px; font-size: 12px; }
.identity-icon { flex: none; display: grid; place-items: center; width: 44px; height: 44px; border-radius: 10px; color: var(--color-accent-text); background: color-mix(in srgb, var(--color-accent) 10%, var(--night-panel)); }
.identity-error { display: flex; gap: 10px; margin-top: 18px; padding: 12px 14px; border-radius: 6px; color: var(--color-error); background: color-mix(in srgb, var(--color-error) 8%, var(--night-panel)); font-size: 13px; line-height: 1.7; }
.identity-error svg { flex: none; margin-top: 2px; }
.identity-error small { display: block; margin-top: 2px; color: var(--ink-muted); font-size: 12px; }
.identity-error a { color: var(--color-accent-text); }
.identity-field { display: grid; gap: 6px; margin-top: 16px; font-size: 13px; font-weight: 600; }
.identity-field > span:first-child { display: flex; justify-content: space-between; }
.identity-field small { color: var(--ink-muted); font-size: 12px; font-weight: 400; }
.identity-input { position: relative; display: block; }
.identity-input svg { position: absolute; top: 50%; left: 12px; transform: translateY(-50%); color: var(--ink-muted); pointer-events: none; }
.identity-input input { width: 100%; min-height: 44px; box-sizing: border-box; padding: 0 12px 0 40px; border: 1px solid var(--color-border-strong); border-radius: 6px; background: var(--color-bg-surface); color: var(--ink); font: inherit; font-size: 15px; font-weight: 400; }
.identity-input input:focus { outline: 0; border-color: var(--color-accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-accent) 15%, transparent); }
.identity-input input:disabled { background: var(--color-bg-subtle); }
.identity-input.bad input { border-color: var(--color-error); box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-error) 10%, transparent); }
.identity-consent { display: flex; gap: 10px; align-items: flex-start; margin-top: 16px; color: var(--ink-muted); font-size: 12px; line-height: 1.75; }
.identity-consent input { flex: none; width: 16px; height: 16px; margin-top: 4px; accent-color: var(--color-accent); }
.identity-consent a { color: var(--color-accent-text); }
.identity-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 12px; margin-top: 22px; }
.identity-actions.between { justify-content: space-between; align-items: center; }
.text-button { padding: 0; border: 0; background: transparent; color: var(--ink-muted); font: inherit; font-size: 13px; cursor: pointer; }
.text-button:hover:not(:disabled) { color: var(--color-accent-text); }
.identity-panel .identity-secure { display: flex; justify-content: center; align-items: center; gap: 4px; margin-top: 14px; font-size: 12px; line-height: 1.6; }
.identity-done { padding-top: 8px; text-align: center; }
.identity-done-icon { display: grid; place-items: center; width: 64px; height: 64px; margin: 0 auto 14px; border-radius: 50%; color: var(--color-success); background: color-mix(in srgb, var(--color-success) 10%, var(--night-panel)); }
.identity-done p { margin-top: 6px; }
.identity-done .identity-actions { justify-content: center; }
</style>
