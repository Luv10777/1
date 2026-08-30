<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { auth } from '../stores/auth'
import RouteTabs from '../components/RouteTabs.vue'
import ThemeToggle from '../components/ThemeToggle.vue'

defineProps({
  // The shell intentionally owns global navigation while pages own their content.
})

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const accountOpen = ref(false)
const pageScroll = ref(null)
const NAV_STORAGE_KEY = 'wuyao-sidebar-groups-v2'
const quickSearchOpen = ref(false)
const quickSearch = ref('')

const quickSearchItems = [
  { name: 'dashboard', label: '大屏总览', path: '/dashboard' },
  { name: 'creative', label: '一句话创作', path: '/creative' },
  { name: 'analytics', label: '各平台数据看板', path: '/analytics' },
  { name: 'publishing', label: '内容发布', path: '/publishing' },
  { name: 'assets', label: '素材库', path: '/assets' },
  { name: 'tasks', label: '任务中心', path: '/tasks' },
]

const navIcons = { workspace: 'dashboard', analytics: 'monitoring', acquisition: 'person_search', content: 'auto_awesome', publishing: 'publish', 'customer-service': 'support_agent', geo: 'location_on', assets: 'folder', billing: 'card_membership', system: 'settings' }
const navItemIcons = { dashboard: 'dashboard', creative: 'auto_awesome', analytics: 'monitoring', diagnosis: 'insights', 'analytics-platforms': 'link', acquisition: 'person_search', 'image-create': 'image', 'video-create': 'movie', 'digital-human': 'record_voice_over', 'copy-rewrite': 'edit_note', 'video-analyze': 'query_stats', publishing: 'publish', 'matrix-publishing': 'hub', 'publishing-plan': 'calendar_month', 'publishing-platforms': 'link', messages: 'chat', reviews: 'reviews', 'service-rules': 'rule', 'geo-brand': 'location_on', 'geo-services': 'workspace_premium', 'geo-keywords': 'key', 'geo-visibility': 'visibility', merchants: 'storefront', brands: 'branding_watermark', assets: 'folder', knowledge: 'menu_book', works: 'gallery_thumbnail', billing: 'card_membership', 'merchant-alliance': 'handshake', notifications: 'notifications', tasks: 'task_alt', settings: 'settings', help: 'help_outline' }

