import { createRouter, createWebHistory } from 'vue-router'
import { auth } from './stores/auth'
import LoginView from './views/LoginView.vue'
import DashboardView from './views/DashboardView.vue'
import PlaceholderView from './views/PlaceholderView.vue'
import ForbiddenView from './views/ForbiddenView.vue'
import NotFoundView from './views/NotFoundView.vue'
import CreativeWorkspaceView from './views/CreativeWorkspaceView.vue'

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
  { path: '/dashboard', name: 'dashboard', component: DashboardView, meta: { title: '运营总览', eyebrow: 'TODAY / OPERATIONS' } },
  { path: '/creative', name: 'creative', component: CreativeWorkspaceView, meta: { title: '一句话创作', eyebrow: 'AI WORKSPACE' } },
  { path: '/chat', name: 'chat', component: PlaceholderView, meta: { title: '模型对话', eyebrow: 'AI WORKSPACE', icon: '✦', description: '让模型理解你的品牌语气，快速产出下一步行动建议。' } },
  { path: '/copy/extract', name: 'copy-extract', component: PlaceholderView, meta: { title: '文案提取', eyebrow: 'CONTENT TOOLS', icon: '↗', description: '从已有素材中提取可复用的卖点、语气与结构。' } },
  { path: '/copy/rewrite', name: 'copy-rewrite', component: PlaceholderView, meta: { title: '文案重写', eyebrow: 'CONTENT TOOLS', icon: 'Aa', description: '保留核心信息，切换平台语气与传播场景。' } },
  { path: '/video/analyze', name: 'video-analyze', component: PlaceholderView, meta: { title: '视频结构分析', eyebrow: 'CONTENT TOOLS', icon: '◌', description: '拆解视频节奏、镜头意图与可复制的内容骨架。' } },
  { path: '/image/create', name: 'image-create', component: PlaceholderView, meta: { title: 'AI 图片创作', eyebrow: 'CONTENT TOOLS', icon: '▧', description: '为门店活动生成可编辑、可追踪的视觉素材。' } },
  { path: '/video/create', name: 'video-create', component: PlaceholderView, meta: { title: 'AI 视频创作', eyebrow: 'CONTENT TOOLS', icon: '▶', description: '把脚本、商品信息和品牌资产组合成短视频草稿。' } },
  { path: '/batch', name: 'batch', component: PlaceholderView, meta: { title: '批量内容生产', eyebrow: 'CONTENT TOOLS', icon: '▦', description: '一次配置，多平台批量生成内容任务。' } },
  { path: '/digital-human', name: 'digital-human', component: PlaceholderView, meta: { title: '数字人播报', eyebrow: 'CONTENT TOOLS', icon: '◎', description: '建立门店可持续使用的数字人讲解与播报模板。' } },
  { path: '/merchants', name: 'merchants', component: PlaceholderView, meta: { title: '商家与门店', eyebrow: 'ASSETS', icon: '⌂', description: '管理租户、商家资料与门店运营边界。' } },
  { path: '/brands', name: 'brands', component: PlaceholderView, meta: { title: '品牌库', eyebrow: 'ASSETS', icon: '◈', description: '沉淀品牌定位、语言风格和视觉识别资产。' } },
  { path: '/assets', name: 'assets', component: PlaceholderView, meta: { title: '素材库', eyebrow: 'ASSETS', icon: '□', description: '统一管理图片、视频、文案与门店可用素材。' } },
  { path: '/knowledge', name: 'knowledge', component: PlaceholderView, meta: { title: '知识库', eyebrow: 'ASSETS', icon: '≡', description: '把门店经验整理为模型可以调用的知识单元。' } },
  { path: '/works', name: 'works', component: PlaceholderView, meta: { title: '作品库', eyebrow: 'ASSETS', icon: '⌁', description: '查看已生成、审核中和已发布的内容作品。' } },
  { path: '/reviews', name: 'reviews', component: PlaceholderView, meta: { title: '评论与 AI 客服', eyebrow: 'OPERATIONS', icon: '◠', description: '聚合门店评论，给出可审核的智能回复建议。' } },
  { path: '/publishing', name: 'publishing', component: PlaceholderView, meta: { title: '内容发布', eyebrow: 'OPERATIONS', icon: '↑', description: '管理多平台发布队列与内容审核状态。' } },
  { path: '/analytics', name: 'analytics', component: PlaceholderView, meta: { title: '运营分析', eyebrow: 'OPERATIONS', icon: '⌗', description: '从内容、门店与活动三个视角观察增长表现。' } },
  { path: '/tasks', name: 'tasks', component: PlaceholderView, meta: { title: '任务中心', eyebrow: 'SYSTEM', icon: '✓', description: '跟踪生成、审核、发布等异步任务的进度。' } },
  { path: '/settings', name: 'settings', component: PlaceholderView, meta: { title: '系统设置', eyebrow: 'SYSTEM', icon: '⌘', description: '管理成员、角色、通知和平台连接配置。' } },
  { path: '/403', name: 'forbidden', component: ForbiddenView, meta: { title: '没有访问权限', public: true } },
  { path: '/404', name: 'not-found', component: NotFoundView, meta: { title: '页面不存在', public: true } },
  { path: '/:pathMatch(.*)*', redirect: '/404' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  if (to.meta.public) {
    if (to.name === 'login' && auth.isAuthenticated) return { name: 'dashboard' }
    return true
  }

  if (!auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta.role && !auth.hasRole(to.meta.role)) return { name: 'forbidden' }
  return true
})

export default router
