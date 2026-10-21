<script setup>
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ChevronRight } from 'lucide-vue-next'
import PosterStudioView from './PosterStudioView.vue'
import ProductSetStudioView from './ProductSetStudioView.vue'

const route = useRoute()
const isPoster = computed(() => route.name === 'image-create-poster')
const isProductSet = computed(() => route.name === 'image-create-product-set')
const isLanding = computed(() => !isPoster.value && !isProductSet.value)
// 每个入口三张案例图：前两张在后，第三张居中在前。
const flows = [
  { to: '/image/create/poster', name: '营销海报', intro: '说一句活动需求，自动安排文案与画面。', images: ['/images/marketing-poster-case-2.png', '/images/marketing-poster-case-3.png', '/images/marketing-poster-case-1.png'] },
  { to: '/image/create/product-set', name: '产品套图', intro: '一件商品，生成主图、细节与场景。', images: ['/images/product-set-case-2.png', '/images/product-set-case-3.png', '/images/product-set-case-1.png'] },
]

</script>

<template>
  <div class="image-create-page">
    <template v-if="isLanding">
      <header class="image-home-head">
        <h1>图片创作</h1>
        <p>从创意方向，到可交付的每一张图。</p>
      </header>

      <section class="image-home-grid">
        <RouterLink v-for="flow in flows" :key="flow.to" :to="flow.to" class="image-home-tile">
          <div class="image-home-stage" aria-hidden="true"><img v-for="(image, index) in flow.images" :key="image" :src="image" alt="" :class="`image-home-shot image-home-shot-${index + 1}`" /></div>
          <div class="image-home-copy">
            <div><h2>{{ flow.name }}</h2><p>{{ flow.intro }}</p></div>
            <span class="image-home-link">开始创作<ChevronRight :size="16" :stroke-width="2" aria-hidden="true" /></span>
          </div>
        </RouterLink>
      </section>
    </template>

    <PosterStudioView v-if="isPoster" />

    <ProductSetStudioView v-else-if="isProductSet" />
  </div>
</template>
