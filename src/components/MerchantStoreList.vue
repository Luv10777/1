<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Package, RefreshCw, Store } from 'lucide-vue-next'
import { commerceApi } from '../services/commerceApi'
import MerchantAllianceTabs from './MerchantAllianceTabs.vue'

const PAGE_SIZE = 10
const route = useRoute()
const router = useRouter()
const feed = ref({ items: [], total: 0, totalPages: 0 })
const covers = reactive({})
const loading = ref(false)
const error = ref('')
let generation = 0

const page = computed(() => Math.max(0, Number.parseInt(route.query.page, 10) || 0))
const pages = computed(() => Math.max(1, feed.value.totalPages || 1))
const formatMoney = value => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const storefront = storeId => ({ name: 'public-storefront', params: { storeId } })

async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await commerceApi.allianceStores({ page: page.value, size: PAGE_SIZE })
    if (current !== generation) return
    feed.value = result
    result.items.flatMap(store => store.products).forEach(async item => {
      if (!item.coverAssetId || covers[item.coverAssetId] !== undefined) return
      covers[item.coverAssetId] = ''
      covers[item.coverAssetId] = (await commerceApi.publicImageUrl(item.coverAssetId).catch(() => null))?.downloadUrl || ''
    })
  } catch (failure) {
    if (current === generation) error.value = failure.message || '入驻商户暂时无法读取。'
  } finally {
    if (current === generation) loading.value = false
  }
}

function go(next) {
  const keyword = String(route.query.keyword || '')
  return router.push({ name: 'merchant-alliance', query: { tab: 'stores', ...(keyword ? { keyword } : {}), ...(next ? { page: next } : {}) } })
}

watch(page, load, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <section id="alliance-panel-stores" class="alliance-wall" role="tabpanel" aria-labelledby="alliance-tab-stores">
    <div class="alliance-wall-toolbar"><MerchantAllianceTabs /></div>

    <p v-if="error" class="business-message-error" role="alert">{{ error }} <button type="button" class="alliance-retry" @click="load"><RefreshCw :size="13" aria-hidden="true" />重新加载</button></p>
    <p v-else-if="loading && !feed.items.length" class="alliance-state" role="status">正在读取入驻商户…</p>
    <div v-else-if="!feed.items.length" class="alliance-state">
      <Store :size="30" aria-hidden="true" />
      <h3>还没有商户入驻联盟</h3>
      <p>商家通过入驻审核后，门店会出现在这里。</p>
      <RouterLink :to="{ name: 'merchant-join' }" class="business-button">加入商户联盟</RouterLink>
    </div>
    <template v-else>
      <p class="alliance-count" aria-live="polite">共 {{ feed.total }} 家入驻门店</p>
      <ul class="alliance-stores" :aria-busy="loading">
        <li v-for="store in feed.items" :key="store.storeId" class="alliance-store">
          <div class="alliance-store-head">
            <span class="alliance-store-mark" aria-hidden="true">{{ store.storeName.slice(0, 1) }}</span>
            <div>
              <h3>{{ store.storeName }}</h3>
              <p v-if="store.productTotal">在售 {{ store.productTotal }} 件<template v-if="store.productTotal > store.products.length">，展示最近 {{ store.products.length }} 件</template></p>
            </div>
            <RouterLink v-if="store.productTotal" :to="storefront(store.storeId)" class="alliance-store-link">进店看看</RouterLink>
          </div>
          <ul v-if="store.products.length" class="alliance-store-products">
            <li v-for="item in store.products" :key="item.id">
              <RouterLink :to="storefront(item.storeId)" class="alliance-mini">
                <span class="alliance-card-photo">
                  <img v-if="covers[item.coverAssetId]" :src="covers[item.coverAssetId]" :alt="item.name" loading="lazy" width="160" height="160" />
                  <Package v-else :size="22" aria-hidden="true" />
                  <b v-if="item.inventory === 0">已售罄</b>
                </span>
                <strong>{{ item.name }}</strong>
                <span class="alliance-mini-price"><i>¥</i>{{ formatMoney(item.price) }}</span>
              </RouterLink>
            </li>
          </ul>
          <p v-else class="alliance-store-empty"><Package :size="16" aria-hidden="true" />该门店暂未上架商品</p>
        </li>
      </ul>
      <nav v-if="pages > 1" class="alliance-pagination" aria-label="入驻门店分页">
        <button type="button" :disabled="page <= 0 || loading" @click="go(page - 1)">上一页</button>
        <span>{{ page + 1 }} / {{ pages }}</span>
        <button type="button" :disabled="page + 1 >= pages || loading" @click="go(page + 1)">下一页</button>
      </nav>
    </template>
  </section>
</template>