const navGroups = [
  {
    key: 'workspace',
    label: '工作台',
    items: [
      { name: 'dashboard', label: '大屏总览', path: '/dashboard' },
      { name: 'creative', label: '一句话创作', path: '/creative' },
    ],
  },
  {
    key: 'analytics',
    label: '运营分析',
    items: [
      { name: 'analytics', label: '各平台数据看板', path: '/analytics' },
      { name: 'diagnosis', label: 'AI 诊断报告', path: '/analytics/diagnosis' },
      { name: 'analytics-platforms', label: '关联平台管理', path: '/analytics/platforms' },
    ],
  },
  {
    key: 'acquisition',
    name: 'acquisition',
    label: '获客中心',
    path: '/acquisition',
  },
  {
    key: 'content',
    label: '内容工具',
    items: [
      { name: 'image-create', label: 'AI 图片创作', path: '/image/create' },
      { name: 'video-create', label: 'AI 视频创作', path: '/video/create' },
      { name: 'digital-human', label: 'AI实景直播', path: '/digital-human' },
      { name: 'copy-rewrite', label: '文案提取 / 仿写 / 重写', path: '/copy/rewrite' },
      { name: 'video-analyze', label: '视频反推', path: '/video/analyze' },
    ],
  },
  {
    key: 'publishing',
    label: '全网发布',
    items: [
      { name: 'publishing', label: '内容发布', path: '/publishing' },
      { name: 'matrix-publishing', label: '矩阵发布', path: '/publishing/matrix' },
      { name: 'publishing-plan', label: '发布计划', path: '/publishing/plan' },
      { name: 'publishing-platforms', label: '关联平台管理', path: '/publishing/platforms' },
    ],
  },
  {
    key: 'customer-service',
    label: '智能客服',
    items: [
      { name: 'messages', label: '私信会话', path: '/service/messages' },
      { name: 'reviews', label: '评论 / 差评自动回复', path: '/reviews' },
      { name: 'service-rules', label: '客服规则配置', path: '/service/rules' },
    ],
  },
  {
    key: 'geo',
    label: 'GEO 增长',
    items: [
      { name: 'geo-brand', label: '品牌信息设置', path: '/geo/brand' },
      { name: 'geo-services', label: '增值服务', path: '/geo/services' },
      { name: 'geo-keywords', label: '关键词监控', path: '/geo/keywords' },
      { name: 'geo-visibility', label: 'AI 可见度报告', path: '/geo/visibility' },
    ],
  },
  {
    key: 'assets',
    label: '资产中心',
    items: [
      { name: 'merchants', label: '门店信息', path: '/merchants' },
      { name: 'brands', label: '品牌库', path: '/brands' },
      { name: 'assets', label: '素材库', path: '/assets' },
      { name: 'knowledge', label: '知识库', path: '/knowledge' },
      { name: 'works', label: '作品库', path: '/works' },
    ],
  },
  {
    key: 'billing',
    label: '套餐与权益',
    items: [
      { name: 'billing', label: '套餐与权益', path: '/billing' },
      { name: 'merchant-alliance', label: '商家联盟', path: '/merchant-alliance' },
    ],
  },
  {
    key: 'system',
    label: '系统',
    items: [
      { name: 'notifications', label: '消息', path: '/notifications' },
      { name: 'tasks', label: '任务中心', path: '/tasks' },
      { name: 'settings', label: '系统设置', path: '/settings' },
      { name: 'help', label: '帮助与反馈', path: '/help' },
    ],
  },
]

const getRouteGroup = () => navGroups.find(group => (
  group.name === route.name || route.meta.navGroup === group.key || group.items?.some(item => item.name === route.name)
))

const getInitialExpandedGroups = () => {
  let savedGroups = []
  try {
    const storedGroups = JSON.parse(localStorage.getItem(NAV_STORAGE_KEY) || '[]')
    if (Array.isArray(storedGroups)) savedGroups = storedGroups
  } catch {
    // Ignore malformed or unavailable local storage and use the active group.
  }

  const knownKeys = new Set(navGroups.map(group => group.key))
  const initialGroups = new Set(savedGroups.filter(key => knownKeys.has(key)))
  const activeGroup = getRouteGroup()
  if (activeGroup?.items?.length) initialGroups.add(activeGroup.key)
  else if (!activeGroup) initialGroups.add(navGroups[0].key)
  return initialGroups
}

const expandedGroups = ref(getInitialExpandedGroups())
const isActive = (name) => route.name === name
const isGroupActive = (group) => isActive(group.name) || route.meta.navGroup === group.key || group.items?.some(item => isActive(item.name))
const isGroupExpanded = (key) => expandedGroups.value.has(key)
const closeMenu = () => { menuOpen.value = false }
const openQuickSearch = () => { quickSearchOpen.value = true }
const closeQuickSearch = () => { quickSearchOpen.value = false; quickSearch.value = '' }
const filteredQuickSearchItems = () => {
  const query = quickSearch.value.trim().toLowerCase()
  if (!query) return quickSearchItems
  return quickSearchItems.filter(item => item.label.toLowerCase().includes(query) || item.name.includes(query))
}
const goToQuickSearchItem = (path) => { router.push(path); closeQuickSearch() }

const handleQuickSearchShortcut = (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    openQuickSearch()
  }
  if (event.key === 'Escape' && quickSearchOpen.value) closeQuickSearch()
}

