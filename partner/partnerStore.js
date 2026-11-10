import { reactive, watch } from 'vue'
import { auth } from '../src/stores/auth.js'
import { partnerApi } from '../src/services/partnerApi.js'

/** 当前登录账号所属的服务商；换账号时清空重读。 */
export const partner = reactive({ overview: null, loaded: false, error: '' })
let pending = null

watch(() => auth.user?.id, () => { pending = null; Object.assign(partner, { overview: null, loaded: false, error: '' }) }, { flush: 'sync' })

export async function loadPartner(force = false) {
  if (!auth.isAuthenticated) return false
  if (partner.loaded && !force) return Boolean(partner.overview)
  if (pending) return pending
  pending = partnerApi.me().then(result => {
    Object.assign(partner, { overview: result, loaded: true, error: '' })
    return true
  }).catch(error => {
    // 403 是「不是服务商账号」或「服务商已停用」，原样告诉用户；其他错误按连接问题处理。
    Object.assign(partner, { overview: null, loaded: error.status === 403, error: error.message || '服务商信息暂时无法读取' })
    return false
  }).finally(() => { pending = null })
  return pending
}
