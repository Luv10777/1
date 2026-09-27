<script setup>
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import PhotoStack from '../components/PhotoStack.vue'
import PosterStudioView from './PosterStudioView.vue'
import ProductSetStudioView from './ProductSetStudioView.vue'

const route = useRoute()
const isPoster = computed(() => route.name === 'image-create-poster')
const isProductSet = computed(() => route.name === 'image-create-product-set')
const isLanding = computed(() => !isPoster.value && !isProductSet.value)

</script>

<template>
  <div class="image-create-page">
    <div v-if="isLanding" class="page-heading image-heading image-heading-minimal">
      <div>
        <h1>AI 图片创作<span class="heading-mark">✦</span></h1>
        <p class="page-intro">从创意方向，到可交付的每一张图。</p>
      </div>
      <div class="image-heading-meta"><span class="status-pulse" /> <span>2 个创作入口</span></div>
    </div>

    <section v-if="isLanding" class="workflow-grid">
      <RouterLink to="/image/create/poster" class="workflow-card workflow-card-cinnabar">
        <div class="workflow-card-copy"><h3>营销海报</h3><p>说一句活动需求，自动安排文案与画面，做好门店宣传。</p><div class="workflow-tags"><span>活动主视觉</span><span>门店促销</span><span>社媒传播</span></div></div>
        <PhotoStack :images="['/images/marketing-poster-case-1.png', '/images/marketing-poster-case-2.png', '/images/marketing-poster-case-3.png']" alt="节日营销海报案例" />
      </RouterLink>

      <RouterLink to="/image/create/product-set" class="workflow-card workflow-card-cyan">
        <div class="workflow-card-copy"><h3>产品套图</h3><p>基于商品图，批量生成统一风格的电商主图、详情页配图与使用场景图。</p><div class="workflow-tags"><span>主图与细节</span><span>场景氛围</span><span>多尺寸输出</span></div></div>
        <PhotoStack class="product-stack" :images="['/images/marketing-poster-case-3.png', '/images/publishing/restaurant.jpg', '/images/marketing-poster-case-2.png']" alt="产品套图视觉参考" label="产品套图视觉参考" />
      </RouterLink>
    </section>

    <PosterStudioView v-if="isPoster" />

    <ProductSetStudioView v-else-if="isProductSet" />
  </div>
</template>
