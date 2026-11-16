<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Dialog, DialogDescription, DialogPanel, DialogTitle } from '@headlessui/vue'
import { KeyRound, LockKeyhole, Ticket, X } from 'lucide-vue-next'
import { billing, loadBillingPlans, yuan } from './stores/billing'
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
// 后端拦下写操作（9001）时弹出：说明为什么不能用，并给出输入激活码、购买激活码两条路。
const showMembership = () => {
  membershipOpen.value = true
  if (!billing.plans.length) loadBillingPlans().catch(() => { /* 读不到套餐时只显示通用的购买说明 */ })
}
onMounted(() => window.addEventListener('membership-required', showMembership))
onBeforeUnmount(() => window.removeEventListener('membership-required', showMembership))
// 只有管理员能在站内买激活码；账户还没读到时先给入口，套餐页会再按权限处理。
const canBuyCode = computed(() => billing.summary?.canManage !== false)
const buyHint = computed(() => {
  if (!canBuyCode.value) return '请联系店铺管理员购买激活码，或向服务商获取。'
  if (!billing.plans.length) return '在「套餐与权益」站内购买激活码，付款后即可开通。'
  return '站内可购买：' + billing.plans.map(plan => `${plan.name} ${yuan(plan.priceMinor)} / ${plan.durationDays} 天`).join('，') + '。'
})
function openBilling(action) {
  membershipOpen.value = false
  router.push({ name: 'billing', query: { membership: 'required', [action]: '1', redirect: route.fullPath, module: route.meta.title } })
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
        <header class="membership-head">
          <span class="membership-icon" aria-hidden="true"><LockKeyhole :size="20" :stroke-width="1.75" /></span>
          <span class="membership-chip">当前只能查看</span>
          <button type="button" class="membership-close" aria-label="关闭" title="关闭" @click="membershipOpen = false"><X :size="18" /></button>
        </header>
        <DialogTitle as="h2">{{ route.meta.title ? `开通会员后才能使用「${route.meta.title}」` : '开通会员后才能使用这个功能' }}</DialogTitle>
        <DialogDescription as="p" class="membership-lead">账号还没有用激活码开通会员，现在只能浏览页面，生成、提交等操作都会被拦下。开通后全部 AI 功能即可使用。</DialogDescription>
        <ul class="membership-paths">
          <li>
            <span class="membership-path-icon" aria-hidden="true"><KeyRound :size="18" :stroke-width="1.75" /></span>
            <div><strong>已有激活码</strong><span>在「套餐与权益」输入 16 位激活码，马上开通。</span></div>
            <button type="button" class="primary-button compact" @click="openBilling('activate')">输入激活码</button>
          </li>
          <li>
            <span class="membership-path-icon" aria-hidden="true"><Ticket :size="18" :stroke-width="1.75" /></span>
            <div><strong>还没有激活码</strong><span>{{ buyHint }}</span></div>
            <button v-if="canBuyCode" type="button" class="secondary-button compact" @click="openBilling('buy')">购买激活码</button>
          </li>
        </ul>
        <p class="membership-note">激活码不含烟火，开通后使用功能照常按烟火扣费。</p>
        <button type="button" class="membership-later" @click="membershipOpen = false">稍后再说</button>
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
.membership-panel { width: min(100%, 480px); padding: 24px 28px 20px; border: 1px solid var(--line); border-radius: 8px; background: var(--night-panel); box-shadow: var(--shadow-paper-elevated); color: var(--ink); }
.membership-head { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }
.membership-icon { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 8px; background: color-mix(in srgb, var(--color-accent) 12%, transparent); color: var(--color-accent-text); }
.membership-chip { padding: 2px 8px; border: 1px solid color-mix(in srgb, var(--color-warning) 40%, transparent); border-radius: 2px; color: var(--color-warning); font-size: 12px; }
.membership-close { display: grid; place-items: center; width: 32px; height: 32px; margin-left: auto; padding: 0; border: 0; border-radius: 4px; background: transparent; color: var(--ink-muted); cursor: pointer; }
.membership-close:hover, .membership-close:focus-visible { background: var(--color-bg-subtle); color: var(--ink); }
.membership-panel h2 { margin: 0 0 8px; font-family: var(--font-serif); font-size: 20px; font-weight: 600; line-height: 1.4; }
.membership-lead { margin: 0; color: var(--ink-muted); font-size: 13px; line-height: 1.8; }
.membership-paths { display: grid; gap: 10px; margin: 20px 0 0; padding: 0; list-style: none; }
.membership-paths li { display: flex; align-items: center; gap: 12px; padding: 14px; border: 1px solid var(--line); border-radius: 8px; background: var(--color-bg-subtle); }
.membership-paths li > div { display: flex; flex: 1; flex-direction: column; gap: 2px; min-width: 0; }
.membership-paths strong { font-size: 14px; font-weight: 600; }
.membership-paths li span:not(.membership-path-icon) { color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
.membership-path-icon { display: grid; flex: 0 0 34px; place-items: center; width: 34px; height: 34px; border-radius: 6px; background: var(--night-panel); color: var(--color-accent-text); }
.membership-paths button { flex: none; min-width: 104px; }
.membership-note { margin: 14px 0 0; color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
.membership-later { display: block; margin: 12px auto 0; padding: 6px 12px; border: 0; background: transparent; color: var(--ink-muted); font-size: 13px; cursor: pointer; }
.membership-later:hover, .membership-later:focus-visible { color: var(--color-accent-text); }
@media (max-width: 640px) {
  .membership-position { place-items: end center; padding: 12px; }
  .membership-panel { padding: 20px 18px 14px; }
  .membership-panel h2 { font-size: 18px; }
  .membership-paths li { flex-wrap: wrap; }
  .membership-paths li > div { flex-basis: calc(100% - 46px); }
  .membership-paths button { flex: 1 0 100%; }
}
@media print {
  .site-layout-legal { display:block; height:auto; min-height:0; overflow:visible; }
  .site-layout-legal > .site-content { display:block; height:auto; min-height:0; overflow:visible; }
}
</style>
