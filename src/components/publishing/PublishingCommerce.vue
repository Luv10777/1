<script setup>
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { Check, RefreshCw, LoaderCircle, Store, CircleAlert } from 'lucide-vue-next'
import { loadAccountCommerce } from '../../services/publishingCommerce'
import PublishingSelect from './PublishingSelect.vue'

const props = defineProps({ accounts: { type: Array, default: () => [] }, store: { type: String, required: true } })
const emit = defineEmits(['change'])
const selectedAccountId = ref('')
const selections = ref({})
const result = ref({ state: 'no_account', packages: [], source: 'demo' })
const loading = ref(false)
let generation = 0
const douyinAccounts = computed(() => props.accounts.filter(a => a.platform === '抖音'))
const account = computed(() => douyinAccounts.value.find(a => a.id === selectedAccountId.value))
const accountOptions = computed(() => douyinAccounts.value.map(item => ({ value: item.id, label: item.name, description: selections.value[item.id] ? '已选择团购套餐' : item.handle })))
const hint = computed(() => ({
  no_account: ['请先选择抖音发布账号', '团购套餐来自账号关联的商家，仅挂载到对应抖音账号。'],
  not_connected: ['尚未连接商家套餐', '当前账号未接入抖音商家授权及套餐接口，暂时无法读取套餐。'],
  not_enabled: ['该账号尚未开通团购', '请先在抖音来客开通本地生活团购服务并上架套餐，再刷新。'],
  empty: ['当前门店暂无可用套餐', '该账号在所选门店没有可挂载套餐，请检查门店或在抖音来客上架套餐后刷新。'],
  expired: ['抖音商家授权已失效', '请在关联平台管理中重新授权该账号，再读取套餐。'],
  error: ['套餐读取失败', '暂时无法读取该账号的商家套餐，请稍后重试。'],
})[result.value.state] || [])
function update(next) { selections.value = next; emit('change', { ...next }) }
function clearAccount(id) { const next = { ...selections.value }; delete next[id]; update(next) }
watch(() => douyinAccounts.value.map(a => a.id).join('|'), () => {
  const allowed = new Set(douyinAccounts.value.map(a => a.id))
  update(Object.fromEntries(Object.entries(selections.value).filter(([id]) => allowed.has(id))))
  if (!allowed.has(selectedAccountId.value)) selectedAccountId.value = douyinAccounts.value[0]?.id || ''
}, { immediate: true })
watch(() => props.store, () => update({}))
async function refresh() {
  const request = ++generation
  const owner = account.value
  if (!owner) { result.value = { state: 'no_account', packages: [], source: 'demo' }; loading.value = false; return }
  loading.value = true
  try {
    const next = await loadAccountCommerce(owner, props.store)
    if (request === generation) {
      result.value = next
      if (!next.packages.some(item => item.id === selections.value[owner.id]?.id)) clearAccount(owner.id)
    }
  } catch {
    if (request === generation) {
      result.value = { state: 'error', packages: [], source: 'demo' }
      clearAccount(owner.id)
    }
  } finally { if (request === generation) loading.value = false }
}
watch([account, () => props.store], refresh, { immediate: true })
function choose(bundle) {
  if (loading.value || !account.value || bundle.accountId !== account.value.id || bundle.store !== props.store) return
  if (selections.value[account.value.id]?.id === bundle.id) clearAccount(account.value.id)
  else update({ ...selections.value, [account.value.id]: { id: bundle.id, name: bundle.name, store: props.store } })
}
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="pub-commerce-picker">
    <div class="pub-commerce-heading"><label class="field-label" for="commerce-account">抖音商家团购套餐 <small>可选</small></label><button class="pub-icon-button" :disabled="loading || !account" aria-label="刷新商家套餐" title="刷新套餐" @click="refresh"><RefreshCw :size="14" /></button></div>
    <PublishingSelect v-if="douyinAccounts.length" id="commerce-account" v-model="selectedAccountId" label="团购套餐所属抖音账号" :options="accountOptions"><template #icon><Store :size="16" /></template></PublishingSelect>
    <p v-if="account" class="pub-commerce-source">{{ account.name }} · {{ store }}<span>示例账号数据，尚未同步真实抖音套餐</span></p>
    <div v-if="loading" class="pub-commerce-message" role="status"><LoaderCircle class="pub-loading" :size="19" /><div><b>正在读取商家套餐</b></div></div>
    <div v-else-if="result.state !== 'ready'" class="pub-commerce-message" role="status"><CircleAlert :size="20" /><div><b>{{ hint[0] }}</b><p>{{ hint[1] }}</p><button v-if="result.state === 'error'" class="draft-btn" @click="refresh">重新读取</button><RouterLink v-if="result.state === 'expired' || result.state === 'not_connected'" to="/publishing/platforms">前往关联平台管理</RouterLink></div></div>
    <template v-else>
      <p class="pub-commerce-owner"><Store :size="14" />{{ result.merchantName }} · {{ result.packages.length }} 个可选套餐</p>
      <div class="bundle-list"><button v-for="bundle in result.packages" :key="bundle.id" class="bundle-card" :class="{ selected: selections[selectedAccountId]?.id === bundle.id }" :aria-pressed="selections[selectedAccountId]?.id === bundle.id" @click="choose(bundle)"><span class="bundle-check"><Check v-if="selections[selectedAccountId]?.id === bundle.id" :size="11" /></span><span><b>{{ bundle.name }}</b><small><del>{{ bundle.original }}</del> <strong>{{ bundle.price }}</strong> · 佣金 {{ bundle.commission }}</small></span></button></div>
    </template>
    <p v-if="Object.keys(selections).length" class="pub-commerce-selected">{{ Object.keys(selections).length }} 个抖音账号已选择套餐 <button @click="update({})">清除挂载</button></p>
  </div>
</template>
