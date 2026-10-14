<script setup>
import { computed, ref, watch } from 'vue'
import { ArrowRight, FileText, MapPin, Search, Store } from 'lucide-vue-next'
import { businessCommunityService } from '../services/businessCommunity'
import '../public-commerce.css'

const categories = [
  { value: '', label: '全部信息' }, { value: 'activity', label: '门店活动' },
  { value: 'cooperation', label: '商业合作' }, { value: 'supply', label: '供求信息' }, { value: 'notice', label: '商家公告' },
]
const categoryNames = Object.fromEntries(categories.filter(item => item.value).map(item => [item.value, item.label]))
const activeCategory = ref('')
const query = ref('')
const keyword = ref('')
const page = ref(0)
const feed = ref({ items: [], totalPages: 0, totalElements: 0 })
const loading = ref(false)
const error = ref('')
const pages = computed(() => Math.max(1, feed.value.totalPages || 1))

async function loadFeed() {
  loading.value = true
  error.value = ''
  try {
    feed.value = await businessCommunityService.publicFeed({ page: page.value, size: 12, category: activeCategory.value || undefined, keyword: keyword.value || undefined })
  } catch (failure) { error.value = failure.message || '公开信息暂时无法读取。' }
  finally { loading.value = false }
}
function search() { page.value = 0; keyword.value = query.value.trim(); loadFeed() }
watch(activeCategory, () => { page.value = 0; loadFeed() }, { immediate: true })
</script>

<template>
  <main class="public-commerce public-information-page">
    <header class="public-commerce-topbar"><RouterLink to="/information" class="public-brand"><span class="public-brand-seal"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><span><strong>一方志</strong><small>本地商家信息广场</small></span></RouterLink><nav aria-label="公开服务"><RouterLink to="/merchant-alliance">商家入驻</RouterLink><RouterLink to="/information-publishing">发布信息</RouterLink></nav></header>
    <div class="public-information-content">
      <header class="public-information-heading"><div><p>YIFANGZHI / LOCAL BUSINESS</p><h1>一方好生意，<br />在这里遇见。</h1><span>门店活动、商业合作、供求需求与商家公告。</span></div><div class="public-information-actions"><RouterLink class="public-button" to="/merchant-alliance"><Store :size="16" />商家入驻</RouterLink><RouterLink class="public-button public-button-primary" to="/information-publishing"><FileText :size="16" />发布商家信息</RouterLink></div></header>
      <section class="public-feed-toolbar" aria-label="信息筛选"><div class="public-feed-categories"><button v-for="category in categories" :key="category.value || 'all'" type="button" :class="{ active: activeCategory === category.value }" :aria-pressed="activeCategory === category.value" @click="activeCategory = category.value">{{ category.label }}</button></div><form class="public-feed-search" role="search" @submit.prevent="search"><Search :size="16" /><input v-model="query" type="search" maxlength="80" placeholder="搜索标题、商家或摘要" aria-label="搜索公开商家信息" /><button type="submit" aria-label="搜索" title="搜索"><Search :size="15" aria-hidden="true" /></button></form></section>
      <p v-if="loading" class="public-state" role="status">正在读取商家信息…</p>
      <section v-else-if="error" class="public-empty" role="alert"><p>{{ error }}</p><button type="button" class="public-button" @click="loadFeed">重新加载</button></section>
      <section v-else-if="feed.items?.length" class="public-information-feed" aria-label="公开商家信息">
        <RouterLink v-for="item in feed.items" :key="item.id" :to="`/information/${item.id}`" class="public-information-item">
          <span class="public-information-category">{{ categoryNames[item.category] || '商家信息' }}</span>
          <div class="public-information-item-main"><h2>{{ item.title }}</h2><p>{{ item.summary || item.content }}</p><div class="public-information-meta"><strong><Store :size="14" />{{ item.merchantName }}</strong><span><MapPin :size="13" />{{ item.region }}</span><time>{{ new Date(item.publishedAt || item.createdAt).toLocaleDateString('zh-CN') }}</time></div></div>
          <ArrowRight :size="17" class="public-information-arrow" />
        </RouterLink>
        <nav v-if="pages > 1" class="public-pagination" aria-label="信息分页"><button type="button" :disabled="page <= 0" @click="page--; loadFeed()">上一页</button><span>{{ page + 1 }} / {{ pages }}</span><button type="button" :disabled="page + 1 >= pages" @click="page++; loadFeed()">下一页</button></nav>
      </section>
      <section v-else class="public-empty"><FileText :size="32" /><h2>还没有公开的信息</h2><p>商家发布活动、合作或供求信息后，会在这里显示。</p><RouterLink to="/information-publishing" class="public-text-link">发布第一条商家信息<ArrowRight :size="14" /></RouterLink></section>
    </div>
  </main>
</template>
