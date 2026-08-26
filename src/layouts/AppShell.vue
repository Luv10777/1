<script setup>
import { nextTick, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { auth } from '../stores/auth'
import ThemeToggle from '../components/ThemeToggle.vue'

defineProps({
  // The shell intentionally owns global navigation while pages own their content.
})

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const accountOpen = ref(false)
const pageScroll = ref(null)

const navGroups = [
  {
    label: '工作台',
    items: [
      { name: 'dashboard', label: '运营总览', path: '/dashboard', glyph: '◒' },
      { name: 'creative', label: '一句话创作', path: '/creative', glyph: '✧' },
      { name: 'billing', label: '套餐与权益', path: '/billing', glyph: '◈' },
      { name: 'ecosystem', label: '生态与治理', path: '/ecosystem', glyph: '◎' },
      { name: 'chat', label: '模型对话', path: '/chat', glyph: '✦' },
    ],
  },
  {
    label: '内容工具',
    items: [
      { name: 'copy-extract', label: '文案提取', path: '/copy/extract', glyph: '↗' },
      { name: 'copy-rewrite', label: '文案重写', path: '/copy/rewrite', glyph: 'Aa' },
      { name: 'video-analyze', label: '视频结构分析', path: '/video/analyze', glyph: '◌' },
      { name: 'image-create', label: 'AI 图片创作', path: '/image/create', glyph: '▧' },
      { name: 'video-create', label: 'AI 视频创作', path: '/video/create', glyph: '▶' },
      { name: 'batch', label: '批量内容生产', path: '/batch', glyph: '▦' },
      { name: 'digital-human', label: '数字人播报', path: '/digital-human', glyph: '◎' },
    ],
  },
  {
    label: '资产',
    items: [
      { name: 'merchants', label: '商家与门店', path: '/merchants', glyph: '⌂' },
      { name: 'brands', label: '品牌库', path: '/brands', glyph: '◈' },
      { name: 'assets', label: '素材库', path: '/assets', glyph: '□' },
      { name: 'knowledge', label: '知识库', path: '/knowledge', glyph: '≡' },
      { name: 'works', label: '作品库', path: '/works', glyph: '⌁' },
    ],
  },
  {
    label: '运营',
    items: [
      { name: 'reviews', label: '评论与 AI 客服', path: '/reviews', glyph: '◠' },
      { name: 'publishing', label: '内容发布', path: '/publishing', glyph: '↑' },
      { name: 'analytics', label: '运营分析', path: '/analytics', glyph: '⌗' },
    ],
  },
  {
    label: '系统',
    items: [
      { name: 'tasks', label: '任务中心', path: '/tasks', glyph: '✓' },
      { name: 'settings', label: '系统设置', path: '/settings', glyph: '⌘' },
    ],
  },
]

const isActive = (name) => route.name === name
const closeMenu = () => { menuOpen.value = false }

watch(() => route.fullPath, async () => {
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

      <div class="tenant-switcher">
        <div class="tenant-avatar">梧</div>
        <div class="tenant-copy">
          <span class="eyebrow">当前租户</span>
          <strong>{{ auth.user?.tenant || '梧曜增长实验室' }}</strong>
        </div>
        <span class="chevron">⌄</span>
      </div>

      <nav class="primary-nav" aria-label="主导航">
        <div v-for="group in navGroups" :key="group.label" class="nav-group">
          <p class="nav-group-label">{{ group.label }}</p>
          <RouterLink
            v-for="item in group.items"
            :key="item.name"
            :to="item.path"
            class="nav-item"
            :class="{ active: isActive(item.name) }"
            @click="closeMenu"
          >
            <span class="nav-glyph" aria-hidden="true">{{ item.glyph }}</span>
            <span>{{ item.label }}</span>
            <span v-if="item.name === 'tasks'" class="nav-count">3</span>
          </RouterLink>
        </div>
      </nav>

      <div class="sidebar-footer">
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
        <button class="icon-button mobile-menu-button" aria-label="打开导航" @click="menuOpen = true">☰</button>
        <div class="breadcrumb"><span>梧曜星枢</span><span class="breadcrumb-slash">/</span><strong>{{ route.meta.title || '运营总览' }}</strong></div>
        <div class="topbar-actions">
          <ThemeToggle />
          <button class="help-button" aria-label="帮助中心">?</button>
          <span class="topbar-divider" />
          <button class="notification-button" aria-label="通知"><span class="notification-dot" />◔</button>
          <button class="top-account" @click="accountOpen = !accountOpen"><span class="user-avatar small">{{ auth.user?.initials || '林' }}</span><span>{{ auth.user?.name || '林知夏' }}</span><span class="chevron">⌄</span></button>
        </div>
      </header>
      <div ref="pageScroll" class="page-scroll"><slot /></div>
    </main>
  </div>
</template>
