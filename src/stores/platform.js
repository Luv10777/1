import { reactive, watch } from 'vue'
import { auth } from './auth'
import { platformManagementApi } from '../services/platformManagement'

export const platform = reactive({ operator: false, loaded: false, error: '' })
let generation = 0
let pending = null

watch(() => [auth.user?.id, auth.tenantId], () => {
  generation++
  pending = null
  Object.assign(platform, { operator: false, loaded: false, error: '' })
}, { flush: 'sync' })

export async function loadPlatformAccess() {
  if (!auth.isAuthenticated) return false
  if (platform.loaded) return platform.operator
  if (pending) return pending
  const current = generation
  pending = platformManagementApi.access().then(result => {
    if (current !== generation) return false
    platform.operator = result?.operator === true
    platform.loaded = true
    platform.error = ''
    return platform.operator
  }).catch(error => {
    if (current === generation) { platform.operator = false; platform.error = error.message || '平台权限暂时无法确认' }
    return false
  }).finally(() => { if (current === generation) pending = null })
  return pending
}
