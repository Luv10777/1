<script setup>
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import ThemeToggle from '../components/ThemeToggle.vue'
import StoreFormModal from '../components/StoreFormModal.vue'
import { auth } from '../stores/auth'
import { Wallet, ArrowUpRight, Store, FilePenLine } from 'lucide-vue-next'
import { billing, number, refreshBilling } from '../stores/billing'
import { selectedStore, selectedStoreId, selectedStoreRecord, stores, storeLoading, storeError, loadStores } from '../stores/merchantContext'

const route = useRoute()
let billingTimer
const reloadBalance = () => { if (document.visibilityState === 'visible') refreshBilling() }
watch(() => [auth.user?.id, auth.tenantId], () => refreshBilling(), { immediate: true })
onMounted(() => { billingTimer = setInterval(reloadBalance, 30000); window.addEventListener('focus', reloadBalance) })
onBeforeUnmount(() => { clearInterval(billingTimer); window.removeEventListener('focus', reloadBalance) })
const router = useRouter()
const menuOpen = ref(false)
const collapsed = ref(false)
const accountOpen = ref(false)
const pageScroll = ref(null)
// 门店的新建、编辑和关店在"门店库"页面；这里只留一个入口，给还没有任何门店的管理员建第一家店。
const storeModalOpen = ref(false)
const connectedRoute = computed(() => ['products', 'knowledge', 'digital-human'].includes(route.name))
const needsStore = computed(() => billing.summary?.memberActive !== false && connectedRoute.value && (storeLoading.value || storeError.value || !selectedStoreId.value))
function openStoreForm() { storeModalOpen.value = true }
// 侧边栏和顶栏显示的是当前登录的人：名字来自会话，头像取名字的第一个字。
const userName = computed(() => auth.user?.name || '未命名用户')
const userInitial = computed(() => userName.value.slice(0, 1))
// Reviews is a long-form feed and must use the shell's normal page scrolling.
// Keeping it in the edge-workspace list applies overflow:hidden and prevents
// the review stream from being scrolled once it exceeds the viewport.
const edgeWorkspace = computed(() => ['image-create-poster', 'image-create-product-set', 'video-workbench', 'assets', 'messages'].includes(route.name))
const keyboard = (event) => {
  if (event.key === 'Escape') { menuOpen.value = false; accountOpen.value = false }
}
onMounted(() => window.addEventListener('keydown', keyboard))
onBeforeUnmount(() => window.removeEventListener('keydown', keyboard))
watch(() => route.fullPath, () => { expandActiveGroup(); closeMenu(); pageScroll.value?.scrollTo({ top: 0 }); refreshBilling() })

const NAV_STORAGE_KEY = 'wuyao-sidebar-groups-v2'
const navIcons = { workspace: 'dashboard', analytics: 'monitoring', acquisition: 'person_search', content: 'edit_note', publishing: 'publish', 'customer-service': 'support_agent', geo: 'location_on', assets: 'folder', billing: 'card_membership', system: 'settings' }

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
      { name: 'diagnosis', label: '诊断报告', path: '/analytics/diagnosis' },
      { name: 'analytics-platforms', label: '数据接口管理', path: '/analytics/platforms' },
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
      { name: 'image-create', label: '图片创作', path: '/image/create' },
      { name: 'video-create', label: '视频创作', path: '/video/create' },
      { name: 'digital-human', label: '实景直播', path: '/digital-human' },
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
      { name: 'geo-visibility', label: '可见度报告', path: '/geo/visibility' },
    ],
  },
  {
    key: 'assets',
    label: '资产中心',
    items: [
      { name: 'brands', label: '品牌库', path: '/brands' },
      { name: 'merchants', label: '门店库', path: '/merchants' },
      { name: 'products', label: '商品库', path: '/assets/products' },
      { name: 'knowledge', label: '知识库', path: '/knowledge' },
      { name: 'assets', label: '素材库', path: '/assets' },
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
      { name: 'team', label: '员工管理', path: '/team', ownerOnly: true },
      { name: 'settings', label: '系统设置', path: '/settings' },
      { name: 'help', label: '帮助与反馈', path: '/help' },
    ],
  },
].filter(group => !['workspace', 'analytics', 'acquisition', 'publishing', 'customer-service', 'geo'].includes(group.key))

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
const saveExpandedGroups = () => {
  try {
    localStorage.setItem(NAV_STORAGE_KEY, JSON.stringify([...expandedGroups.value]))
  } catch {
    // Navigation remains functional when local storage is unavailable.
  }
}

