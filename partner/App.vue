<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BarChart3, Coins, LayoutDashboard, LogOut, PanelLeftClose, PanelLeftOpen, ShieldCheck, Store, TicketCheck, Users } from 'lucide-vue-next'
import { auth } from '../src/stores/auth.js'
import { isOwner, partner } from './partnerStore.js'

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const signingOut = ref(false)
const authorized = computed(() => route.meta.partner && auth.isAuthenticated && partner.overview)
const navigation = computed(() => [
  { name: 'partner-overview', label: '工作台', description: '今天要跟进的商户、额度与流水', icon: LayoutDashboard },
  { name: 'partner-dashboard', label: '经营看板', description: '商户使用、烟火和激活码的统计', icon: BarChart3 },
  { name: 'partner-tenants', label: '名下商户', description: isOwner() ? '按 UID 添加商户，分配烟火、续期' : '按 UID 添加商户、续期', icon: Store },
  { name: 'partner-codes', label: '激活码', description: '在额度内生成、作废激活码', icon: TicketCheck },
  ...(isOwner() ? [{ name: 'partner-team', label: '团队', description: '添加 BD，查看各 BD 名下商户', icon: Users },
    { name: 'partner-distributions', label: '分配记录', description: '分给商户和 BD 的每一笔烟火', icon: Coins }] : []),
])

async function logout() {
  signingOut.value = true
  try { await auth.logout(); await router.replace({ name: 'partner-login' }) }
  finally { signingOut.value = false }
}
</script>

<template>
  <div v-if="authorized" class="admin-workspace" :class="{ 'menu-open': menuOpen }">
    <a class="admin-skip" href="#partner-main">跳到主要内容</a>
    <button v-if="menuOpen" class="admin-menu-backdrop" aria-label="关闭导航" @click="menuOpen = false" />
    <aside class="admin-sidebar">
      <RouterLink class="admin-brand" :to="{ name: 'partner-overview' }"><span class="admin-brand-mark">服</span><span><strong>服务商后台</strong><small>{{ partner.overview.partner.name }}</small></span></RouterLink>
      <nav aria-label="服务商后台导航">
        <div class="admin-nav-group">
          <RouterLink v-for="item in navigation" :key="item.name" :to="{ name: item.name }" :title="item.description" :class="{ active: route.name === item.name }" :aria-current="route.name === item.name ? 'page' : undefined" @click="menuOpen = false"><component :is="item.icon" :size="17" />{{ item.label }}</RouterLink>
        </div>
      </nav>
      <div class="admin-sidebar-note"><ShieldCheck :size="15" /><span v-if="isOwner()">只显示你这家服务商的数据<br />烟火和额度由平台发放</span><span v-else>你是 BD，只显示你名下的商户<br />和你生成的激活码</span></div>
    </aside>
    <div class="admin-body">
      <header class="admin-topbar">
        <div class="admin-crumb"><button class="admin-menu-toggle" type="button" :aria-expanded="menuOpen" aria-label="切换导航" @click="menuOpen = !menuOpen"><component :is="menuOpen ? PanelLeftClose : PanelLeftOpen" :size="20" /></button><span>服务商后台</span><span class="admin-topbar-separator">/</span><strong>{{ route.meta.title }}</strong></div>
        <div class="admin-topbar-actions" style="margin-left:auto"><div class="admin-account"><span class="admin-account-avatar">{{ (auth.user?.name || '服').slice(0, 1) }}</span><span>{{ auth.user?.name || '服务商账号' }}<small>{{ isOwner() ? '负责人' : 'BD' }} · UID {{ auth.user?.uid || '—' }}</small></span><button type="button" :disabled="signingOut" @click="logout"><LogOut :size="14" />退出</button></div></div>
      </header>
      <main id="partner-main" class="admin-main" tabindex="-1"><RouterView /></main>
    </div>
  </div>
  <RouterView v-else />
</template>
