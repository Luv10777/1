<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, ChevronRight, FileText, Flame, Inbox, LayoutDashboard, Store, ShieldCheck, LogOut, PanelLeftClose, PanelLeftOpen, Search, Ticket, Layers, Users, Percent, ChartColumn, TicketCheck } from 'lucide-vue-next'
import { auth } from '../src/stores/auth.js'
import { platform } from '../src/stores/platform.js'
import { pendingByRoute, pendingItems, pendingTotal, refreshPending, startPendingPolling, stopPendingPolling } from './pendingReviews.js'
import CommandPalette from './CommandPalette.vue'

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const signingOut = ref(false)
const paletteOpen = ref(false)
const authorized = computed(() => route.meta.admin && auth.isAuthenticated && platform.operator)
const navigation = [
  { items: [
    { name: 'admin-home', label: '工作台', description: '今天要处理的事和平台概况', icon: LayoutDashboard },
    { name: 'admin-review', label: '审核中心', description: '信息、入驻、供券、申诉、收款账号', icon: Inbox },
  ] },
  { label: '用户与商家', items: [
    { name: 'admin-users', label: '用户管理', description: '账号状态、会员与烟火', icon: Users },
    { name: 'admin-merchants', label: '商家管理', description: '商家档案与入驻', icon: Store },
    { name: 'admin-identities', label: '用户实名', description: '实名核验记录', icon: ShieldCheck },
  ] },
  { label: '内容与联盟', items: [
    { name: 'admin-information', label: '信息管理', description: '平台发布内容与上下线', icon: FileText },
    { name: 'admin-alliance', label: '联盟券', description: '供券、申诉、对账与打款', icon: Ticket },
  ] },
  { label: '烟火与计费', items: [
    { name: 'admin-credit-overview', label: '烟火看板', description: '购买、赠送与商家余额', icon: ChartColumn },
    { name: 'admin-credits', label: '烟火日志', description: '全平台消耗与退回', icon: Flame },
    { name: 'admin-redeem-codes', label: '兑换码', description: '生成充值或赠送兑换码', icon: TicketCheck },
    { name: 'admin-plans', label: '会员套餐', description: '套餐版本与上架', icon: Layers },
  ] },
  { label: '增长', items: [
    { name: 'admin-referral', label: '邀请返利', description: '返利比例、提现与调整记录', icon: Percent },
  ] },
]
const currentGroup = computed(() => navigation.find(group => group.items.some(item => item.name === route.name))?.label)
const noticesOpen = ref(false)
const closeNotices = event => { if (event.key === 'Escape' || (event.type === 'pointerdown' && !event.target.closest?.('.admin-notices'))) noticesOpen.value = false }
watch(noticesOpen, open => {
  for (const type of ['pointerdown', 'keydown']) document[open ? 'addEventListener' : 'removeEventListener'](type, closeNotices)
})

// Ctrl K（Mac 上 ⌘K）或不在输入框里按 / 打开页面跳转。
function onShortcut(event) {
  if (!authorized.value) return
  const typing = event.target.closest?.('input,textarea,select,[contenteditable="true"]')
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); paletteOpen.value = !paletteOpen.value }
  else if (event.key === '/' && !typing && !paletteOpen.value) { event.preventDefault(); paletteOpen.value = true }
}
onMounted(() => document.addEventListener('keydown', onShortcut))

// 待处理事项只在进入后台后轮询；切换页面时顺便刷新，处理完一项回到列表就能看到数字变化。
watch(authorized, value => { if (value) startPendingPolling(); else stopPendingPolling() }, { immediate: true })
watch(() => route.name, () => { noticesOpen.value = false; paletteOpen.value = false; if (authorized.value) refreshPending() })
onBeforeUnmount(() => { stopPendingPolling(); noticesOpen.value = false; document.removeEventListener('keydown', onShortcut) })

async function logout() {
  signingOut.value = true
  try { await auth.logout(); await router.replace({ name: 'admin-login' }) }
  finally { signingOut.value = false }
}
</script>