const toggleGroup = (key) => {
  if (collapsed.value) {
    collapsed.value = false
    expandedGroups.value = new Set([...expandedGroups.value, key])
    saveExpandedGroups()
    return
  }
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

// 只有管理员能用的入口对店员收起；接口本身也会拒绝。
const canSee = item => !item.ownerOnly || auth.isOwner
const closeMenu = () => { menuOpen.value = false }
// 先等会话清掉再跳转，否则路由守卫看到的还是已登录，会把人送回内容工具而不是登录页。
const logout = async () => { await auth.logout(); router.push({ name: 'login' }) }
</script>

<template>
  <div class="app-frame paper-app">
    <div class="mobile-scrim" :class="{ 'is-visible': menuOpen }" @click="closeMenu" />
    <aside class="sidebar paper-sidebar" :class="{ 'is-open': menuOpen, 'is-collapsed': collapsed }">
      <div class="brand-lockup paper-brand">
        <RouterLink to="/image/create" class="brand-link" aria-label="一方志内容工具"><span class="brand-mark seal-mark"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><span class="brand-copy"><strong>一方志</strong><small>为每一方商家立传</small></span></RouterLink>
        <button class="icon-button sidebar-close" aria-label="关闭导航" @click="closeMenu">×</button>
      </div>
      <div class="tenant-switcher paper-tenant" :class="{ switchable: stores.length > 1 }">
        <span class="tenant-avatar">{{ selectedStoreRecord?.name?.slice(0, 1) || '店' }}</span>
        <span class="tenant-copy"><small>当前门店</small><strong>{{ selectedStore }}</strong></span>
        <template v-if="stores.length > 1">
          <span class="chevron" aria-hidden="true">⌄</span>
          <!-- 透明的原生下拉盖在整块上：点哪里都能切换，键盘和读屏也照常可用。 -->
          <select v-model="selectedStoreId" class="tenant-select" aria-label="切换门店" :disabled="storeLoading">
            <option v-for="store in stores" :key="store.id" :value="store.id">{{ store.name }}</option>
          </select>
        </template>
      </div>
      <nav class="primary-nav paper-nav" aria-label="主导航">
        <div
          v-for="group in navGroups"
          :key="group.key"
          class="nav-group"
          :class="{ 'has-active-item': isGroupActive(group) }"
        >
          <RouterLink
            v-if="group.path"
            :to="group.path"
            :aria-label="group.label"
            :title="group.label"
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
              :aria-expanded="!collapsed && isGroupExpanded(group.key)"
              :aria-label="group.label"
              :title="group.label"
              :aria-controls="`nav-group-${group.key}`"
              @click="toggleGroup(group.key)"
            >
              <span class="material-symbols-outlined nav-group-icon" aria-hidden="true">{{ navIcons[group.key] || 'folder' }}</span>
              <span class="nav-group-label">{{ group.label }}</span>
              <span class="nav-group-chevron" :class="{ expanded: isGroupExpanded(group.key) }" aria-hidden="true">⌄</span>
            </button>
            <div
              v-show="!collapsed && isGroupExpanded(group.key)"
              :id="`nav-group-${group.key}`"
              class="nav-group-items"
              :class="{ expanded: isGroupExpanded(group.key) }"
              :aria-hidden="collapsed || !isGroupExpanded(group.key)"
            >
              <div class="nav-group-items-inner">
                <RouterLink
                  v-for="item in group.items.filter(canSee)"
                  :key="item.name"
                  :to="item.path"
                  class="nav-item paper-nav-item"
                  :class="{ active: isActive(item.name) }"
                  @click="closeMenu"
                >
                  <span class="nav-item-label">{{ item.label }}</span>
                </RouterLink>
              </div>
            </div>
          </template>
        </div>
      </nav>

      <div class="sidebar-footer paper-sidebar-footer">
        <RouterLink :to="{ path: '/billing', query: { topup: '1' } }" class="sidebar-yanhuo" :title="billing.error ? '烟火账户暂时无法读取' : billing.summary ? number(billing.summary.available) + ' 烟火 · 套餐与充值' : '读取烟火账户'" @click="closeMenu">
          <Wallet :size="18" class="sidebar-yanhuo-icon" />
          <div class="sidebar-yanhuo-copy"><div><span>可用烟火</span><ArrowUpRight :size="14" /></div><strong>{{ billing.error ? '--' : billing.summary ? number(billing.summary.available) : '...' }}<small>烟火</small></strong><p>{{ billing.error ? '账户暂不可读' : billing.summary?.memberActive ? billing.summary.plan?.name : billing.summary?.plan ? '会员已到期' : '尚未开通会员' }}<span v-if="billing.summary?.held"> · 预占 {{ number(billing.summary.held) }}</span></p><span class="sidebar-yanhuo-action">套餐与充值</span></div>
        </RouterLink>
        <div class="account-wrap"><button class="account-button" @click="accountOpen = !accountOpen"><span class="user-avatar">{{ userInitial }}</span><span class="account-copy"><strong>{{ userName }}</strong><small v-if="auth.user?.role">{{ auth.user.role }}</small></span><span class="chevron">⌄</span></button><div v-if="accountOpen" class="account-menu"><button @click="router.push('/settings'); accountOpen = false">账户设置</button><button @click="logout">退出登录</button></div></div>
      </div>
    </aside>
    <main class="main-area paper-main">
      <header class="topbar paper-topbar">
        <div class="topbar-inner">
          <button class="icon-button mobile-menu-button" aria-label="打开导航" @click="menuOpen = true">☰</button>
          <button class="icon-button desktop-collapse" :aria-label="collapsed ? '展开侧栏' : '折叠侧栏'" :aria-expanded="!collapsed" @click="collapsed = !collapsed">☰</button>
          <RouterLink to="/image/create" class="header-brand" aria-label="一方志内容工具"><span class="seal-mark"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><strong>一方志</strong><small>为每一方商家立传</small></RouterLink>
          <div class="breadcrumb"><span>一方志</span><b>/</b><strong>{{ route.meta.title || '内容工具' }}</strong></div>
          <div class="topbar-actions">
            <select v-model="selectedStoreId" class="header-store" aria-label="切换门店" :disabled="storeLoading || !stores.length"><option v-if="!stores.length" :value="null">{{ storeLoading ? '加载门店…' : '尚无门店' }}</option><option v-for="store in stores" :key="store.id" :value="store.id">{{ store.name }}</option></select>
            <RouterLink to="/merchant-alliance" class="topbar-entry" :class="{ 'is-current': route.name === 'merchant-alliance' }"><Store :size="15" aria-hidden="true" />商家入驻</RouterLink>
            <RouterLink to="/information-publishing" class="topbar-entry topbar-entry-primary"><FilePenLine :size="15" aria-hidden="true" />信息发布</RouterLink>
            <ThemeToggle />
            <button class="help-button" aria-label="帮助中心" @click="router.push('/help')">?</button>
            <button class="notification-button" aria-label="通知" @click="router.push('/notifications')">◌<span class="notification-dot" /></button>
            <button class="top-account" aria-label="账户设置" @click="router.push('/settings')"><span class="user-avatar small">{{ userInitial }}</span><span class="top-account-name">{{ userName }}</span></button>
          </div>
        </div>
      </header>
      <div ref="pageScroll" class="page-scroll" :class="{ 'page-scroll-workspace': edgeWorkspace }">
        <section v-if="needsStore" class="store-context-state" :aria-busy="storeLoading">
          <span class="material-symbols-outlined">storefront</span>
          <h2>{{ storeLoading ? '正在加载门店…' : storeError ? '门店暂时无法加载' : auth.isOwner ? '先创建你的第一家门店' : '还没有分配给你的门店' }}</h2>
          <p>{{ storeError || (storeLoading ? '正在获取你有权限访问的门店。' : auth.isOwner ? '创建门店后，即可管理商品、维护知识库并配置实景直播。' : '请联系管理员在“员工管理”里把门店分配给你，分配后刷新即可使用。') }}</p>
          <button v-if="storeError" class="secondary-button compact" @click="loadStores">重新加载</button>
          <button v-else-if="!storeLoading && auth.isOwner" class="primary-button compact" @click="openStoreForm()">＋ 新建门店</button>
          <button v-else-if="!storeLoading" class="secondary-button compact" @click="loadStores">刷新</button>
        </section>
        <slot v-else />
      </div>
      <StoreFormModal v-if="storeModalOpen" @close="storeModalOpen = false" />
      <nav class="mobile-bottom-nav" aria-label="移动端快捷导航"><RouterLink to="/image/create" :class="{ active: String(route.name).startsWith('image-create') }"><span class="material-symbols-outlined" aria-hidden="true">edit_note</span><small>创作</small></RouterLink><RouterLink to="/digital-human" :class="{ active: route.name === 'digital-human' }"><span class="material-symbols-outlined" aria-hidden="true">live_tv</span><small>直播</small></RouterLink><RouterLink to="/information-publishing" class="mobile-bottom-primary" aria-label="信息发布"><FilePenLine :size="21" aria-hidden="true" /></RouterLink><RouterLink to="/works" :class="{ active: route.name === 'works' }"><span class="material-symbols-outlined" aria-hidden="true">folder</span><small>作品库</small></RouterLink><RouterLink to="/merchant-alliance" :class="{ active: route.name === 'merchant-alliance' }"><Store :size="21" aria-hidden="true" /><small>入驻</small></RouterLink></nav>
    </main>
  </div>
</template>

<style scoped>
.store-context-state { min-height: 55vh; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 36px; text-align: center; }
.store-context-state > span { font-size: 36px; color: var(--muted, #8d8478); }
.store-context-state h2 { margin: 20px 0 8px; font-size: 22px; }
.store-context-state p { margin: 0 0 22px; font-size: 14px; color: var(--muted, #8d8478); }
.tenant-switcher { position: relative; }
.tenant-switcher.switchable:hover { border-color: var(--line-strong); }
.tenant-switcher.switchable:has(.tenant-select:focus-visible) { outline: 2px solid var(--color-accent); outline-offset: 2px; }
.tenant-select { position: absolute; inset: 0; width: 100%; height: 100%; margin: 0; border: 0; opacity: 0; cursor: pointer; appearance: none; }
.sidebar-footer .account-wrap { margin-top: 14px; }
.topbar-entry { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 32px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 4px; background: var(--night-panel); color: var(--ink); font-size: 12px; white-space: nowrap; }
.topbar-entry:hover, .topbar-entry.is-current { border-color: var(--color-accent); color: var(--color-accent-text); }
.topbar-entry-primary { border-color: var(--color-accent); background: var(--color-accent); color: var(--color-on-accent); }
.topbar-entry-primary:hover { border-color: var(--color-accent-hover); background: var(--color-accent-hover); color: var(--color-on-accent); }
@media (max-width: 1100px) { .topbar-actions { gap: 8px; } .topbar .header-store { max-width: 105px; } .topbar .help-button { display: none; } }
@media (max-width: 860px) { .topbar .header-store, .topbar .notification-button, .topbar .top-account { display: none; } }
@media (max-width: 640px) { .topbar-entry { padding: 6px 8px; font-size: 11px; } .topbar-entry svg { display: none; } .topbar .header-brand strong { display: none; } }
</style>
