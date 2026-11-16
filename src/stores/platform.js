import { reactive, watch } from 'vue'
import { auth } from './auth'
import { platformManagementApi } from '../services/platformManagement'

export const platform = reactive({ operator: false, permissions: [], roles: [], loaded: false, error: '' })
let generation = 0
let pending = null

watch(() => [auth.user?.id, auth.tenantId], () => {
  generation++
  pending = null
  Object.assign(platform, { operator: false, permissions: [], roles: [], loaded: false, error: '' })
}, { flush: 'sync' })

export async function loadPlatformAccess() {
  if (!auth.isAuthenticated) return false
  if (platform.loaded) return platform.operator
  if (pending) return pending
  const current = generation
  pending = platformManagementApi.access().then(result => {
    if (current !== generation) return false
    platform.operator = result?.operator === true
    platform.permissions = Array.isArray(result?.permissions) ? result.permissions : []
    platform.roles = Array.isArray(result?.roles) ? result.roles : []
    platform.loaded = true
    platform.error = ''
    return platform.operator
  }).catch(error => {
    if (current === generation) { platform.operator = false; platform.error = error.message || '平台权限暂时无法确认' }
    return false
  }).finally(() => { if (current === generation) pending = null })
  return pending
}

export const hasPlatformPermission = (...keys) => keys.some(key => platform.permissions.includes(key))
