import { inDesktop } from './desktop.js'

// 主域名不展示 AI 创作，这些功能放在 wy.yifangzhi.com。
// 这里只决定页面和入口显不显示，接口照常可用，不能当作权限校验。
const MAIN_HOSTS = new Set(['yifangzhi.com', 'www.yifangzhi.com'])
const HIDDEN_ROUTES = new Set([
  'creative', 'chat', 'batch',
  'copy', 'copy-create',
  'image-create', 'image-create-poster', 'image-create-product-set',
  'video-create', 'video-workbench', 'video-analyze',
  'digital-human', 'digital-human-history', 'digital-human-studio',
  'works', 'tasks',
  'knowledge', 'referral',
])

// 旧版桌面端和安卓端连的还是主域名，要靠它开直播，不隐藏。
export function hidesAiCreation({ hostname, userAgent = '', desktop = false }) {
  return MAIN_HOSTS.has(hostname) && !desktop && !/YifangzhiAndroid\//.test(userAgent)
}

export const aiCreationHidden = hidesAiCreation({ hostname: globalThis.location?.hostname, userAgent: globalThis.navigator?.userAgent, desktop: inDesktop })
export const isHiddenRoute = name => aiCreationHidden && HIDDEN_ROUTES.has(name)
