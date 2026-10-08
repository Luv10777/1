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

// 后端的角色只有 OWNER（管理员）和 STAFF（店员）。这里只决定显示哪些入口，真正的限制在服务端。
const rolesOf = role => [String(role || 'OWNER').toLowerCase()]
const roleLabel = role => (role === 'STAFF' ? '店员' : '管理员')

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
    name: data.user.name || (data.user.phone ? `用户${data.user.phone.slice(-4)}` : '用户'),
    roles: rolesOf(data.user.role),
    role: roleLabel(data.user.role),
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
  /**
   * 只有明确是店员才收起管理入口。角色还没从服务端对齐时（旧会话、后端暂时连不上）按管理员显示：
   * 多显示一个按钮顶多被服务端拒绝，把管理员的入口藏起来才是真的挡住了人。
   */
  get isOwner() {
    return !state.user?.roles?.includes('staff')
  },

  async sendCode(phone) {
    if (!/^1[3-9]\d{9}$/.test(phone)) {
      throw new Error('请输入有效的 11 位手机号')
    }
    if (state.cooldown > 0) return

    const result = await authService.sendCode(phone)

    state.cooldown = result.retryAfterSeconds
    clearInterval(timer)
    timer = setInterval(() => {
      state.cooldown -= 1
      if (state.cooldown <= 0) clearInterval(timer)
    }, 1000)
    return result
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
      state.user = { ...state.user, id: me.userId, tenantId: me.tenantId, phone: me.phone, name: me.name || state.user?.name, roles: rolesOf(me.role), role: roleLabel(me.role) }
      writeUser(state.user)
      // 校验过程中 request.js 可能已经换过 token，内存里的那份要跟上。
      state.token = readTokens()
      return true
    } catch (error) {
      // 只有服务端明确判定未登录才清会话；网络错误、后端重启时保留登录态。
      if (error?.code === 1401) this.clearSession()
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
