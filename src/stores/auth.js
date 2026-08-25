import { reactive, computed } from 'vue'
import { authService } from '../services/auth'
import { isDemoMode } from '../utils/config'

const STORAGE_KEY = 'wuyao-ai-auth'
const TOKEN_KEY = 'wuyao-ai-token'
const saved = localStorage.getItem(STORAGE_KEY)
const savedToken = localStorage.getItem(TOKEN_KEY)

const state = reactive({
  user: saved ? JSON.parse(saved) : null,
  token: savedToken ? JSON.parse(savedToken) : null,
  mode: isDemoMode() ? 'demo' : 'production',
  cooldown: 0,
  loading: false,
})

let timer

export const auth = {
  state,
  isAuthenticated: computed(() => Boolean(state.user && state.token)),
  get user() {
    return state.user
  },
  get token() {
    return state.token
  },

  async sendCode(phone) {
    if (!/^1\d{10}$/.test(phone)) {
      throw new Error('请输入有效的 11 位手机号')
    }
    if (state.cooldown > 0) return

    try {
      const response = await authService.sendCode(phone)

      // 启动倒计时
      state.cooldown = 60
      clearInterval(timer)
      timer = setInterval(() => {
        state.cooldown -= 1
        if (state.cooldown <= 0) clearInterval(timer)
      }, 1000)

      return response
    } catch (error) {
      throw new Error(error.message || '验证码发送失败')
    }
  },

  async login(phone, code) {
    if (!/^1\d{10}$/.test(phone)) {
      throw new Error('请输入有效的 11 位手机号')
    }
    if (!/^\d{6}$/.test(code)) {
      throw new Error('请输入 6 位验证码')
    }

    state.loading = true
    try {
      const response = await authService.login(phone, code)

      if (response.success && response.data) {
        // 保存Token
        const tokenData = {
          accessToken: response.data.accessToken,
          refreshToken: response.data.refreshToken,
          expiresIn: response.data.expiresIn,
          expiresAt: Date.now() + response.data.expiresIn * 1000
        }
        state.token = tokenData
        localStorage.setItem(TOKEN_KEY, JSON.stringify(tokenData))

        // 保存用户信息
        const user = {
          id: response.data.user.id,
          phone: response.data.user.phone,
          name: response.data.user.name || `用户${phone.slice(-4)}`,
          avatarUrl: response.data.user.avatarUrl,
          roles: ['user'],
        }
        state.user = user
        localStorage.setItem(STORAGE_KEY, JSON.stringify(user))
      }
    } catch (error) {
      throw new Error(error.message || '登录失败')
    } finally {
      state.loading = false
    }
  },

  async logout() {
    try {
      await authService.logout()
    } catch (error) {
      console.warn('登出请求失败:', error)
    }

    state.user = null
    state.token = null
    localStorage.removeItem(STORAGE_KEY)
    localStorage.removeItem(TOKEN_KEY)
  },

  hasRole(role) {
    return Boolean(state.user?.roles?.includes(role))
  },

  getAuthHeader() {
    if (state.token?.accessToken) {
      return `Bearer ${state.token.accessToken}`
    }
    return null
  }
}