<template>
  <div v-if="authorized" class="admin-workspace" :class="{ 'menu-open': menuOpen }">
    <a class="admin-skip" href="#admin-main">跳到主要内容</a>
    <button v-if="menuOpen" class="admin-menu-backdrop" aria-label="关闭导航" @click="menuOpen = false" />
    <aside class="admin-sidebar">
      <RouterLink class="admin-brand" :to="{ name: 'admin-home' }"><span class="admin-brand-mark">管</span><span><strong>管理后台</strong><small>平台运营工作台</small></span></RouterLink>
      <nav aria-label="后台主导航">
        <div v-for="(group, index) in navigation" :key="group.label || index" class="admin-nav-group">
          <div v-if="group.label" class="admin-nav-label">{{ group.label }}</div>
          <RouterLink v-for="item in group.items" :key="item.name" :to="{ name: item.name }" :title="item.description" :class="{ active: route.name === item.name }" :aria-current="route.name === item.name ? 'page' : undefined" @click="menuOpen = false"><component :is="item.icon" :size="17" /><span>{{ item.label }}</span><b v-if="pendingByRoute[item.name]" class="admin-nav-badge" :aria-label="`${pendingByRoute[item.name]} 项待处理`">{{ pendingByRoute[item.name] > 99 ? '99+' : pendingByRoute[item.name] }}</b></RouterLink>
        </div>
      </nav>
      <div class="admin-sidebar-note"><ShieldCheck :size="15" /><span>仅限已授权管理员<br />资料查阅与处理留存记录</span></div>
    </aside>
    <div class="admin-body">
      <header class="admin-topbar">
        <div class="admin-crumb"><button class="admin-menu-toggle" type="button" :aria-expanded="menuOpen" aria-label="切换后台导航" @click="menuOpen = !menuOpen"><component :is="menuOpen ? PanelLeftClose : PanelLeftOpen" :size="20" /></button><template v-if="currentGroup"><span>{{ currentGroup }}</span><span class="admin-topbar-separator">/</span></template><strong>{{ route.meta.title }}</strong></div>
        <button class="admin-search" type="button" aria-label="跳转到页面" @click="paletteOpen = true"><Search :size="16" /><span>跳转到页面…</span><kbd>Ctrl K</kbd></button>
        <div class="admin-topbar-actions"><div class="admin-notices"><button type="button" class="admin-notices-button" :aria-expanded="noticesOpen" :aria-label="pendingTotal ? `待处理事项 ${pendingTotal} 项` : '待处理事项'" @click="noticesOpen = !noticesOpen; noticesOpen && refreshPending()"><Bell :size="18" /><b v-if="pendingTotal">{{ pendingTotal > 99 ? '99+' : pendingTotal }}</b></button><div v-if="noticesOpen" class="admin-notices-panel"><strong>待处理事项</strong><p v-if="!pendingTotal" class="admin-notices-empty">暂时没有需要审核或处理的内容。</p><RouterLink v-for="item in pendingItems.filter(entry => entry.count)" v-else :key="item.key" :to="{ name: item.route, query: item.kind ? { kind: item.kind } : undefined }" @click="noticesOpen = false"><span>{{ item.label }}</span><b>{{ item.count }}</b><ChevronRight :size="15" /></RouterLink></div></div><div class="admin-account"><span class="admin-account-avatar">{{ (auth.user?.name || '管').slice(0, 1) }}</span><span>{{ auth.user?.name || '管理员' }}<small>平台管理员</small></span><button type="button" :disabled="signingOut" @click="logout"><LogOut :size="15" />{{ signingOut ? '退出中…' : '退出' }}</button></div></div>
      </header>
      <main id="admin-main" class="admin-main" tabindex="-1"><RouterView /></main>
    </div>
    <CommandPalette :open="paletteOpen" :groups="navigation" @close="paletteOpen = false" />
  </div>
  <RouterView v-else />
</template>
