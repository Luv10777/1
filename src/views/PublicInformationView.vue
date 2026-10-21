<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, FilePenLine, FileText, MapPin, Search, Store, X } from 'lucide-vue-next'
import { businessCommunityService } from '../services/businessCommunity'
import { informationCategories, informationCategoryNames, informationDate, informationFilters, informationQuery } from '../utils/informationPresentation'
import '../public-commerce.css'
import '../public-information.css'

const route = useRoute()
const router = useRouter()
const filters = computed(() => informationFilters(route.query))
const query = ref(filters.value.keyword)
const feed = ref({ items: [], totalPages: 0, total: 0 })
const loading = ref(false)
const error = ref('')
const pages = computed(() => Math.max(1, feed.value.totalPages || 1))
const filtered = computed(() => Boolean(filters.value.category || filters.value.keyword))
let requestVersion = 0

async function loadFeed() {
  const attempt = ++requestVersion
  loading.value = true
  error.value = ''
  try {
    const result = await businessCommunityService.publicFeed({ ...filters.value, size: 12 })
    if (attempt === requestVersion) feed.value = result
  } catch (failure) {
    if (attempt === requestVersion) error.value = failure.message || '公开信息暂时无法读取。'
  } finally {
    if (attempt === requestVersion) loading.value = false
  }
}

function changeFilters(values) {
  return router.push({ name: 'public-information', query: informationQuery({ ...filters.value, page: 0, ...values }) })
}

function search() {
  const keyword = query.value.trim().slice(0, 80)
  if (keyword === filters.value.keyword && filters.value.page === 0) return loadFeed()
  return changeFilters({ keyword })
}

watch(() => [filters.value.category, filters.value.keyword, filters.value.page], () => {
  query.value = filters.value.keyword
  loadFeed()
}, { immediate: true })
onBeforeUnmount(() => { requestVersion++ })
</script>

<template>
  <main class="public-commerce public-information-page">
    <header class="public-commerce-topbar">
      <RouterLink to="/image/create" class="public-brand"><span class="public-brand-seal"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><span><strong>一方志</strong><small>为每一方商家立传</small></span></RouterLink>
      <nav aria-label="公开服务"><RouterLink to="/merchant-alliance">商家入驻</RouterLink><RouterLink to="/information-publishing">发布与管理</RouterLink></nav>
    </header>
    <div class="public-information-content">
      <header class="information-masthead">
        <div><p class="information-eyebrow">一方志 · 商家信息服务</p><h1>商家信息广场</h1><p class="information-intro">新消息，好生意。<br class="information-mobile-break" />门店活动、商品供求与商家公告，在这里看见。</p></div>
        <RouterLink class="public-button public-button-primary" to="/information-publishing"><FilePenLine :size="16" aria-hidden="true" />发布一条信息</RouterLink>
      </header>

      <section class="public-feed-toolbar" aria-label="信息筛选">
        <div class="public-feed-categories"><button v-for="category in informationCategories" :key="category.value || 'all'" type="button" :class="{ active: filters.category === category.value }" :aria-pressed="filters.category === category.value" @click="changeFilters({ category: category.value })">{{ category.label }}</button></div>
        <form class="public-feed-search" role="search" @submit.prevent="search"><Search :size="16" aria-hidden="true" /><input v-model="query" type="search" maxlength="80" placeholder="搜索标题、商家或摘要" aria-label="搜索公开商家信息" /><button type="submit" aria-label="搜索" title="搜索"><ArrowRight :size="16" aria-hidden="true" /></button></form>
      </section>

      <div class="information-columns">
        <section class="information-stream" aria-labelledby="information-stream-title" :aria-busy="loading">
          <header class="information-stream-heading"><h2 id="information-stream-title">{{ filtered ? '筛选结果' : '最新发布' }}</h2><span v-if="!loading && !error" role="status">共 {{ feed.total || 0 }} 条</span><button v-if="filtered" type="button" class="information-clear" @click="changeFilters({ category: '', keyword: '' })"><X :size="13" aria-hidden="true" />清除筛选</button></header>
          <p v-if="loading" class="public-state" role="status">正在读取商家信息…</p>
          <section v-else-if="error" class="public-empty" role="alert"><p>{{ error }}</p><button type="button" class="public-button" @click="loadFeed">重新加载</button></section>
          <div v-else-if="feed.items?.length" class="public-information-feed">
            <article v-for="item in feed.items" :key="item.id" class="information-feed-entry">
              <RouterLink :to="{ name: 'public-information-detail', params: { id: item.id }, query: informationQuery(filters) }" class="public-information-item">
                <span class="public-information-category">{{ informationCategoryNames[item.category] || '商家信息' }}</span>
                <div class="public-information-item-main"><h3>{{ item.title }}</h3><p>{{ item.summary || item.content }}</p><div class="public-information-meta"><strong><Store :size="14" aria-hidden="true" />{{ item.merchantName }}</strong><span><MapPin :size="13" aria-hidden="true" />{{ item.region }}</span><time :datetime="item.publishedAt || item.createdAt">{{ informationDate(item.publishedAt || item.createdAt) }}</time></div></div>
              </RouterLink>
            </article>
          </div>
          <section v-else class="public-empty"><FileText :size="30" aria-hidden="true" /><h3>{{ filtered || filters.page ? '没有找到相关信息' : '还没有公开的信息' }}</h3><p>{{ filtered ? '试试其他关键词，或查看全部商家信息。' : filters.page ? '这一页暂无内容，可以返回第一页继续浏览。' : '商家发布的信息会按时间显示在这里。' }}</p><button v-if="filtered || filters.page" type="button" class="public-button" @click="changeFilters({ category: '', keyword: '' })">查看全部信息</button><RouterLink v-else to="/information-publishing" class="public-text-link">发布一条信息</RouterLink></section>
          <nav v-if="!loading && !error && pages > 1" class="public-pagination" aria-label="信息分页"><button type="button" :disabled="filters.page <= 0" @click="changeFilters({ page: filters.page - 1 })">上一页</button><span>{{ filters.page + 1 }} / {{ pages }}</span><button type="button" :disabled="filters.page + 1 >= pages" @click="changeFilters({ page: filters.page + 1 })">下一页</button></nav>
        </section>

        <aside class="information-sidebar" aria-label="商家服务">
          <section class="information-service-note"><span class="information-note-index">商家来信</span><h2>让你的消息<br />被更多人看见。</h2><p>写清具体内容、所在地区和参与方式。发布后生成独立页面，可分享、编辑与下架。</p><RouterLink to="/information-publishing" class="public-text-link">发布与管理信息</RouterLink></section>
          <RouterLink to="/merchant-alliance" class="information-service-link"><Store :size="22" aria-hidden="true" /><div><strong>好物与商家</strong><span>查看实物商品，申请商家入驻。</span></div></RouterLink>
          <p class="information-reading-note">点击标题阅读全文。信息详情展示发布商家、发布时间及有效期，内容由发布商家维护。</p>
        </aside>
      </div>
    </div>
  </main>
</template>
