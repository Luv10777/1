import { computed, ref, watch } from 'vue'
import { auth } from './auth'
import { accountPlatforms, authorizationStatus, canPublishAccount, createMockAccounts } from '../domain/platformAccounts'

const accountList = ref([])
const storageError = ref('')
const storageKey = computed(() => `wuyao-platform-accounts-demo-v1-${auth.tenantId || 'local'}-${auth.user?.id || 'local'}`)
watch(storageKey, key => {
  storageError.value = ''
  try {
    const saved = JSON.parse(localStorage.getItem(key) || 'null')
    const supported = Array.isArray(saved) ? saved.filter(a => accountPlatforms.some(p => p.name === a?.platform)) : []
    const valid = Array.isArray(saved) && supported.every(a => typeof a.id === 'string' && typeof a.name === 'string' && typeof a.uid === 'string' && Array.isArray(a.permissions) && a.permissions.every(p => typeof p === 'string') && Number.isFinite(Date.parse(a.expiresAt)))
    accountList.value = valid ? supported : createMockAccounts()
  } catch {
    accountList.value = createMockAccounts()
    storageError.value = '浏览器存储不可用，当前修改仅在本次会话保留。'
  }
}, { immediate: true })
watch(accountList, list => {
  try { localStorage.setItem(storageKey.value, JSON.stringify(list)); storageError.value = '' }
  catch { storageError.value = '浏览器存储不可用，当前修改仅在本次会话保留。' }
}, { deep: true, flush: 'sync', immediate: true })

const now = ref(Date.now())
const publishingAccounts = computed(() => accountList.value.filter(a => canPublishAccount(a, now.value)).map(a => ({
  ...a, status: authorizationStatus(a, now.value) === 'expiring' ? '即将到期 · 演示' : '授权有效 · 演示',
})))
function completeAuthorization({ accountId, platform, type, name, permissions }) {
  const existing = accountList.value.find(a => a.id === accountId)
  const expiry = new Date(Date.now() + 90 * 86400000).toISOString()
  if (existing) {
    Object.assign(existing, { permissions: [...permissions], expiresAt: expiry, revoked: false })
    return existing
  }
  const id = crypto.randomUUID()
  const account = { id, platform, type, name: name.trim(), uid: `demo_${id.slice(0, 8)}`, handle: `demo_${id.slice(0, 8)}`, fans: '—', avatar: name.trim().slice(0, 1), avatarClass: 'bg-blue-50 text-blue-700', image: '', permissions: [...permissions], expiresAt: expiry, revoked: false }
  accountList.value.push(account)
  return account
}
export function usePlatformAccounts() {
  now.value = Date.now()
  return { accountList, publishingAccounts, storageError, completeAuthorization, refreshTime: () => { now.value = Date.now() } }
}
