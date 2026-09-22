<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Dialog, DialogPanel, DialogTitle, TransitionChild, TransitionRoot } from '@headlessui/vue'
import { Check, ChevronLeft, ChevronRight, LoaderCircle, RefreshCw, ShieldCheck, Smartphone, X } from 'lucide-vue-next'
import QRCode from 'qrcode'
import PlatformMark from './PlatformMark.vue'
import { accountPlatforms, permissionOptions } from '../../domain/platformAccounts'

const props = defineProps({ open: Boolean, initialPlatform: { type: String, default: '抖音' }, account: { type: Object, default: null } })
const emit = defineEmits(['close', 'complete'])
const step = ref(1)
const platform = ref('抖音')
const identity = ref('企业号')
const name = ref('')
const permissions = ref([])
const countdown = ref(120)
const qrImage = ref('')
const qrError = ref('')
const scanning = ref(false)
const selectedMeta = computed(() => accountPlatforms.find(p => p.name === platform.value))
const options = computed(() => permissionOptions(platform.value))
let expiresAt = 0
let timer
let scanTimer
let qrGeneration = 0
const control = 'rounded-lg border border-solid border-border-subtle bg-bg-surface px-3 py-2.5 text-sm text-brand-primary! transition hover:bg-bg-subtle focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary'
const primary = 'inline-flex items-center justify-center gap-2 rounded-lg border-0 bg-brand-accent px-4 py-2.5 text-sm font-semibold text-[var(--color-on-accent)]! shadow-paper transition hover:bg-brand-accent-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary disabled:cursor-not-allowed disabled:opacity-40'

function stopTimers() { clearInterval(timer); clearTimeout(scanTimer); scanning.value = false }
function selectPlatform(value) {
  platform.value = value
  identity.value = accountPlatforms.find(p => p.name === value).identity[0]
  permissions.value = permissionOptions(value)
}
watch(() => props.open, open => {
  stopTimers()
  qrGeneration += 1
  if (!open) return
  step.value = 1
  selectPlatform(props.account?.platform || props.initialPlatform)
  identity.value = props.account?.type || selectedMeta.value.identity[0]
  name.value = props.account?.name || ''
})
async function refreshQr() {
  stopTimers()
  const generation = ++qrGeneration
  qrError.value = ''
  qrImage.value = ''
  countdown.value = 120
  try {
    // Demonstration payload only: no credentials, OAuth tokens, or live callback URL.
    const payload = JSON.stringify({ mode: 'wuyao-oauth-demo', platform: platform.value, nonce: crypto.randomUUID() })
    const image = await QRCode.toDataURL(payload, { width: 240, margin: 2, errorCorrectionLevel: 'H', color: { dark: '#0f172a', light: '#ffffff' } })
    if (generation !== qrGeneration || !props.open || step.value !== 2) return
    qrImage.value = image
    expiresAt = Date.now() + 120000
    timer = setInterval(() => {
      countdown.value = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000))
      if (!countdown.value) stopTimers()
    }, 250)
  } catch { if (generation === qrGeneration) qrError.value = '二维码生成失败，请重试。' }
}
function next() {
  if (!name.value.trim()) return
  step.value = 2
  refreshQr()
}
function simulateScan() {
  if (!qrImage.value || scanning.value || Date.now() >= expiresAt) return
  scanning.value = true
  scanTimer = setTimeout(() => {
    if (!props.open || step.value !== 2 || Date.now() >= expiresAt) { stopTimers(); return }
    stopTimers()
    step.value = 3
  }, 700)
}
function back() { stopTimers(); qrGeneration += 1; step.value = 1 }
function complete() {
  if (step.value !== 3 || !permissions.value.length) return
  emit('complete', { accountId: props.account?.id, platform: platform.value, type: identity.value, name: name.value, permissions: permissions.value })
}
onBeforeUnmount(() => { stopTimers(); qrGeneration += 1 })
</script>

