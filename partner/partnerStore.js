import { reactive, watch } from 'vue'
import { auth } from '../src/stores/auth.js'
import { partnerApi } from '../src/services/partnerApi.js'

/** 当前登录账号所属的服务商和他的权限；换账号时清空重读。team 是团队成员（名字用于生成人、操作人筛选）。 */
export const partner = reactive({ overview: null, loaded: false, error: '', team: [] })
let pending = null

watch(() => auth.user?.id, () => { pending = null; Object.assign(partner, { overview: null, loaded: false, error: '', team: [] }) }, { flush: 'sync' })

/** 负责人全部权限；成员按勾选的权限（tenants、codes、distribute、finance、team）。只决定显示哪些入口，真正的校验在后端。 */
export const isOwner = () => partner.overview?.role === 'OWNER'
export const can = permission => Boolean(partner.overview?.permissions?.[permission])
/** 成员在团队里的称呼，没填时用账号名。 */
export const myName = () => partner.overview?.displayName || partner.overview?.userName || auth.user?.name || ''

export async function loadPartner(force = false) {
  if (!auth.isAuthenticated) return false
  if (partner.loaded && !force) return Boolean(partner.overview)
  if (pending) return pending
  pending = partnerApi.me().then(result => {
    Object.assign(partner, { overview: result, loaded: true, error: '' })
    return true
  }).catch(error => {
    // 403 是「不是服务商成员」或「服务商已停用」，原样告诉用户；其他错误按连接问题处理。
    Object.assign(partner, { overview: null, loaded: error.status === 403, error: error.message || '服务商信息暂时无法读取' })
    return false
  }).finally(() => { pending = null })
  return pending
}

export async function loadTeam() {
  partner.team = await partnerApi.team()
  return partner.team
}

/** userId → 团队里的称呼（没填的用账号名）。 */
export const teamNames = () => Object.fromEntries(partner.team.map(m => [m.userId, m.displayName || m.name || String(m.uid || '')]))
