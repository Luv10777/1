<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, ChevronRight, FileText, Flame, Store, ShieldCheck, LogOut, PanelLeftClose, PanelLeftOpen, Ticket, Layers, Users } from 'lucide-vue-next'
import { auth } from '../src/stores/auth.js'
import { platform } from '../src/stores/platform.js'
import { pendingByRoute, pendingItems, pendingTotal, refreshPending, startPendingPolling, stopPendingPolling } from './pendingReviews.js'

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const signingOut = ref(false)
const authorized = computed(() => route.meta.admin && auth.isAuthenticated && platform.operator)
const navigation = [
  { name: 'admin-information', label: '信息管理', description: '平台发布内容', icon: FileText },
  { name: 'admin-merchants', label: '商家管理', description: '商家档案与入驻', icon: Store },
  { name: 'admin-identities', label: '用户实名', description: '实名资料与审核', icon: ShieldCheck },
  { name: 'admin-users', label: '用户管理', description: '账号状态与停用', icon: Users },
  { name: 'admin-alliance', label: '联盟券', description: '供券审核、申诉与结算', icon: Ticket },
  { name: 'admin-plans', label: '会员套餐', description: '套餐版本与上架', icon: Layers },
  { name: 'admin-credits', label: '烟火日志', description: '全平台消耗与退回', icon: Flame },
]
const noticesOpen = ref(false)
const closeNotices = event => { if (event.key === 'Escape' || (event.type === 'pointerdown' && !event.target.closest?.('.admin-notices'))) noticesOpen.value = false }
watch(noticesOpen, open => {
  for (const type of ['pointerdown', 'keydown']) document[open ? 'addEventListener' : 'removeEventListener'](type, closeNotices)
})

// 待处理事项只在进入后台后轮询；切换页面时顺便刷新，处理完一项回到列表就能看到数字变化。
watch(authorized, value => { if (value) startPendingPolling(); else stopPendingPolling() }, { immediate: true })
watch(() => route.name, () => { noticesOpen.value = false; if (authorized.value) refreshPending() })
onBeforeUnmount(() => { stopPendingPolling(); noticesOpen.value = false })

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
      <RouterLink class="admin-brand" :to="{ name: 'admin-information' }"><span class="admin-brand-mark"><ShieldCheck :size="21" /></span><span><strong>管理后台</strong><small>平台运营工作台</small></span></RouterLink>
      <div class="admin-nav-label">运营管理</div>
      <nav aria-label="后台主导航">
        <RouterLink v-for="item in navigation" :key="item.name" :to="{ name: item.name }" :class="{ active: route.name === item.name }" :aria-current="route.name === item.name ? 'page' : undefined" @click="menuOpen = false"><component :is="item.icon" :size="18" /><span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span><b v-if="pendingByRoute[item.name]" class="admin-nav-badge" :aria-label="`${pendingByRoute[item.name]} 项待处理`">{{ pendingByRoute[item.name] > 99 ? '99+' : pendingByRoute[item.name] }}</b></RouterLink>
      </nav>
      <div class="admin-sidebar-note"><ShieldCheck :size="15" /><span>仅限已授权管理员<br />资料查阅与处理留存记录</span></div>
    </aside>
    <div class="admin-body">
      <header class="admin-topbar"><div><button class="admin-menu-toggle" type="button" :aria-expanded="menuOpen" aria-label="切换后台导航" @click="menuOpen = !menuOpen"><component :is="menuOpen ? PanelLeftClose : PanelLeftOpen" :size="20" /></button><span>运营管理</span><span class="admin-topbar-separator">/</span><strong>{{ route.meta.title }}</strong></div><div class="admin-topbar-actions"><div class="admin-notices"><button type="button" class="admin-notices-button" :aria-expanded="noticesOpen" :aria-label="pendingTotal ? `待处理事项 ${pendingTotal} 项` : '待处理事项'" @click="noticesOpen = !noticesOpen; noticesOpen && refreshPending()"><Bell :size="18" /><b v-if="pendingTotal">{{ pendingTotal > 99 ? '99+' : pendingTotal }}</b></button><div v-if="noticesOpen" class="admin-notices-panel"><strong>待处理事项</strong><p v-if="!pendingTotal" class="admin-notices-empty">暂时没有需要审核或处理的内容。</p><RouterLink v-for="item in pendingItems.filter(entry => entry.count)" v-else :key="item.key" :to="{ name: item.route }" @click="noticesOpen = false"><span>{{ item.label }}</span><b>{{ item.count }}</b><ChevronRight :size="15" /></RouterLink></div></div><div class="admin-account"><span class="admin-account-avatar">{{ (auth.user?.name || '管').slice(0, 1) }}</span><span>{{ auth.user?.name || '管理员' }}<small>平台管理员</small></span><button type="button" :disabled="signingOut" @click="logout"><LogOut :size="15" />{{ signingOut ? '退出中…' : '退出' }}</button></div></div></header>
      <main id="admin-main" class="admin-main" tabindex="-1"><RouterView /></main>
    </div>
  </div>
  <RouterView v-else />
</template>
