/**
 * 环境配置管理
 */

export const config = {
  appName: import.meta.env.VITE_APP_NAME || '梧曜星枢 AI 商家增长平台',
  appEnv: import.meta.env.VITE_APP_ENV || 'development',
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL || '',
  authMode: import.meta.env.VITE_AUTH_MODE || 'mock',
  isDevelopment: import.meta.env.DEV,
  isProduction: import.meta.env.PROD,
  isDemoMode: !import.meta.env.VITE_API_BASE_URL || import.meta.env.VITE_AUTH_MODE === 'mock'
}

export function getConfig(key) {
  return config[key]
}

export function isDemoMode() {
  return config.isDemoMode
}
