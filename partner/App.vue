<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BarChart3, ClipboardList, Coins, ExternalLink, LayoutDashboard, LogOut, PanelLeftClose, PanelLeftOpen, ShieldCheck, Store, TicketCheck, Users } from 'lucide-vue-next'
import { auth } from '../src/stores/auth.js'
import { useNavigationProgress } from '../admin/navigation.js'
import { partnerRoles } from '../src/domain/partnerOps.js'
import { can, myName, partner } from './partnerStore.js'
import YanhuoIcon from '../src/components/YanhuoIcon.vue'
import NameDialog from './components/NameDialog.vue'
import MemberIdentity from './components/MemberIdentity.vue'
import brandMark from '../public/favicon.png'

const number = value => Number(value || 0).toLocaleString('zh-CN')

const route = useRoute()
const router = useRouter()
const navigating = useNavigationProgress(router)
const menuOpen = ref(false)
// 窄屏上点了侧栏或页面里的链接，跳转后收起侧栏。
watch(() => route.name, () => { menuOpen.value = false })
const signingOut = ref(false)
const authorized = computed(() => route.meta.partner && auth.isAuthenticated && partner.overview)
// 第一次进来还没有团队称呼时先请他填（不能跳过）；改称呼在右上角点名字。
const naming = ref(false)
const needsName = computed(() => Boolean(authorized.value) && !partner.overview.displayName)
// 侧栏按权限显示：没有的入口不出现（后端照样会拦）。
const navigation = computed(() => [
  { label: '概览', items: [
    { name: 'partner-overview', label: '工作台', description: '今天要跟进的商户和团队烟火', icon: LayoutDashboard, show: true },
    { name: 'partner-dashboard', label: '经营看板', description: '商户使用、烟火和激活码的统计', icon: BarChart3, show: can('tenants') },
  ] },
  { label: '商户', items: [
    { name: 'partner-tenants', label: '名下商户', description: '添加商户、续期、分烟火、档案跟进', icon: Store, show: can('tenants') },
    { name: 'partner-codes', label: '激活码', description: '不限个数生成、作废、导出', icon: TicketCheck, show: can('codes') },
  ] },
  { label: '管理', items: [
    { name: 'partner-credits', label: '烟火', description: '团队烟火、平台发放和分配记录', icon: Coins, show: can('finance') || can('distribute') },
    { name: 'partner-task-logs', label: '任务日志', description: '团队烟火的创作消耗和商户分配', icon: ClipboardList, show: true },
    { name: 'partner-team', label: '团队', description: '成员、权限和称呼', icon: Users, show: true },
  ] },
].map(group => ({ ...group, items: group.items.filter(item => item.show) })).filter(group => group.items.length))

async function logout() {
  signingOut.value = true
  try { await auth.logout(); window.location.assign('/login') }
  finally { signingOut.value = false }
}
</script>

<template>
  <div v-if="authorized" class="admin-workspace" :class="{ 'menu-open': menuOpen }">
    <a class="admin-skip" href="#partner-main">跳到主要内容</a>
    <button v-if="menuOpen" class="admin-menu-backdrop" aria-label="关闭导航" @click="menuOpen = false" />
    <aside class="admin-sidebar">
      <RouterLink class="admin-brand" :to="{ name: 'partner-overview' }"><img class="admin-brand-mark" :src="brandMark" alt="一方志" width="30" height="30" /><span><strong>{{ partner.overview.partner.name }}</strong><small>一方志服务商后台</small></span></RouterLink>
      <nav aria-label="服务商后台导航">
        <div v-for="group in navigation" :key="group.label" class="admin-nav-group">
          <div class="admin-nav-label">{{ group.label }}</div>
          <RouterLink v-for="item in group.items" :key="item.name" :to="{ name: item.name }" :title="item.description" :class="{ active: route.name === item.name }" :aria-current="route.name === item.name ? 'page' : undefined" @click="menuOpen = false"><component :is="item.icon" :size="17" />{{ item.label }}</RouterLink>
        </div>
      </nav>
      <div class="admin-sidebar-note"><ShieldCheck :size="15" /><span>只显示你这家服务商的数据<br />烟火都记在团队账户里</span></div>
    </aside>
    <div class="admin-body">
      <header class="admin-topbar">
        <div class="admin-progress" :class="{ active: navigating }" aria-hidden="true" />
        <div class="admin-crumb"><button class="admin-menu-toggle" type="button" :aria-expanded="menuOpen" aria-label="切换导航" @click="menuOpen = !menuOpen"><component :is="menuOpen ? PanelLeftClose : PanelLeftOpen" :size="20" /></button><span>{{ partner.overview.partner.name }}</span><span class="admin-topbar-separator">/</span><strong>{{ route.meta.title }}</strong></div>
        <div class="admin-topbar-actions" style="margin-left:auto">
          <a class="partner-balance partner-home" href="/" title="回到一方志创作"><ExternalLink :size="14" /><span class="partner-balance-label">一方志</span></a>
          <span class="partner-balance" :title="`团队烟火 ${number(partner.overview.account?.available)}：大家在一方志创作和分给商户都扣这里`" :aria-label="`团队烟火 ${number(partner.overview.account?.available)}`"><span class="partner-balance-label">团队烟火</span><YanhuoIcon :size="16" /><span class="mg-mono">{{ number(partner.overview.account?.available) }}</span></span>
          <div class="admin-account">
            <button class="partner-account-name" type="button" title="修改团队称呼" @click="naming = true">
              <span class="admin-account-avatar">{{ (myName() || '服').slice(0, 1) }}</span>
              <span>
                <MemberIdentity :number="partner.overview.memberNumber" :name="myName() || '服务商成员'" />
                <small>{{ partnerRoles[partner.overview.role] || partner.overview.role }}<span class="partner-account-uid"> · UID {{ auth.user?.uid || '—' }}</span></small>
              </span>
            </button>
            <button type="button" class="partner-sign-out" :disabled="signingOut" @click="logout"><LogOut :size="14" />退出</button>
          </div>
        </div>
      </header>
      <main id="partner-main" class="admin-main" tabindex="-1"><RouterView v-slot="{ Component, route: page }"><Transition name="ad-page" mode="out-in"><component :is="Component" :key="page.name" /></Transition></RouterView></main>
    </div>
    <NameDialog :open="needsName || naming" :required="needsName" @close="naming = false" />
  </div>
  <RouterView v-else />
</template>
