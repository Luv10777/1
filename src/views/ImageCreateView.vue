<script setup>
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import PhotoStack from '../components/PhotoStack.vue'
import PosterStudioView from './PosterStudioView.vue'
import ProductSetStudioView from './ProductSetStudioView.vue'

const route = useRoute()
const brief = ref('')
const selectedRatio = ref('3:4')
const selectedCount = ref(4)

const isPoster = computed(() => route.name === 'image-create-poster')
const isProductSet = computed(() => route.name === 'image-create-product-set')
const isLanding = computed(() => !isPoster.value && !isProductSet.value)

const workflow = computed(() => isProductSet.value ? {
  eyebrow: '产品套图 / 02',
  title: '产品套图',
  description: '一次生成一组有秩序的商品视觉：主图、细节、场景与卖点图，保持产品外观与品牌规则一致。',
  accent: 'cyan',
  icon: '▦',
  ratios: ['1:1', '4:3', '3:4'],
  countLabel: '4 张 / 组',
} : {
  eyebrow: '营销海报 / 01',
  title: '营销海报',
  description: '把活动信息转成一张能被看懂、记住并行动的门店主视觉，适配小红书、朋友圈与线下物料。',
  accent: 'violet',
  icon: '✦',
  ratios: ['3:4', '4:3', '9:16'],
  countLabel: '1 张 / 次',
})

const goGenerate = () => {
  if (!brief.value.trim()) brief.value = isProductSet.value ? '为青岚茶事夏日新品生成一组清爽、留白充足的产品套图。' : '为青岚茶事周末双人下午茶生成一张温暖高级的营销海报。'
}
</script>

<template>
  <div class="image-create-page">
    <div v-if="isLanding" class="page-heading image-heading image-heading-minimal">
      <div>
        <h1>AI 图片创作<span class="heading-mark">✦</span></h1>
        <p class="page-intro">从创意方向，到可交付的每一张图。</p>
      </div>
      <div class="image-heading-meta"><span class="status-pulse" /> <span>2 个工作流 · 已就绪</span></div>
    </div>

    <section v-if="isLanding" class="workflow-grid">
      <RouterLink to="/image/create/poster" class="workflow-card workflow-card-violet">
        <div class="workflow-card-copy"><h3>营销海报</h3><p>一键生成高转化率的节日促销、活动预热与品牌宣发视觉素材。</p><div class="workflow-tags"><span>活动主视觉</span><span>门店促销</span><span>社媒传播</span></div></div>
        <PhotoStack :images="['/images/marketing-poster-case-1.png', '/images/marketing-poster-case-2.png', '/images/marketing-poster-case-3.png']" alt="节日营销海报案例" />
      </RouterLink>

      <RouterLink to="/image/create/product-set" class="workflow-card workflow-card-cyan">
        <div class="workflow-card-copy"><h3>产品套图</h3><p>基于商品图，批量生成统一风格的电商主图、详情页配图与使用场景图。</p><div class="workflow-tags"><span>主图与细节</span><span>场景氛围</span><span>多尺寸输出</span></div></div>
        <PhotoStack class="product-stack" :images="['/images/product-set-case-1.png', '/images/product-set-case-2.png', '/images/product-set-case-3.png']" alt="产品套图案例" label="产品套图案例组图" />
      </RouterLink>
    </section>

    <PosterStudioView v-if="isPoster" />

    <ProductSetStudioView v-else-if="isProductSet" />
  </div>
</template>
