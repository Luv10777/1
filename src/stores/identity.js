import { reactive, watch } from 'vue'
import { auth } from './auth'
import { platformManagementApi } from '../services/platformManagement'

// 当前登录账号的实名状态。侧栏的「未实名」标记、实名提醒弹窗和设置页共用这一份。
export const myIdentity = reactive({
  application: null,
  loaded: false,
})

let pending = null
let scope = 0

watch(() => auth.user?.id, () => {
  scope += 1
  pending = null
  myIdentity.application = null
  myIdentity.loaded = false
}, { flush: 'sync' })

export function loadMyIdentity() {
  if (!auth.isAuthenticated) return Promise.resolve(null)
  if (pending) return pending
  const current = scope
  pending = platformManagementApi.myIdentity().then(data => {
    if (current === scope) { myIdentity.application = data; myIdentity.loaded = true }
    return current === scope ? data : null
  }).catch(() => null).finally(() => {
    if (current === scope) pending = null
  })
  return pending
}

export function setMyIdentity(application) {
  myIdentity.application = application
  myIdentity.loaded = true
}

// 读取失败时 loaded 仍为 false，不弹窗也不显示标记，避免把「读不到」当成「没实名」。
export const identityMissing = () => myIdentity.loaded && !['VERIFIED', 'PENDING_REVIEW'].includes(myIdentity.application?.status)