onMounted(() => window.addEventListener('keydown', handleQuickSearchShortcut))
onBeforeUnmount(() => window.removeEventListener('keydown', handleQuickSearchShortcut))

const saveExpandedGroups = () => {
  try {
    localStorage.setItem(NAV_STORAGE_KEY, JSON.stringify([...expandedGroups.value]))
  } catch {
    // Navigation remains functional when local storage is unavailable.
  }
}

const toggleGroup = (key) => {
  const nextGroups = new Set(expandedGroups.value)
  if (nextGroups.has(key)) nextGroups.delete(key)
  else nextGroups.add(key)
  expandedGroups.value = nextGroups
  saveExpandedGroups()
}

const expandActiveGroup = () => {
  const activeGroup = getRouteGroup()
  if (!activeGroup?.items?.length || expandedGroups.value.has(activeGroup.key)) return
  expandedGroups.value = new Set([...expandedGroups.value, activeGroup.key])
  saveExpandedGroups()
}

watch(() => route.fullPath, async () => {
  expandActiveGroup()
  await nextTick()
  pageScroll.value?.scrollTo({ top: 0, left: 0 })
})

const logout = () => {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="app-frame">
    <div class="mobile-scrim" :class="{ 'is-visible': menuOpen }" @click="closeMenu" />
    <aside class="sidebar" :class="{ 'is-open': menuOpen }">
      <div class="brand-lockup">
        <div class="brand-mark" aria-hidden="true"><span /><span /><span /></div>
        <div>
          <p class="brand-name">梧曜星枢</p>
          <p class="brand-caption">AI GROWTH OS <span>·</span> V0.1</p>
        </div>
        <button class="icon-button sidebar-close" aria-label="关闭导航" @click="closeMenu">×</button>
      </div>

      <nav class="primary-nav" aria-label="主导航">
        <div
          v-for="group in navGroups"
          :key="group.key"
          class="nav-group"
          :class="{ 'has-active-item': isGroupActive(group) }"
        >
          <RouterLink
            v-if="group.path"
            :to="group.path"
            class="nav-group-trigger nav-group-direct"
            @click="closeMenu"
          >
            <span class="material-symbols-outlined nav-group-icon" aria-hidden="true">{{ navIcons[group.key] || 'folder' }}</span>
            <span class="nav-group-label">{{ group.label }}</span>
          </RouterLink>
          <template v-else>
            <button
              class="nav-group-trigger"
              type="button"
              :aria-expanded="isGroupExpanded(group.key)"
              :aria-controls="`nav-group-${group.key}`"
              @click="toggleGroup(group.key)"
            >
              <span class="material-symbols-outlined nav-group-icon" aria-hidden="true">{{ navIcons[group.key] || 'folder' }}</span>
              <span class="nav-group-label">{{ group.label }}</span>
              <span class="nav-group-chevron" :class="{ expanded: isGroupExpanded(group.key) }" aria-hidden="true">⌄</span>
            </button>
            <div
              :id="`nav-group-${group.key}`"
              class="nav-group-items"
              :class="{ expanded: isGroupExpanded(group.key) }"
              :aria-hidden="!isGroupExpanded(group.key)"
            >
              <div class="nav-group-items-inner">
                <RouterLink
                  v-for="item in group.items"
                  :key="item.name"
                  :to="item.path"
                  class="nav-item"
                  :class="{ active: isActive(item.name) }"
                  @click="closeMenu"
                >
                  <span class="material-symbols-outlined nav-item-icon" aria-hidden="true">{{ navItemIcons[item.name] || 'chevron_right' }}</span>
                  <span class="nav-item-label">{{ item.label }}</span>
                  <span v-if="item.name === 'tasks'" class="nav-count">3</span>
                </RouterLink>
              </div>
            </div>
          </template>
        </div>
      </nav>

      <div class="sidebar-footer">
        <RouterLink to="/creative" class="sidebar-create-button"><span class="material-symbols-outlined" aria-hidden="true">add</span><span>新建创作任务</span></RouterLink>
        <div class="status-capsule">
          <span class="status-pulse" />
          <div>
            <span class="eyebrow">星枢状态舱</span>
            <strong>所有系统运行中</strong>
          </div>
          <span class="mono status-time">99.9%</span>
        </div>
        <div class="account-wrap">
          <button class="account-button" @click="accountOpen = !accountOpen">
            <span class="user-avatar">{{ auth.user?.initials || '林' }}</span>
            <span class="account-copy"><strong>{{ auth.user?.name || '林知夏' }}</strong><small>{{ auth.user?.role || '运营管理员' }}</small></span>
            <span class="chevron">⌄</span>
          </button>
          <div v-if="accountOpen" class="account-menu">
            <button @click="router.push('/settings'); accountOpen = false">账户设置</button>
            <button @click="logout">退出登录</button>
          </div>
        </div>
      </div>
    </aside>

    <main class="main-area">
      <header class="topbar">
        <div class="topbar-inner">
          <button class="icon-button mobile-menu-button" aria-label="打开导航" @click="menuOpen = true">☰</button>
          <div class="topbar-actions">
            <button class="topbar-search" type="button" aria-label="打开快速导航" @click="openQuickSearch">
              <span class="topbar-search-icon" aria-hidden="true">⌕</span>
              <span class="topbar-search-label">搜索页面</span>
              <kbd>⌘ K</kbd>
            </button>
            <ThemeToggle />
            <button class="help-button" aria-label="帮助中心">?</button>
            <button class="notification-button" aria-label="通知"><span class="notification-dot" />◔</button>
          </div>
        </div>
      </header>
      <RouteTabs />
      <div ref="pageScroll" class="page-scroll"><slot /></div>
      <div v-if="quickSearchOpen" class="quick-search-scrim" @click="closeQuickSearch">
        <section class="quick-search" role="dialog" aria-modal="true" aria-label="快速导航" @click.stop>
          <div class="quick-search-head">
            <span class="quick-search-icon" aria-hidden="true">⌕</span>
            <input v-model="quickSearch" autofocus type="search" placeholder="搜索页面或功能" @keydown.esc="closeQuickSearch" @keydown.enter="filteredQuickSearchItems()[0] && goToQuickSearchItem(filteredQuickSearchItems()[0].path)">
            <kbd>ESC</kbd>
          </div>
          <div class="quick-search-results">
            <button v-for="item in filteredQuickSearchItems()" :key="item.name" type="button" class="quick-search-result" @click="goToQuickSearchItem(item.path)">
              <span class="quick-search-result-mark">↗</span>
              <span>{{ item.label }}</span>
              <span class="quick-search-result-path">{{ item.path }}</span>
            </button>
            <p v-if="!filteredQuickSearchItems().length" class="quick-search-empty">没有匹配的页面</p>
          </div>
        </section>
      </div>
      <nav class="mobile-bottom-nav" aria-label="移动端快捷导航">
        <RouterLink to="/dashboard" :class="{ active: route.name === 'dashboard' }"><span class="material-symbols-outlined">dashboard</span><span>总览</span></RouterLink>
        <RouterLink to="/creative" :class="{ active: route.name === 'creative' }"><span class="material-symbols-outlined">auto_awesome</span><span>创作</span></RouterLink>
        <RouterLink to="/creative" class="mobile-bottom-primary"><span class="material-symbols-outlined">add</span></RouterLink>
        <RouterLink to="/assets" :class="{ active: route.name === 'assets' }"><span class="material-symbols-outlined">folder</span><span>素材</span></RouterLink>
        <RouterLink to="/analytics" :class="{ active: route.name === 'analytics' }"><span class="material-symbols-outlined">monitoring</span><span>分析</span></RouterLink>
      </nav>
    </main>
  </div>
</template>
