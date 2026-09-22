/**
 * 认证服务 —— 已接通真实后端（backend/growth-api）。
 *
 * 后端 data 结构：
 *   login / refresh -> { accessToken, refreshToken, expiresIn, user: { userId, tenantId, phone, name } }
 *   me              -> { userId, tenantId, phone, name }
 */

import { get, post } from '../utils/request'

export const authService = {
  sendCode(phone) {
    return post('/api/auth/send-code', { phone })
  },

  login(phone, code) {
    return post('/api/auth/login', { phone, code })
  },

  loginWithPassword(account, password) {
    const endpoint = import.meta.env.VITE_PASSWORD_LOGIN_ENDPOINT || '/api/auth/password-login'
    if (!endpoint.startsWith('/api/')) {
      throw new Error('密码登录接口配置不正确，请联系管理员。')
    }
    return post(endpoint, { account, password })
  },

  refreshToken(refreshToken) {
    return post('/api/auth/refresh', { refreshToken })
  },

  logout() {
    return post('/api/auth/logout')
  },

  getCurrentUser() {
    return get('/api/auth/me')
  },
}
