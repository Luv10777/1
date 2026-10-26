<script setup>
import { computed, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const TABS = [{ key: 'products', label: '联盟好物' }, { key: 'merchants', label: '入驻商户' }]
const route = useRoute()
const router = useRouter()
const current = computed(() => (route.query.tab === 'merchants' ? 'merchants' : 'products'))

// 栏目记在地址里；切换时保留搜索词（只对联盟好物生效），页码回到第一页。
// 两个栏目各自渲染一份栏目条，切换后焦点要放到新面板里的按钮上。
async function select(key) {
  if (key === current.value) return
  const keyword = String(route.query.keyword || '')
  await router.push({ name: 'merchant-alliance', query: { ...(key === 'merchants' ? { tab: 'merchants' } : {}), ...(keyword ? { keyword } : {}) } })
  await nextTick()
  globalThis.document?.getElementById(`alliance-tab-${key}`)?.focus()
}
function move(step) {
  return select(TABS[(TABS.findIndex(tab => tab.key === current.value) + step + TABS.length) % TABS.length].key)
}
</script>

<template>
  <div class="alliance-tabs" role="tablist" aria-label="商城好物栏目">
    <button
      v-for="tab in TABS" :id="`alliance-tab-${tab.key}`" :key="tab.key" type="button" role="tab"
      :aria-selected="current === tab.key" :aria-controls="`alliance-panel-${tab.key}`" :tabindex="current === tab.key ? 0 : -1"
      :class="{ 'is-active': current === tab.key }"
      @click="select(tab.key)" @keydown.right.prevent="move(1)" @keydown.left.prevent="move(-1)"
    >
      {{ tab.label }}
    </button>
  </div>
</template>
