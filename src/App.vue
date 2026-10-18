<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Dialog, DialogDescription, DialogPanel, DialogTitle } from '@headlessui/vue'
import AppShell from './layouts/AppShell.vue'
import DesktopShell from './layouts/DesktopShell.vue'
import { inDesktop } from './utils/desktop'
import { LEGAL_ENTITY } from './domain/legalPolicies'

const route = useRoute()
const router = useRouter()
const isPublic = computed(() => Boolean(route.meta.public))
const membershipOpen = ref(false)
const showMembership = () => { membershipOpen.value = true }
onMounted(() => window.addEventListener('membership-required', showMembership))
onBeforeUnmount(() => window.removeEventListener('membership-required', showMembership))
function openMembership() {
  membershipOpen.value = false
  router.push({ name: 'billing', query: { membership: 'required', redirect: route.fullPath, module: route.meta.title } })
}
</script>

<template>
  <div class="site-layout" :class="{ 'site-layout-legal': route.name === 'legal-policy' }">
    <div class="site-content">
      <RouterView v-if="isPublic" />
      <!-- 桌面端只做直播，不带平台的导航。 -->
      <DesktopShell v-else-if="inDesktop">
        <RouterView />
      </DesktopShell>
      <AppShell v-else>
        <RouterView />
      </AppShell>
    </div>
    <footer v-if="!inDesktop" class="site-footer" aria-label="网站备案信息">
      <span>ICP备案号：<a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">浙ICP备2026081720号</a></span>
      <span>运营主体：{{ LEGAL_ENTITY.name }}</span>
      <nav class="site-legal-links" aria-label="协议、售后与客服"><RouterLink to="/policies/terms" target="_blank" rel="noopener noreferrer">用户注册协议</RouterLink><RouterLink to="/policies/privacy" target="_blank" rel="noopener noreferrer">隐私协议</RouterLink><RouterLink to="/policies/merchant" target="_blank" rel="noopener noreferrer">商家入驻</RouterLink><RouterLink to="/policies/aftersales" target="_blank" rel="noopener noreferrer">售后与退款</RouterLink><a :href="`tel:${LEGAL_ENTITY.phone}`">客服 {{ LEGAL_ENTITY.phone }}</a></nav>
    </footer>
  </div>
  <Dialog :open="membershipOpen" class="membership-dialog" @close="membershipOpen = false">
    <div class="membership-backdrop" aria-hidden="true" />
    <div class="membership-position">
      <DialogPanel class="membership-panel">
        <DialogTitle as="h2">您还未开通会员</DialogTitle>
        <DialogDescription as="p">开通会员后即可使用创作功能。</DialogDescription>
        <div class="membership-actions">
          <button type="button" class="secondary-button" @click="membershipOpen = false">稍后再说</button>
          <button type="button" class="primary-button" @click="openMembership">去开通会员</button>
        </div>
      </DialogPanel>
    </div>
  </Dialog>
</template>

<style scoped>
.site-layout { display: flex; flex-direction: column; height: 100dvh; }
.site-content { position: relative; flex: 1; min-width: 0; min-height: 0; overflow: auto; }
.site-content :deep(.app-frame), .site-content :deep(.access-page) { height: 100%; }
.site-content :deep(.mobile-bottom-nav) { position: absolute; }
.site-footer { display: flex; flex-shrink: 0; flex-wrap: wrap; justify-content: center; align-items: center; gap: 4px 20px; padding: 8px 16px max(8px, env(safe-area-inset-bottom)); border-top: 1px solid var(--line); background: var(--night); color: var(--ink-muted); font-size: 12px; line-height: 1.8; text-align: center; }
.site-legal-links { display: flex; flex-wrap: wrap; justify-content: center; gap: 3px 14px; }
.site-legal-links a { white-space: nowrap; text-decoration: underline; text-decoration-color: var(--line-strong); text-underline-offset: 3px; }
.site-legal-links a:hover, .site-legal-links a:focus-visible { color: var(--color-accent-text); }
.site-footer a { white-space: nowrap; }
.site-footer a:hover, .site-footer a:focus-visible { color: var(--ink); text-decoration: underline; text-underline-offset: 3px; }
:global(.membership-dialog) { position: fixed; inset: 0; z-index: 1100; }
.membership-backdrop { position: fixed; inset: 0; background: rgb(30 27 23 / 38%); }
.membership-position { position: fixed; inset: 0; display: grid; place-items: center; overflow-y: auto; padding: 24px; }
.membership-panel { width: min(100%, 400px); padding: 28px; border: 1px solid var(--line); border-radius: 8px; background: var(--night-panel); color: var(--ink); }
.membership-panel h2 { margin: 0 0 12px; font-size: 20px; font-weight: 500; }
.membership-panel p { margin: 0; color: var(--ink-muted); font-size: 13px; line-height: 1.8; }
.membership-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 12px; margin-top: 24px; }
@media print {
  .site-layout-legal { display:block; height:auto; min-height:0; overflow:visible; }
  .site-layout-legal > .site-content { display:block; height:auto; min-height:0; overflow:visible; }
  .site-layout-legal > .site-footer { display:none; }
}
</style>
