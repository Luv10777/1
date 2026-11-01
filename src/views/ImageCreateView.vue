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
// 封面跟随入口数据，避免循环卡片共用同一张图片。
const flows = [
  { to: '/image/create/poster', name: '灵感绘图', intro: '把想象画出来，摄影、插画与创意图都能做。', cover: '/images/marketing-poster-full.jpg' },
  { to: '/image/create/product-set', name: '产品套图', intro: '一件商品，生成主图、细节与场景。', cover: '/images/product-set-full.jpg' },
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
          <div class="image-home-stage" aria-hidden="true">
            <img :src="flow.cover" class="wuyao-cover-img" alt="" decoding="async" />
            <div class="wuyao-vignette"></div>
          </div>
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
