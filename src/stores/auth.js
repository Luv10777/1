import { reactive } from 'vue'
import { authService } from '../services/auth'
import { readTokens, readUser, writeTokens, writeUser, clearAll } from '../utils/tokenStore'

const state = reactive({
  user: readUser(),
  token: readTokens(),
  mode: 'production',
  cooldown: 0,
  loading: false,
})

let timer

function persistSession(data) {
  const tokens = {
    accessToken: data.accessToken,
    refreshToken: data.refreshToken,
    expiresIn: data.expiresIn,
    expiresAt: Date.now() + data.expiresIn * 1000,
  }
  state.token = tokens
  writeTokens(tokens)

  const user = {
    id: data.user.userId,
    tenantId: data.user.tenantId,
    phone: data.user.phone,
    name: data.user.name || `用户${data.user.phone.slice(-4)}`,
    roles: ['user'],
  }
  state.user = user
  writeUser(user)
  return user
}

export const auth = {
  state,
  /**
   * 用 getter 而不是 computed()。
   * computed() 返回的是 ref 对象，在 router.beforeEach 这类普通 JS 里
   * 忘了写 .value 就永远是 truthy —— 路由守卫会形同虚设。
   * getter 在模板和 JS 里都直接拿到布尔值，且读的是 reactive state，响应式不受影响。
   */
  get isAuthenticated() {
    return Boolean(state.user && state.token?.accessToken)
  },
  get user() {
    return state.user
  },
  get token() {
    return state.token
  },
  get tenantId() {
    return state.user?.tenantId ?? null
  },

  async sendCode(phone) {
    if (!/^1[3-9]\d{9}$/.test(phone)) {
      throw new Error('请输入有效的 11 位手机号')
    }
    if (state.cooldown > 0) return

    await authService.sendCode(phone)

    state.cooldown = 60
    clearInterval(timer)
    timer = setInterval(() => {
      state.cooldown -= 1
      if (state.cooldown <= 0) clearInterval(timer)
    }, 1000)
  },

  async login(phone, code) {
    if (!/^1[3-9]\d{9}$/.test(phone)) {
      throw new Error('请输入有效的 11 位手机号')
    }
    if (!/^\d{6}$/.test(code)) {
      throw new Error('请输入 6 位验证码')
    }

    state.loading = true
    try {
      const data = await authService.login(phone, code)
      return persistSession(data)
    } finally {
      state.loading = false
    }
  },

  async loginWithPassword(account, password) {
    state.loading = true
    try {
      const data = await authService.loginWithPassword(account, password)
      return persistSession(data)
    } finally {
      state.loading = false
    }
  },

  /** 刷新页面后校验会话是否还有效，顺便把用户信息对齐服务端。 */
  async restore() {
    if (!state.token?.accessToken) return false
    try {
      const me = await authService.getCurrentUser()
      state.user = { ...state.user, id: me.userId, tenantId: me.tenantId, phone: me.phone }
      writeUser(state.user)
      return true
    } catch {
      this.clearSession()
      return false
    }
  },

  async logout() {
    try {
      await authService.logout()
    } catch (error) {
      console.warn('登出请求失败，本地会话仍会清除:', error.message)
    }
    this.clearSession()
  },

  clearSession() {
    state.user = null
    state.token = null
    clearAll()
  },

  hasRole(role) {
    return Boolean(state.user?.roles?.includes(role))
  },

  getAuthHeader() {
    return state.token?.accessToken ? `Bearer ${state.token.accessToken}` : null
  },
}
