<script setup>
import { ref, watch } from 'vue'
import { ExternalLink, RefreshCw } from 'lucide-vue-next'
import SettingsDialog from './SettingsDialog.vue'
import { accountApi, wechatQrFrameUrl } from '../../services/accountApi.js'

// 扫码绑定微信。二维码用微信官方的网页（和微信 JS 登录同样的参数）嵌在弹窗里，
// 手机上确认后微信让整个页面跳到回调地址，绑定完成后回到设置页。
const props = defineProps({ open: { type: Boolean, required: true } })
const emit = defineEmits(['close'])

const loading = ref(false)
const error = ref('')
const authorizeUrl = ref('')
const frameUrl = ref('')
const frameLoaded = ref(false)

watch(() => props.open, open => { if (open) load() })

async function load() {
  loading.value = true
  error.value = ''
  frameLoaded.value = false
  frameUrl.value = ''
  try {
    const { url } = await accountApi.bindWechat()
    authorizeUrl.value = url
    frameUrl.value = wechatQrFrameUrl(url) || ''
    if (!frameUrl.value) error.value = '微信授权地址不正确，请联系客服'
  } catch (failure) {
    error.value = failure.message || '二维码获取失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function openFullPage() {
  if (authorizeUrl.value) window.location.assign(authorizeUrl.value)
}
</script>

<template>
  <SettingsDialog :open="open" title="绑定微信" description="用要绑定的微信扫一扫，在手机上确认后自动完成。绑定后可以在登录页用微信扫码登录这个账号。" @close="emit('close')">
    <div class="sd-body">
      <div class="wx-qr" :class="{ ready: frameLoaded }" aria-live="polite">
        <div v-if="loading || (frameUrl && !frameLoaded)" class="wx-qr-placeholder"><span class="wx-spinner" aria-hidden="true" />正在获取二维码…</div>
        <div v-else-if="error" class="wx-qr-placeholder error">
          <span>{{ error }}</span>
          <button type="button" class="sd-btn" @click="load"><RefreshCw :size="14" />重新获取</button>
        </div>
        <iframe v-if="frameUrl" :src="frameUrl" title="微信扫码绑定二维码" sandbox="allow-scripts allow-top-navigation allow-same-origin" referrerpolicy="no-referrer" @load="frameLoaded = true" />
      </div>
      <ol class="wx-steps">
        <li>打开手机微信，点右上角「+」选择「扫一扫」</li>
        <li>扫描上方二维码，在手机上点「允许」</li>
        <li>页面会自动回到设置，显示「已绑定」</li>
      </ol>
      <p class="sd-hint">二维码 10 分钟内有效。一个微信只能绑定一个一方志账号。</p>
    </div>
    <footer class="sd-footer">
      <button v-if="authorizeUrl && !error" type="button" class="sd-btn" @click="openFullPage"><ExternalLink :size="14" />二维码显示不出来？</button>
      <button type="button" class="sd-btn primary" @click="emit('close')">关闭</button>
    </footer>
  </SettingsDialog>
</template>

<style>
.wx-qr { position: relative; display: grid; place-items: center; height: 300px; overflow: hidden; border: 1px solid var(--color-border-subtle); border-radius: 10px; background: #fff; }
.wx-qr iframe { width: 300px; height: 400px; margin-top: 90px; border: 0; opacity: 0; transition: opacity .25s ease; }
.wx-qr.ready iframe { opacity: 1; }
.wx-qr-placeholder { position: absolute; inset: 0; z-index: 1; display: grid; place-content: center; justify-items: center; gap: 12px; padding: 20px; color: #58696e; background: #fff; font-size: 13px; text-align: center; }
.wx-qr-placeholder.error { color: var(--color-error); }
.wx-spinner { width: 24px; height: 24px; border: 2px solid #e6e1d8; border-top-color: #07c160; border-radius: 50%; animation: wx-spin .8s linear infinite; }
@keyframes wx-spin { to { transform: rotate(360deg); } }
.wx-steps { display: grid; gap: 6px; margin: 0; padding-left: 20px; color: var(--color-text-muted); font-size: 13px; line-height: 1.6; }
@media (prefers-reduced-motion: reduce) { .wx-spinner { animation-duration: 2s; } .wx-qr iframe { transition: none; } }
</style>
