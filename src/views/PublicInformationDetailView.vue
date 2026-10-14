<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, CalendarDays, MapPin, Phone, Store } from 'lucide-vue-next'
import { businessCommunityService } from '../services/businessCommunity'
import '../public-commerce.css'

const route = useRoute()
const item = ref(null)
const loading = ref(false)
const error = ref('')
const categoryNames = { activity: '门店活动', cooperation: '商业合作', supply: '供求信息', notice: '商家公告' }

async function loadDetail() {
  loading.value = true
  error.value = ''
  item.value = null
  try { item.value = await businessCommunityService.publicInformation(route.params.id) }
  catch (failure) { error.value = failure.message || '信息不存在或已过期。' }
  finally { loading.value = false }
}
watch(() => route.params.id, loadDetail, { immediate: true })
</script>

<template>
  <main class="public-commerce public-information-detail-page">
    <header class="public-commerce-topbar"><RouterLink to="/information" class="public-brand"><span class="public-brand-seal"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><span><strong>一方志</strong><small>本地商家信息</small></span></RouterLink><RouterLink to="/information" class="public-text-link"><ArrowLeft :size="14" />信息广场</RouterLink></header>
    <div class="public-information-detail-content">
      <p v-if="loading" class="public-state" role="status">正在读取信息…</p>
      <section v-else-if="error" class="public-empty" role="alert"><h1>信息暂不可用</h1><p>{{ error }}</p><RouterLink to="/information" class="public-text-link">返回信息广场<ArrowLeft :size="14" /></RouterLink></section>
      <article v-else-if="item" class="public-information-detail">
        <div class="public-information-detail-kicker"><span>{{ categoryNames[item.category] || '商家信息' }}</span><time>{{ new Date(item.publishedAt || item.createdAt).toLocaleDateString('zh-CN') }}</time></div>
        <h1>{{ item.title }}</h1>
        <p v-if="item.summary" class="public-information-detail-summary">{{ item.summary }}</p>
        <div class="public-information-publisher"><Store :size="18" /><div><strong>{{ item.merchantName }}</strong><span><MapPin :size="13" />{{ item.region }}</span></div></div>
        <div class="public-information-body">{{ item.content }}</div>
        <dl class="public-information-contact"><div v-if="item.validUntil"><dt><CalendarDays :size="14" />有效期至</dt><dd>{{ item.validUntil }}</dd></div><div v-if="item.contactName"><dt>联系人</dt><dd>{{ item.contactName }}</dd></div><div v-if="item.contactPhone"><dt><Phone :size="14" />联系电话</dt><dd><a :href="`tel:${item.contactPhone}`">{{ item.contactPhone }}</a></dd></div></dl>
        <RouterLink to="/information" class="public-store-footer-link"><ArrowLeft :size="14" />返回商家信息广场</RouterLink>
      </article>
    </div>
  </main>
</template>
