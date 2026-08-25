/**
 * 认证服务
 * 根据环境自动选择Mock或真实API
 */

import { post } from '../utils/request'
import { isDemoMode } from '../utils/config'
import * as mockAuth from './mockAuth'

export const authService = {
  /**
   * 发送验证码
   */
  async sendCode(phone) {
    if (isDemoMode()) {
      return mockAuth.mockSendCode(phone)
    }
    return post('/api/auth/send-code', { phone })
  },

  /**
   * 登录
   */
  async login(phone, code) {
    if (isDemoMode()) {
      return mockAuth.mockLogin(phone, code)
    }
    return post('/api/auth/login', { phone, code })
  },

  /**
   * 刷新Token
   */
  async refreshToken(refreshToken) {
    if (isDemoMode()) {
      return mockAuth.mockRefreshToken(refreshToken)
    }
    return post('/api/auth/refresh', { refreshToken })
  },

  /**
   * 登出
   */
  async logout() {
    if (isDemoMode()) {
      return mockAuth.mockLogout()
    }
    return post('/api/auth/logout')
  },

  /**
   * 获取当前用户
   */
  async getCurrentUser(token) {
    if (isDemoMode()) {
      return mockAuth.mockGetCurrentUser(token)
    }
    // 真实API会通过Authorization header自动识别用户
    return { success: true, data: null }
  }
}
