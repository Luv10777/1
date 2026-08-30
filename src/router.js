import { createRouter, createWebHistory } from 'vue-router'
import { auth } from './stores/auth'
import LoginView from './views/LoginView.vue'
import DashboardView from './views/DashboardView.vue'
import PlaceholderView from './views/PlaceholderView.vue'
import ForbiddenView from './views/ForbiddenView.vue'
import NotFoundView from './views/NotFoundView.vue'
import CreativeWorkspaceView from './views/CreativeWorkspaceView.vue'
import ConsumerPreviewView from './views/ConsumerPreviewView.vue'
import BillingView from './views/BillingView.vue'
import EcosystemView from './views/EcosystemView.vue'
import TaskCenterView from './views/TaskCenterView.vue'
import BrandsView from './views/BrandsView.vue'
import AssetsView from './views/AssetsView.vue'
import KnowledgeView from './views/KnowledgeView.vue'
import WorksView from './views/WorksView.vue'
import ImageCreateView from './views/ImageCreateView.vue'
import VideoCreateView from './views/VideoCreateView.vue'
import VideoWorkbenchView from './views/VideoWorkbenchView.vue'
import DigitalHumanStudioView from './views/DigitalHumanStudioView.vue'
import LiveStudioView from './views/LiveStudioView.vue'

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
  { path: '/consumer', name: 'consumer', component: ConsumerPreviewView, meta: { public: true, title: '消费者预览' } },
  { path: '/billing', name: 'billing', component: BillingView, meta: { title: '套餐与权益', eyebrow: 'SAAS COMMERCIAL' } },
  { path: '/ecosystem', name: 'ecosystem', component: EcosystemView, meta: { title: '生态与治理', eyebrow: 'OPEN ECOSYSTEM' }, },
  { path: '/dashboard', name: 'dashboard', component: DashboardView, meta: { title: '大屏总览', eyebrow: 'TODAY / OPERATIONS' } },
  { path: '/creative', name: 'creative', component: CreativeWorkspaceView, meta: { title: '一句话创作', eyebrow: 'AI WORKSPACE' } },
  { path: '/chat', name: 'chat', component: PlaceholderView, meta: { title: '模型对话', eyebrow: 'AI WORKSPACE', icon: '✦', description: '让模型理解你的品牌语气，快速产出下一步行动建议。' } },
  { path: '/copy/extract', name: 'copy-extract', component: PlaceholderView, meta: { title: '文案提取', eyebrow: 'CONTENT TOOLS', icon: '↗', description: '从已有素材中提取可复用的卖点、语气与结构。' } },
  { path: '/copy/rewrite', name: 'copy-rewrite', component: PlaceholderView, meta: { title: '文案提取 / 仿写 / 重写', eyebrow: 'CONTENT TOOLS', icon: 'Aa', description: '从已有素材提取文案，在保留核心信息的基础上完成仿写与多平台重写。' } },
  { path: '/video/analyze', name: 'video-analyze', component: PlaceholderView, meta: { title: '视频反推', eyebrow: 'CONTENT TOOLS', icon: '◌', description: '反向拆解视频节奏、镜头意图与可复制的内容骨架。' } },
  { path: '/image/create', name: 'image-create', component: ImageCreateView, meta: { title: 'AI 图片创作', eyebrow: 'CONTENT TOOLS', icon: '▧', description: '为门店活动生成可编辑、可追踪的视觉素材。', navGroup: 'content' } },
  { path: '/image/create/poster', name: 'image-create-poster', component: ImageCreateView, meta: { title: '营销海报', eyebrow: 'CONTENT TOOLS', icon: '✦', description: '为一次活动定下主视觉。', navGroup: 'content' } },
  { path: '/image/create/product-set', name: 'image-create-product-set', component: ImageCreateView, meta: { title: '产品套图', eyebrow: 'CONTENT TOOLS', icon: '▦', description: '围绕同一件商品生成一组有秩序的画面。', navGroup: 'content' } },
  { path: '/video/create', name: 'video-create', component: VideoCreateView, meta: { title: 'AI 视频创作', eyebrow: 'CONTENT TOOLS', icon: '▶', description: '把脚本、商品信息和品牌资产组合成短视频草稿。' } },
  { path: '/video/workbench', name: 'video-workbench', component: VideoWorkbenchView, meta: { title: '视频工作台', eyebrow: 'CONTENT TOOLS', icon: 'movie_edit', description: '从一句话开始，把脚本、商品素材和品牌规则编排成可发布的短视频。' } },
  { path: '/batch', name: 'batch', component: PlaceholderView, meta: { title: '批量内容生产', eyebrow: 'CONTENT TOOLS', icon: '▦', description: '一次配置，多平台批量生成内容任务。' } },
  { path: '/digital-human', name: 'digital-human', component: LiveStudioView, meta: { title: 'AI实景直播', eyebrow: 'CONTENT TOOLS', icon: '◎', description: '手机拍真实场景开播，AI 负责话术、声音克隆与弹幕自动回复。' } },
  { path: '/digital-human/studio', name: 'digital-human-studio', component: DigitalHumanStudioView, meta: { title: '数字人摄影棚', eyebrow: 'CONTENT TOOLS', icon: 'record_voice_over', description: '选定专属出镜人，输入文案或语音，生成真人级数字人播报视频。' } },
  { path: '/merchants', name: 'merchants', component: PlaceholderView, meta: { title: '门店信息', eyebrow: 'ASSET CENTER', icon: '⌂', description: '管理商家资料、门店信息与运营边界。' } },
  { path: '/brands', name: 'brands', component: BrandsView, meta: { title: '品牌库', eyebrow: 'ASSETS', icon: '◈', description: '沉淀品牌定位、语言风格和视觉识别资产。' } },
  { path: '/assets', name: 'assets', component: AssetsView, meta: { title: '素材库', eyebrow: 'ASSETS', icon: '□', description: '统一管理图片、视频、文案与门店可用素材。' } },
  { path: '/knowledge', name: 'knowledge', component: KnowledgeView, meta: { title: '知识库', eyebrow: 'ASSETS', icon: '≡', description: '把门店经验整理为模型可以调用的知识单元。' } },
  { path: '/works', name: 'works', component: WorksView, meta: { title: '作品库', eyebrow: 'ASSETS', icon: '⌁', description: '查看已生成、审核中和已发布的内容作品。' } },
  { path: '/reviews', name: 'reviews', component: PlaceholderView, meta: { title: '评论 / 差评自动回复', eyebrow: 'CUSTOMER SERVICE', icon: '◠', description: '聚合门店评论与差评，给出可审核的智能回复建议。' } },
  { path: '/publishing', name: 'publishing', component: PlaceholderView, meta: { title: '内容发布', eyebrow: 'OPERATIONS', icon: '↑', description: '管理多平台发布队列与内容审核状态。' } },
  { path: '/publishing/matrix', name: 'matrix-publishing', component: PlaceholderView, meta: { title: '矩阵发布', eyebrow: 'OMNICHANNEL PUBLISHING', icon: '▦', description: '统一编排多个平台和账号的内容发布任务。' } },
  { path: '/publishing/plan', name: 'publishing-plan', component: PlaceholderView, meta: { title: '发布计划', eyebrow: 'OMNICHANNEL PUBLISHING', icon: '◷', description: '规划内容日历、发布时间和审核节点。' } },
  { path: '/publishing/platforms', name: 'publishing-platforms', component: PlaceholderView, meta: { title: '关联平台管理', eyebrow: 'OMNICHANNEL PUBLISHING', icon: '⌁', description: '管理用于内容发布的平台账号和授权状态。' } },
  { path: '/analytics', name: 'analytics', component: PlaceholderView, meta: { title: '各平台数据看板', eyebrow: 'OPERATIONS ANALYTICS', icon: '⌗', description: '汇总各平台的内容、门店与活动增长表现。' } },
  { path: '/analytics/diagnosis', name: 'diagnosis', component: PlaceholderView, meta: { title: 'AI 诊断报告', eyebrow: 'OPERATIONS ANALYTICS', icon: '✦', description: '通过 AI 识别运营问题、增长机会和建议动作。' } },
  { path: '/analytics/platforms', name: 'analytics-platforms', component: PlaceholderView, meta: { title: '关联平台管理', eyebrow: 'OPERATIONS ANALYTICS', icon: '⌁', description: '管理用于数据分析的平台连接与同步状态。' } },
  { path: '/acquisition', name: 'acquisition', component: PlaceholderView, meta: { title: '获客中心', eyebrow: 'CUSTOMER ACQUISITION', icon: '◎', description: '集中承接获客渠道、潜在客户和转化动作；具体功能将在业务范围明确后逐步开放。' } },
  { path: '/service/messages', name: 'messages', component: PlaceholderView, meta: { title: '私信会话', eyebrow: 'CUSTOMER SERVICE', icon: '◠', description: '聚合各平台私信，集中查看并处理客户会话。' } },
  { path: '/service/rules', name: 'service-rules', component: PlaceholderView, meta: { title: '客服规则配置', eyebrow: 'CUSTOMER SERVICE', icon: '⌘', description: '配置智能回复范围、审核条件和客服升级规则。' } },
  { path: '/geo/brand', name: 'geo-brand', component: PlaceholderView, meta: { title: '品牌信息设置', eyebrow: 'GEO GROWTH', icon: '◎', description: '维护用于生成式搜索理解和引用的品牌基础信息。' } },
  { path: '/geo/services', name: 'geo-services', component: PlaceholderView, meta: { title: '增值服务', eyebrow: 'GEO GROWTH', icon: '◇', description: '查看品牌在生成式搜索场景中的增长服务。' } },
  { path: '/geo/keywords', name: 'geo-keywords', component: PlaceholderView, meta: { title: '关键词监控', eyebrow: 'GEO GROWTH', icon: '⌗', description: '持续监控品牌关键词和重点问题的表现变化。' } },
  { path: '/geo/visibility', name: 'geo-visibility', component: PlaceholderView, meta: { title: 'AI 可见度报告', eyebrow: 'GEO GROWTH', icon: '◉', description: '分析品牌在主流 AI 回答中的出现频率和引用质量。' } },
  { path: '/merchant-alliance', name: 'merchant-alliance', component: PlaceholderView, meta: { title: '商家联盟', eyebrow: 'PLANS & BENEFITS', icon: '◇', description: '查看商家合作权益、联合活动和联盟资源。' } },
  { path: '/notifications', name: 'notifications', component: PlaceholderView, meta: { title: '消息', eyebrow: 'SYSTEM', icon: '◌', description: '集中查看系统通知、审核提醒和任务动态。' } },
  { path: '/tasks', name: 'tasks', component: TaskCenterView, meta: { title: '任务中心', eyebrow: 'SYSTEM', icon: '✓', description: '跟踪生成、审核、发布等异步任务的进度。' } },
  { path: '/settings', name: 'settings', component: PlaceholderView, meta: { title: '系统设置', eyebrow: 'SYSTEM', icon: '⌘', description: '管理成员、角色、通知和平台连接配置。' } },
  { path: '/help', name: 'help', component: PlaceholderView, meta: { title: '帮助与反馈', eyebrow: 'SYSTEM', icon: '?', description: '查找使用说明、常见问题并提交产品反馈。' } },
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
