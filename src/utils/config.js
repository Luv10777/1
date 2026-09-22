/**
 * 环境配置
 *
 * 注意区分两件事：
 *   authMode   —— 登录走不走真后端。已接通，默认 real。
 *   demoMode   —— 品牌/素材/知识/作品等模块的后端还没写，仍返回演示数据。
 * 两者互相独立，别再用一个开关同时控制。
 */

export const config = {
  appName: import.meta.env.VITE_APP_NAME || '一方志 AI 商家增长平台',
  appEnv: import.meta.env.VITE_APP_ENV || 'development',
  // 留空表示同源。开发环境由 vite 代理转发到后端，不需要填。
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL || '',
  authMode: import.meta.env.VITE_AUTH_MODE || 'real',
  isDevelopment: import.meta.env.DEV,
  isProduction: import.meta.env.PROD,
}

export function getConfig(key) {
  return config[key]
}

/** 这些模块后端尚未实现，页面应显式标注"演示数据"。 */
export function isDemoMode() {
  return import.meta.env.VITE_DEMO_MODE !== 'false'
}

/** 登录链路是否走真后端。 */
export function isAuthReal() {
  return config.authMode === 'real'
}
