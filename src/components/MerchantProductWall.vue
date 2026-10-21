<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Package, RefreshCw, Search, Store, X } from 'lucide-vue-next'
import { commerceApi } from '../services/commerceApi'

const PAGE_SIZE = 12
const route = useRoute()
const router = useRouter()
const feed = ref({ items: [], total: 0, totalPages: 0 })
const covers = reactive({})
const loading = ref(false)
const error = ref('')
let generation = 0

const keyword = computed(() => String(route.query.keyword || '').trim().slice(0, 80))
const page = computed(() => Math.max(0, Number.parseInt(route.query.page, 10) || 0))
const pages = computed(() => Math.max(1, feed.value.totalPages || 1))
const query = ref(keyword.value)
const formatMoney = value => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await commerceApi.catalog({ keyword: keyword.value, page: page.value, size: PAGE_SIZE })
    if (current !== generation) return
    feed.value = result
    // 图片地址逐张补上，先把文字和价格亮出来。
    result.items.forEach(async item => {
      if (!item.coverAssetId || covers[item.coverAssetId] !== undefined) return
      covers[item.coverAssetId] = ''
      covers[item.coverAssetId] = (await commerceApi.publicImageUrl(item.coverAssetId).catch(() => null))?.downloadUrl || ''
    })
  } catch (failure) {
    if (current === generation) error.value = failure.message || '商品暂时无法读取。'
  } finally {
    if (current === generation) loading.value = false
  }
}

function go(values) {
  const next = { keyword: keyword.value, page: page.value, ...values }
  return router.push({ name: 'merchant-alliance', query: { ...(next.keyword ? { keyword: next.keyword } : {}), ...(next.page ? { page: next.page } : {}) }, hash: '#products' })
}
function search() {
  const value = query.value.trim().slice(0, 80)
  if (value === keyword.value && page.value === 0) return load()
  return go({ keyword: value, page: 0 })
}
function clear() { query.value = ''; return go({ keyword: '', page: 0 }) }

watch(() => [keyword.value, page.value], () => { query.value = keyword.value; load() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <section id="products" class="alliance-wall" aria-labelledby="alliance-wall-title">
    <div class="alliance-wall-toolbar">
      <h2 id="alliance-wall-title">联盟好物</h2>
      <form class="alliance-search" role="search" @submit.prevent="search">
        <Search :size="15" aria-hidden="true" />
        <input v-model="query" type="search" maxlength="80" placeholder="搜索商品名称或分类" aria-label="搜索商品" />
        <button v-if="keyword" type="button" aria-label="清除搜索" @click="clear"><X :size="14" aria-hidden="true" /></button>
      </form>
    </div>

    <p v-if="error" class="business-message-error" role="alert">{{ error }} <button type="button" class="alliance-retry" @click="load"><RefreshCw :size="13" aria-hidden="true" />重新加载</button></p>
    <p v-else-if="loading && !feed.items.length" class="alliance-state" role="status">正在读取商品…</p>
    <div v-else-if="!feed.items.length" class="alliance-state">
      <Package :size="30" aria-hidden="true" />
      <h3>{{ keyword ? '没有找到相关商品' : '还没有商家上架实物商品' }}</h3>
      <p>{{ keyword ? `换个关键词试试，或清除搜索查看全部商品。` : '商家上架后，商品会出现在这里。' }}</p>
      <button v-if="keyword" type="button" class="business-button" @click="clear">清除搜索</button>
    </div>
    <template v-else>
      <p class="alliance-count" aria-live="polite">共 {{ feed.total }} 件商品<template v-if="keyword"> · 搜索「{{ keyword }}」</template></p>
      <ul class="alliance-grid" :aria-busy="loading">
        <li v-for="item in feed.items" :key="item.id">
          <RouterLink :to="{ name: 'public-storefront', params: { storeId: item.storeId } }" class="alliance-card">
            <span class="alliance-card-photo">
              <img v-if="covers[item.coverAssetId]" :src="covers[item.coverAssetId]" :alt="item.name" loading="lazy" width="320" height="320" />
              <Package v-else :size="30" aria-hidden="true" />
              <b v-if="item.inventory === 0">已售罄</b>
            </span>
            <span class="alliance-card-body">
              <small>{{ item.category }}</small>
              <strong>{{ item.name }}</strong>
              <span class="alliance-card-price"><i>¥</i>{{ formatMoney(item.price) }}<small>/ {{ item.saleUnit }}</small></span>
              <span class="alliance-card-store"><Store :size="13" aria-hidden="true" />{{ item.storeName }}</span>
            </span>
          </RouterLink>
        </li>
      </ul>
      <nav v-if="pages > 1" class="alliance-pagination" aria-label="商品分页">
        <button type="button" :disabled="page <= 0 || loading" @click="go({ page: page - 1 })">上一页</button>
        <span>{{ page + 1 }} / {{ pages }}</span>
        <button type="button" :disabled="page + 1 >= pages || loading" @click="go({ page: page + 1 })">下一页</button>
      </nav>
    </template>
  </section>
</template>
