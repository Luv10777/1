import { watch } from 'vue'
import { auth } from './auth.js'
import { storesApi } from '../services/storesApi.js'
import { createStoreContext } from './storeContext.js'

let storage
try { storage = globalThis.localStorage } catch { /* Browsing without local storage is supported. */ }
const context = createStoreContext(storesApi, storage)
export const { stores, selectedStoreId, selectedStoreRecord, selectedStore, storeLoading, storeError, loadStores, saveStore, createStore } = context

watch(() => auth.isAuthenticated ? `${auth.tenantId}:${auth.user.id}` : '', identity => {
  context.setAccount(identity)
}, { immediate: true, flush: 'sync' })
