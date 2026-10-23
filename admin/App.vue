<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { FileText, Store, ShieldCheck, LogOut, PanelLeftClose, PanelLeftOpen, Ticket, Layers, Users } from 'lucide-vue-next'
import { auth } from '../src/stores/auth.js'
import { platform } from '../src/stores/platform.js'

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
]

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
        <RouterLink v-for="item in navigation" :key="item.name" :to="{ name: item.name }" :class="{ active: route.name === item.name }" :aria-current="route.name === item.name ? 'page' : undefined" @click="menuOpen = false"><component :is="item.icon" :size="18" /><span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span></RouterLink>
      </nav>
      <div class="admin-sidebar-note"><ShieldCheck :size="15" /><span>仅限已授权管理员<br />资料查阅与处理留存记录</span></div>
    </aside>
    <div class="admin-body">
      <header class="admin-topbar"><div><button class="admin-menu-toggle" type="button" :aria-expanded="menuOpen" aria-label="切换后台导航" @click="menuOpen = !menuOpen"><component :is="menuOpen ? PanelLeftClose : PanelLeftOpen" :size="20" /></button><span>运营管理</span><span class="admin-topbar-separator">/</span><strong>{{ route.meta.title }}</strong></div><div class="admin-account"><span class="admin-account-avatar">{{ (auth.user?.name || '管').slice(0, 1) }}</span><span>{{ auth.user?.name || '管理员' }}<small>平台管理员</small></span><button type="button" :disabled="signingOut" @click="logout"><LogOut :size="15" />{{ signingOut ? '退出中…' : '退出' }}</button></div></header>
      <main id="admin-main" class="admin-main" tabindex="-1"><RouterView /></main>
    </div>
  </div>
  <RouterView v-else />
</template>