<template>
  <TransitionRoot appear :show="open" as="template">
    <Dialog as="div" class="relative z-[80] font-sans text-brand-primary [font-family:Inter,-apple-system,BlinkMacSystemFont,'PingFang_SC','Segoe_UI',sans-serif] " @close="emit('close')">
      <TransitionChild as="template" enter="duration-200 ease-out motion-reduce:duration-0" enter-from="opacity-0" enter-to="opacity-100" leave="duration-150 ease-in motion-reduce:duration-0" leave-from="opacity-100" leave-to="opacity-0">
        <div class="fixed inset-0 bg-slate-900/40 backdrop-blur-sm" />
      </TransitionChild>
      <div class="fixed inset-0 overflow-y-auto p-4">
        <div class="flex min-h-full items-center justify-center">
          <TransitionChild as="template" enter="duration-200 ease-out motion-reduce:duration-0" enter-from="scale-95 opacity-0" enter-to="scale-100 opacity-100" leave="duration-150 ease-in motion-reduce:duration-0" leave-from="scale-100 opacity-100" leave-to="scale-95 opacity-0">
            <DialogPanel class="w-full max-w-xl overflow-hidden rounded-md border border-solid border-border-subtle bg-bg-surface shadow-paper-elevated">
              <header class="flex items-center justify-between border-0 border-b border-solid border-border-subtle px-6 py-5">
                <div><div class="mb-1 text-xs font-medium text-brand-accent-ink">账号授权中心 <span class="ml-2 rounded bg-bg-subtle px-1.5 py-0.5 text-[10px] text-brand-muted">本地演示</span></div><DialogTitle class="m-0 text-xl font-semibold text-brand-primary">{{ account ? '续期账号授权' : '绑定互通' }}</DialogTitle></div>
                <button :class="control" aria-label="关闭授权弹窗" @click="emit('close')"><X :size="18" /></button>
              </header>
              <ol class="m-0 flex list-none items-center gap-2 px-6 pt-6">
                <li v-for="(label, index) in ['选择平台', '扫码授权', '确认权限']" :key="label" class="flex flex-1 items-center gap-2 text-xs" :aria-current="step === index + 1 ? 'step' : undefined">
                  <span class="grid size-7 shrink-0 place-items-center rounded-full font-semibold" :class="step >= index + 1 ? 'bg-brand-accent text-[var(--color-on-accent)]' : 'bg-bg-subtle text-brand-muted'"><Check v-if="step > index + 1" :size="14" /><template v-else>{{ index + 1 }}</template></span><span class="whitespace-nowrap" :class="step >= index + 1 ? 'text-brand-primary' : 'text-brand-muted'">{{ label }}</span><span v-if="index < 2" class="hidden h-px flex-1 bg-bg-subtle sm:block" />
                </li>
              </ol>
              <div class="min-h-80 px-6 py-6">
                <div v-if="step === 1" class="space-y-5">
                  <fieldset class="m-0 border-0 p-0">
                    <legend class="mb-3 text-sm font-medium">选择目标平台</legend><div class="grid grid-cols-3 gap-2">
                      <label v-for="item in accountPlatforms" :key="item.name" class="relative flex cursor-pointer flex-col items-center gap-2 rounded-xl border border-solid px-1 py-3 text-xs transition has-focus-visible:outline-2 has-focus-visible:outline-brand-primary" :class="platform === item.name ? 'border-brand-accent bg-bg-subtle text-brand-accent-ink' : 'border-border-subtle bg-bg-surface text-brand-primary'">
                        <input class="sr-only" type="radio" name="connect-platform" :value="item.name" :checked="platform === item.name" :disabled="!!account" @change="selectPlatform(item.name)" /><PlatformMark :platform="item.name" />{{ item.name }}
                      </label>
                    </div><p class="mb-0 mt-2 text-xs text-brand-muted">{{ selectedMeta.provider }}</p>
                  </fieldset>
                  <label class="block text-sm font-medium">授权身份<select v-model="identity" :disabled="!!account" class="mt-2 block w-full rounded-lg border border-solid border-border-subtle bg-bg-surface px-3 py-2.5 text-sm text-brand-primary focus:text-brand-accent-ink disabled:bg-bg-subtle"><option v-for="item in selectedMeta.identity" :key="item">{{ item }}</option></select></label>
                  <label class="block text-sm font-medium">{{ account ? '续期账号' : '演示账号昵称' }}<input v-model="name" maxlength="30" :readonly="!!account" placeholder="输入昵称，体验绑定后的账号管理" class="mt-2 block w-full rounded-lg border border-solid border-border-subtle bg-bg-surface px-3 py-2.5 text-sm text-brand-primary text-brand-accent-ink read-only:bg-bg-subtle" /></label>
                </div>
                <div v-else-if="step === 2" class="text-center">
                  <p class="m-0 text-sm font-medium">使用{{ platform === '视频号' ? '微信' : platform }}扫码完成授权</p>
                  <p class="mb-4 mt-2 text-xs text-brand-muted">演示二维码不连接官方平台，请使用下方模拟按钮。</p>
                  <div class="relative mx-auto grid size-52 place-items-center rounded-xl border border-solid border-border-subtle bg-bg-surface p-2">
                    <img v-if="qrImage" :src="qrImage" alt="本地授权演示二维码" class="size-full" />
                    <LoaderCircle v-else-if="!qrError" class="animate-spin text-brand-accent-ink motion-reduce:animate-none" />
                    <span v-if="qrImage" class="absolute rounded-xl bg-bg-surface p-1.5"><PlatformMark :platform="platform" /></span>
                    <div v-if="!countdown || qrError" class="absolute inset-0 flex flex-col items-center justify-center gap-3 rounded-xl bg-bg-surface/95"><span class="text-sm text-brand-primary">{{ qrError || '二维码已过期' }}</span><button :class="control" @click="refreshQr"><RefreshCw :size="14" class="mr-1 inline" />刷新二维码</button></div>
                  </div>
                  <p class="my-4 flex items-center justify-center gap-2 text-xs text-brand-muted" role="status"><LoaderCircle class="animate-spin motion-reduce:animate-none" :size="13" />{{ scanning ? '已收到模拟扫码，正在确认身份…' : `等待扫码 · ${countdown} 秒后过期` }}</p>
                  <button :class="primary" :disabled="!qrImage || !countdown || scanning" @click="simulateScan"><Smartphone :size="16" />模拟扫码成功</button>
                </div>
                <div v-else class="space-y-3">
                  <p class="m-0 rounded-lg bg-bg-subtle px-3 py-3 text-sm text-state-success"><Check class="mr-1 inline" :size="16" />{{ name }} · 模拟身份确认成功</p>
                  <p class="text-xs leading-5 text-brand-muted">勾选允许平台使用的权限。取消发布权限后，该账号将不出现在内容发布页。</p>
                  <label v-for="permission in options" :key="permission" class="flex cursor-pointer items-center gap-3 rounded-lg border border-solid border-border-subtle p-3 text-sm hover:border-brand-accent"><input v-model="permissions" type="checkbox" :value="permission" class="size-4 accent-blue-600" />{{ permission }}<span v-if="permission.includes('发布') || permission === '门店POI关联'" class="ml-auto text-xs text-brand-muted">核心权限</span></label>
                </div>
              </div>
              <footer class="space-y-4 border-0 border-t border-solid border-border-subtle bg-bg-subtle px-6 py-4">
                <div class="flex justify-between gap-3"><button :class="control" @click="step === 1 ? emit('close') : back()"><ChevronLeft v-if="step > 1" class="mr-1 inline" :size="14" />{{ step === 1 ? '取消' : '重新选择' }}</button><button v-if="step === 1" :class="primary" :disabled="!name.trim()" @click="next">下一步 <ChevronRight :size="15" /></button><button v-if="step === 3" :class="primary" :disabled="!permissions.length" @click="complete">{{ account ? '确认续期' : '完成授权' }}<Check :size="15" /></button></div>
                <p class="m-0 flex gap-2 text-[11px] leading-5 text-brand-muted"><ShieldCheck class="mt-0.5 shrink-0" :size="14" />授权过程严格遵守官方开放平台数据安全协议，平台不会获取您的账号登录密码。</p>
              </footer>
            </DialogPanel>
          </TransitionChild>
        </div>
      </div>
    </Dialog>
  </TransitionRoot>
</template>
