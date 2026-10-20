import { createRouter, createWebHistory } from 'vue-router'
import { auth } from './stores/auth'
import LoginView from './views/LoginView.vue'
import DashboardView from './views/DashboardView.vue'
import AnalyticsView from './views/AnalyticsView.vue'
import PlaceholderView from './views/PlaceholderView.vue'
import ForbiddenView from './views/ForbiddenView.vue'
import NotFoundView from './views/NotFoundView.vue'
import CreativeWorkspaceView from './views/CreativeWorkspaceView.vue'
import ConsumerPreviewView from './views/ConsumerPreviewView.vue'
import StoresView from './views/StoresView.vue'
import BillingView from './views/BillingView.vue'
import EcosystemView from './views/EcosystemView.vue'
import TaskCenterView from './views/TaskCenterView.vue'
import BrandsView from './views/BrandsView.vue'
const ProductsView = () => import('./views/ProductsView.vue')
import KnowledgeView from './views/KnowledgeView.vue'
import WorksView from './views/WorksView.vue'
import ImageCreateView from './views/ImageCreateView.vue'
import VideoCreateView from './views/VideoCreateView.vue'
import VideoWorkbenchView from './views/VideoWorkbenchView.vue'
import VideoAnalyzeView from './views/VideoAnalyzeView.vue'
import DigitalHumanStudioView from './views/DigitalHumanStudioView.vue'
import LiveStudioView from './views/LiveStudioView.vue'
import PlayerView from './views/PlayerView.vue'
const AppDownloadView = () => import('./views/AppDownloadView.vue')
import PublishingView from './views/PublishingView.vue'
import MessagesView from './views/MessagesView.vue'
import ServiceRulesView from './views/ServiceRulesView.vue'
import TeamView from './views/TeamView.vue'
const SettingsView = () => import('./views/SettingsView.vue')
import { inDesktop } from './utils/desktop'
const ReviewsView = () => import('./views/ReviewsView.vue')
const PlatformAccountsView = () => import('./views/PlatformAccountsView.vue')
const PublishingPlanView = () => import('./views/PublishingPlanView.vue')
const MerchantAllianceView = () => import('./views/MerchantAllianceView.vue')
const InformationPublishingView = () => import('./views/InformationPublishingView.vue')
const PublicStorefrontView = () => import('./views/PublicStorefrontView.vue')
const PublicOrderView = () => import('./views/PublicOrderView.vue')
const MerchantJoinView = () => import('./views/MerchantJoinView.vue')
const AllianceVouchersView = () => import('./views/AllianceVouchersView.vue')
const AllianceOffersView = () => import('./views/AllianceOffersView.vue')
const AllianceRedeemView = () => import('./views/AllianceRedeemView.vue')
const MerchantOrdersView = () => import('./views/MerchantOrdersView.vue')
const PublicInformationView = () => import('./views/PublicInformationView.vue')
const PublicInformationDetailView = () => import('./views/PublicInformationDetailView.vue')

