import { ref, computed, watch } from 'vue'

export function createStoreContext(api, storage) {
  const stores = ref([])
  const selectedStoreId = ref(null)
  const storeLoading = ref(false)
  const storeError = ref('')
  const selectedStoreRecord = computed(() => stores.value.find(store => String(store.id) === String(selectedStoreId.value)) || null)
  const selectedStore = computed(() => selectedStoreRecord.value?.name || '尚未选择门店')
  let account = ''
  let generation = 0
  let loadSequence = 0
  const cacheKey = () => `wuyao-selected-store:${account}`

  watch(selectedStoreId, id => {
    if (!account || id == null) return
    try { storage?.setItem(cacheKey(), String(id)) } catch { /* Storage may be disabled. */ }
  }, { flush: 'sync' })

  async function loadStores() {
    if (!account) return
    const currentGeneration = generation
    const sequence = ++loadSequence
    storeLoading.value = true
    storeError.value = ''
    try {
      const data = await api.list()
      if (currentGeneration !== generation || sequence !== loadSequence) return
      stores.value = data
      let preferred = selectedStoreId.value
      try { preferred ??= storage?.getItem(cacheKey()) } catch { /* Use first accessible store. */ }
      selectedStoreId.value = data.find(store => String(store.id) === String(preferred))?.id ?? data[0]?.id ?? null
    } catch (error) {
      if (currentGeneration !== generation || sequence !== loadSequence) return
      stores.value = []
      selectedStoreId.value = null
      storeError.value = error.message || '门店加载失败，请重试'
    } finally {
      if (currentGeneration === generation && sequence === loadSequence) storeLoading.value = false
    }
  }

  function setAccount(identity) {
    if (identity === account) return Promise.resolve()
    account = identity || ''
    generation += 1
    loadSequence += 1
    stores.value = []
    selectedStoreId.value = null
    storeLoading.value = false
    storeError.value = ''
    return account ? loadStores() : Promise.resolve()
  }

  async function saveStore(values, previous = null) {
    if (!account) throw new Error('请先登录')
    const currentGeneration = generation
    const saved = previous
      ? await api.update(previous.id, { ...values, version: previous.version })
      : await api.create(values)
    if (currentGeneration !== generation) return null
    loadSequence += 1
    storeLoading.value = false
    storeError.value = ''
    // 修改后留在原来的位置；新建的排在最后，和服务端按建店先后返回的顺序一致。
    const index = stores.value.findIndex(store => String(store.id) === String(saved.id))
    stores.value = index < 0 ? [...stores.value, saved] : stores.value.map((store, at) => (at === index ? saved : store))
    // 新建的门店顺手切过去；只是改了某家店的资料，不该把人从正在管理的门店带走。
    if (!previous) selectedStoreId.value = saved.id
    return saved
  }

  /** 关店。关掉的如果正是当前门店，就切到剩下的第一家。 */
  async function archiveStore(store) {
    if (!account) throw new Error('请先登录')
    const currentGeneration = generation
    await api.archive(store.id)
    if (currentGeneration !== generation) return
    loadSequence += 1
    storeLoading.value = false
    storeError.value = ''
    stores.value = stores.value.filter(item => String(item.id) !== String(store.id))
    if (String(selectedStoreId.value) === String(store.id)) selectedStoreId.value = stores.value[0]?.id ?? null
  }

  return { stores, selectedStoreId, selectedStoreRecord, selectedStore, storeLoading, storeError, loadStores, setAccount, saveStore, archiveStore, createStore: values => saveStore(values) }
}
