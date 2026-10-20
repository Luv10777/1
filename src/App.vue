<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Dialog, DialogDescription, DialogPanel, DialogTitle } from '@headlessui/vue'
import AppShell from './layouts/AppShell.vue'
import DesktopShell from './layouts/DesktopShell.vue'
import { inDesktop } from './utils/desktop'
import { auth } from './stores/auth'
import SiteFooter from './components/SiteFooter.vue'

const route = useRoute()
const router = useRouter()
const isPublic = computed(() => Boolean(route.meta.public))
// 信息广场对游客公开，但已登录的商家从侧边栏点进来时要继续带着侧边栏。
const bareLayout = computed(() => isPublic.value && !(route.meta.shellWhenSignedIn && auth.isAuthenticated && !inDesktop))
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
      <template v-if="bareLayout">
        <RouterView />
        <SiteFooter v-if="!inDesktop" />
      </template>
      <!-- 桌面端只做直播，不带平台的导航。 -->
      <DesktopShell v-else-if="inDesktop">
        <RouterView />
      </DesktopShell>
      <AppShell v-else>
        <RouterView />
      </AppShell>
    </div>
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
}
</style>