const routes = [
  { path: '/', redirect: '/image/create' },
  { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
  { path: '/policies', redirect: '/policies/terms' },
  { path: '/policies/:policy(terms|privacy|merchant|aftersales|identity|publishing)', name: 'legal-policy', component: () => import('./views/LegalPoliciesView.vue'), meta: { public: true, title: '协议中心' } },
  { path: '/consumer', name: 'consumer', component: ConsumerPreviewView, meta: { public: true, title: '消费者预览' } },
  { path: '/store/:storeId(\\d+)', name: 'public-storefront', component: PublicStorefrontView, meta: { public: true, title: '店铺商品' } },
  { path: '/order/:token', name: 'public-order', component: PublicOrderView, meta: { public: true, title: '订单查询' } },
  { path: '/information', name: 'public-information', component: PublicInformationView, meta: { public: true, shellWhenSignedIn: true, title: '本地商家信息', navGroup: 'mall' } },
  { path: '/information/:id(\\d+)', name: 'public-information-detail', component: PublicInformationDetailView, meta: { public: true, shellWhenSignedIn: true, title: '信息详情', navGroup: 'mall' } },
  { path: '/player', name: 'player', component: PlayerView, meta: { public: true, title: '播报页' } },
  { path: '/app', name: 'app-download', component: AppDownloadView, meta: { public: true, title: '一方志 App' } },
  { path: '/billing', name: 'billing', component: BillingView, meta: { title: '套餐与权益' } },
  { path: '/ecosystem', name: 'ecosystem', component: EcosystemView, meta: { title: '生态与治理' }, },
  { path: '/dashboard', name: 'dashboard', component: DashboardView, meta: { title: '经营总览' } },
  { path: '/creative', name: 'creative', component: CreativeWorkspaceView, meta: { title: '内容创作' } },
  { path: '/chat', name: 'chat', component: PlaceholderView, meta: { title: '模型对话', description: '让模型理解你的品牌语气，快速产出下一步行动建议。' } },
  { path: '/copy/extract', name: 'copy-extract', redirect: '/image/create' },
  { path: '/copy/rewrite', name: 'copy-rewrite', redirect: '/image/create' },
  { path: '/video/analyze', name: 'video-analyze', component: VideoAnalyzeView, meta: { guest: true, title: '视频反推', icon: '◌', description: '反向拆解视频节奏、镜头意图与可复制的内容骨架。' } },
  { path: '/image/create', name: 'image-create', component: ImageCreateView, meta: { guest: true, title: '图片创作', icon: '▧', description: '为门店活动生成可编辑、可追踪的视觉素材。', navGroup: 'content' } },
  { path: '/image/create/poster', name: 'image-create-poster', component: ImageCreateView, meta: { guest: true, title: '营销海报', description: '为一次活动定下主视觉。', navGroup: 'content' } },
  { path: '/image/create/product-set', name: 'image-create-product-set', component: ImageCreateView, meta: { guest: true, title: '产品套图', icon: '▦', description: '围绕同一件商品生成一组有秩序的画面。', navGroup: 'content' } },
  { path: '/video/create', name: 'video-create', component: VideoCreateView, meta: { guest: true, title: '视频创作', icon: '▶', description: '把脚本、商品信息和品牌资产组合成短视频草稿。' } },
  { path: '/video/workbench', name: 'video-workbench', component: VideoWorkbenchView, meta: { guest: true, title: '视频工作台', icon: 'movie_edit', description: '从一句话开始，把脚本、商品素材和品牌规则编排成可发布的短视频。' } },
  { path: '/batch', name: 'batch', component: PlaceholderView, meta: { title: '批量内容生产', icon: '▦', description: '一次配置，多平台批量生成内容任务。' } },
  { path: '/digital-human', name: 'digital-human', component: LiveStudioView, meta: { guest: true, title: '实景直播', icon: '◎', description: '手机拍真实场景开播，系统负责话术、声音克隆与弹幕自动回复。' } },
  { path: '/digital-human/history', name: 'digital-human-history', component: PlaceholderView, meta: { title: '直播弹幕与回复记录', icon: '◷', description: '查看本场直播中的观众弹幕与回复内容。历史记录页将在后续版本接入完整数据。' } },
  { path: '/digital-human/studio', name: 'digital-human-studio', component: DigitalHumanStudioView, meta: { guest: true, title: '数字人摄影棚', icon: 'record_voice_over', description: '选定专属出镜人，输入文案或语音，生成真人级数字人播报视频。' } },
  { path: '/merchants', name: 'merchants', component: StoresView, meta: { title: '门店库', icon: '⌂', description: '查看和管理本商户的门店。' } },
  { path: '/brands', name: 'brands', component: BrandsView, meta: { title: '品牌库', icon: '◈', description: '沉淀品牌定位、语言风格和视觉识别资产。' } },
  { path: '/assets', name: 'assets', redirect: '/works' },
  { path: '/assets/products', name: 'products', component: ProductsView, meta: { title: '商品库', navGroup: 'assets', description: '沉淀门店爆款、团购券与实物资产，为直播讲解与内容创作提供商品事实库。' } },
  { path: '/orders', name: 'merchant-orders', component: MerchantOrdersView, meta: { title: '订单管理', navGroup: 'mall' } },
  { path: '/knowledge', name: 'knowledge', component: KnowledgeView, meta: { title: '知识库', icon: '≡', description: '把门店经验整理为模型可以调用的知识单元。' } },
  { path: '/works', name: 'works', component: WorksView, meta: { title: '数字方志库', icon: '⌁', description: '查看当前账号实际生成的图片与视频作品。' } },
  { path: '/reviews', name: 'reviews', component: ReviewsView, meta: { title: '评论 / 差评自动回复', icon: '◠', description: '聚合门店评论与差评，给出可审核的智能回复建议。' } },
  { path: '/publishing', name: 'publishing', component: PublishingView, meta: { title: '内容发布', icon: '↑', description: '管理多平台发布队列与内容审核状态。' } },
  { path: '/publishing/matrix', name: 'matrix-publishing', component: PlaceholderView, meta: { title: '矩阵发布', icon: '▦', description: '统一编排多个平台和账号的内容发布任务。' } },
  { path: '/publishing/plan', name: 'publishing-plan', component: PublishingPlanView, meta: { title: '发布计划', icon: '◷', accountCenter: true, description: '根据商圈流量波段，自动匹配内容并按时分发。' } },
  { path: '/publishing/platforms', name: 'publishing-platforms', component: PlatformAccountsView, meta: { title: '账号矩阵', icon: '⌁', accountCenter: true, description: '管理用于内容发布的平台账号和授权状态。' } },
  { path: '/analytics', name: 'analytics', component: AnalyticsView, meta: { title: '到店转化数据', icon: '⌗', description: '汇总各平台的内容、门店与活动增长表现。' } },
  { path: '/analytics/diagnosis', name: 'diagnosis', component: PlaceholderView, meta: { title: '诊断报告', description: '自动识别运营问题、增长机会和建议动作。' } },
  { path: '/analytics/platforms', name: 'analytics-platforms', component: PlaceholderView, meta: { title: '数据接口管理', icon: '⌗', description: '管理运营分析的数据接口、指标读取授权与数据同步状态。' } },
  { path: '/acquisition', name: 'acquisition', component: PlaceholderView, meta: { title: '获客中心', icon: '◎', description: '集中承接获客渠道、潜在客户和转化动作；具体功能将在业务范围明确后逐步开放。' } },
  { path: '/service/messages', name: 'messages', component: MessagesView, meta: { title: '私信会话', icon: '◠', description: '聚合各平台私信，集中查看并处理客户会话。' } },
  { path: '/service/rules', name: 'service-rules', component: ServiceRulesView, meta: { title: '客服规则配置', icon: '⌘', description: '配置智能回复范围、审核条件和客服升级规则。' } },
  { path: '/geo/brand', name: 'geo-brand', component: PlaceholderView, meta: { title: '品牌信息设置', icon: '◎', description: '维护用于生成式搜索理解和引用的品牌基础信息。' } },
  { path: '/geo/services', name: 'geo-services', component: PlaceholderView, meta: { title: '增值服务', icon: '◇', description: '查看品牌在生成式搜索场景中的增长服务。' } },
  { path: '/geo/keywords', name: 'geo-keywords', component: PlaceholderView, meta: { title: '关键词监控', icon: '⌗', description: '持续监控品牌关键词和重点问题的表现变化。' } },
  { path: '/geo/visibility', name: 'geo-visibility', component: PlaceholderView, meta: { title: '可见度报告', icon: '◉', description: '分析品牌在主流生成式搜索回答中的出现频率和引用质量。' } },
  { path: '/merchant-alliance', name: 'merchant-alliance', component: MerchantAllianceView, meta: { guest: true, title: '商户联盟', navGroup: 'mall' } },
  { path: '/merchant-alliance/join', name: 'merchant-join', component: MerchantJoinView, meta: { guest: true, title: '商家入驻', navGroup: 'mall' } },
  { path: '/alliance/vouchers', name: 'alliance-vouchers', component: AllianceVouchersView, meta: { title: '我的联盟券', navGroup: 'mall' } },
  { path: '/alliance/offers', name: 'alliance-offers', component: AllianceOffersView, meta: { title: '供券管理', navGroup: 'mall' } },
  { path: '/alliance/redeem', name: 'alliance-redeem', component: AllianceRedeemView, meta: { title: '门店核销', navGroup: 'mall' } },
  { path: '/information-publishing', name: 'information-publishing', component: InformationPublishingView, meta: { title: '信息发布' } },
  // 实名已并入系统设置；旧链接跳过去并定位到实名那一块。
  { path: '/identity', alias: '/real-name', name: 'identity', redirect: { name: 'settings', hash: '#identity' } },
  { path: '/notifications', name: 'notifications', redirect: '/image/create' },
  { path: '/tasks', name: 'tasks', component: TaskCenterView, meta: { title: '任务中心', icon: '✓', description: '按时间查看创作任务、生成结果与实际烟火消耗。' } },
  { path: '/team', name: 'team', component: TeamView, meta: { title: '员工管理', icon: '⚇', description: '添加店员、分配门店，停用或移除账号。', ownerOnly: true } },
  { path: '/settings', name: 'settings', component: SettingsView, meta: { title: '系统设置', icon: '⌘', description: '查看和修改自己的账号资料、登录密码和实名认证。' } },
  { path: '/help', name: 'help', redirect: '/image/create' },
  { path: '/403', name: 'forbidden', component: ForbiddenView, meta: { title: '没有访问权限', public: true } },
  { path: '/404', name: 'not-found', component: NotFoundView, meta: { title: '页面不存在', public: true } },
  { path: '/:pathMatch(.*)*', redirect: '/404' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

export function handleAuthExpired() {
  auth.clearSession()
  const current = router.currentRoute.value
  if (!current.matched.length || current.meta.public || (current.meta.guest && !inDesktop)) return
  return router.replace({ name: 'login', query: { redirect: current.fullPath } })
}

router.beforeEach(async (to, from) => {
  if (to.meta.public) {
    const wechatCallback = [to, from].some(location => location.query.wechat_ticket || location.hash.startsWith('#wechat_ticket=') || location.query.wechat_error)
    if (to.name === 'login' && !wechatCallback) {
      const savedHomeLogin = !inDesktop && !from.matched.length && to.query.redirect === '/dashboard' && !to.query.mode
      if (auth.isAuthenticated || savedHomeLogin) return { name: inDesktop ? 'digital-human' : 'image-create' }
    }
    return true
  }

  if (!auth.isAuthenticated) {
    if (to.meta.guest && !inDesktop) return true
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // 桌面端只做直播这一件事：别的页面一律回到直播页。
  if (inDesktop && !['digital-human', 'billing'].includes(to.name)) return { name: 'digital-human' }

  if (to.meta.role && !auth.hasRole(to.meta.role)) return { name: 'forbidden' }
  if (to.meta.ownerOnly && !auth.isOwner) return { name: 'forbidden' }
  return true
})

export default router
