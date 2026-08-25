import { reactive, computed } from 'vue'

const STORAGE_KEY = 'wuyuo-ai-auth'
const saved = localStorage.getItem(STORAGE_KEY)

const state = reactive({
  user: saved ? JSON.parse(saved) : null,
  mode: import.meta.env.VITE_AUTH_MODE || 'mock',
  cooldown: 0,
})

let timer

export const auth = {
  state,
  isAuthenticated: computed(() => Boolean(state.user)),
  get user() {
    return state.user
  },
  async sendCode(phone) {
    if (!/^1\d{10}$/.test(phone)) throw new Error('请输入有效的 11 位手机号')
    if (state.cooldown > 0) return
    state.cooldown = 60
    clearInterval(timer)
    timer = setInterval(() => {
      state.cooldown -= 1
      if (state.cooldown <= 0) clearInterval(timer)
    }, 1000)
  },
  async login(phone, code) {
    if (!/^1\d{10}$/.test(phone)) throw new Error('请输入有效的 11 位手机号')
    if (!/^\d{6}$/.test(code)) throw new Error('请输入 6 位验证码')
    const user = {
      id: 'usr_demo_001',
      name: '林知夏',
      phone,
      role: '运营管理员',
      roles: ['platform_admin', 'operator'],
      tenant: '梧曜增长实验室',
      merchant: '青岚茶事 · 杭州城西店',
      initials: '林',
    }
    state.user = user
    localStorage.setItem(STORAGE_KEY, JSON.stringify(user))
  },
  logout() {
    state.user = null
    localStorage.removeItem(STORAGE_KEY)
  },
  hasRole(role) {
    return Boolean(state.user?.roles?.includes(role))
  },
}
