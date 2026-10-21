<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { CalendarDays, MapPin, Phone, Store } from 'lucide-vue-next'
import { businessCommunityService } from '../services/businessCommunity'
import InformationContent from '../components/InformationContent.vue'
import { informationCategoryNames, informationDate, informationFilters, informationQuery } from '../utils/informationPresentation'
import '../public-commerce.css'
import '../public-information.css'

const route = useRoute()
const item = ref(null)
const loading = ref(false)
const error = ref('')
const back = computed(() => ({ name: 'public-information', query: informationQuery(informationFilters(route.query)) }))
let requestVersion = 0

async function loadDetail() {
  const attempt = ++requestVersion
  loading.value = true
  error.value = ''
  item.value = null
  try {
    const result = await businessCommunityService.publicInformation(route.params.id)
    if (!result?.id) throw new Error('信息不存在或已过期。')
    if (attempt === requestVersion) item.value = result
  } catch (failure) {
    if (attempt === requestVersion) error.value = failure.message || '信息不存在或已过期。'
  } finally {
    if (attempt === requestVersion) loading.value = false
  }
}
watch(() => route.params.id, loadDetail, { immediate: true })
onBeforeUnmount(() => { requestVersion++ })
</script>

<template>
  <main class="public-commerce public-information-detail-page">
    <header class="public-commerce-topbar"><RouterLink to="/image/create" class="public-brand"><span class="public-brand-seal"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><span><strong>一方志</strong><small>商家信息服务</small></span></RouterLink><nav aria-label="公开服务"><RouterLink :to="back">信息广场</RouterLink><RouterLink to="/information-publishing">发布与管理</RouterLink></nav></header>
    <div class="public-information-detail-content">
      <RouterLink :to="back" class="information-back">返回信息广场</RouterLink>
      <p v-if="loading" class="public-state" role="status">正在读取信息…</p>
      <section v-else-if="error" class="public-empty" role="alert"><h1>信息暂不可用</h1><p>{{ error }}</p><button type="button" class="public-button" @click="loadDetail">重新加载</button><RouterLink :to="back" class="public-text-link">看看其他商家信息</RouterLink></section>
      <article v-else-if="item" class="public-information-detail">
        <div class="public-information-detail-kicker"><span class="public-information-category">{{ informationCategoryNames[item.category] || '商家信息' }}</span><span>信息编号 {{ item.id }}</span></div>
        <h1>{{ item.title }}</h1>
        <p v-if="item.summary" class="public-information-detail-summary">{{ item.summary }}</p>
        <div class="public-information-publisher"><Store :size="22" aria-hidden="true" /><div><strong>{{ item.merchantName }}</strong><span><MapPin :size="13" aria-hidden="true" />{{ item.region }}</span></div><div class="information-published-time"><span>发布时间</span><time :datetime="item.publishedAt || item.createdAt">{{ informationDate(item.publishedAt || item.createdAt, true) }}</time></div></div>
        <InformationContent :content="item.content" />
        <dl class="public-information-contact"><div><dt><CalendarDays :size="15" aria-hidden="true" />信息有效期</dt><dd>{{ item.validUntil ? `至 ${item.validUntil}` : '长期有效，以商家最新发布内容为准' }}</dd></div><div v-if="item.contactName"><dt>联系人</dt><dd>{{ item.contactName }}</dd></div><div v-if="item.contactPhone"><dt><Phone :size="15" aria-hidden="true" />联系电话</dt><dd><a :href="`tel:${item.contactPhone}`">{{ item.contactPhone }}</a></dd></div></dl>
        <footer class="information-article-footer"><p>本条信息由 {{ item.merchantName }} 发布和维护。</p><div><RouterLink :to="back" class="public-button">继续浏览信息</RouterLink><RouterLink to="/information-publishing" class="public-text-link">我也要发布</RouterLink></div></footer>
      </article>
    </div>
  </main>
</template>
