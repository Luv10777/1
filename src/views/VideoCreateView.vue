<script setup>
import { RouterLink } from 'vue-router'
import { ChevronRight, Play } from 'lucide-vue-next'

// 与图片创作入口页共用全幅封面卡片。
const flows = [
  { to: '/video/workbench', name: '视频生成', intro: '文本、图片或素材，剪成营销短视频。', cta: '进入创作台', cover: '/images/video-gen-full.jpg' },
  { to: '/digital-human/studio', name: '数字人视频生成', intro: '输入文案或语音，生成数字人播报视频。', cta: '进入录影棚', cover: '/images/digital-human-full.jpg' },
]
</script>

<template>
  <div class="video-create-page">
    <header class="image-home-head">
      <h1>视频创作</h1>
      <p>把文案、商品与素材，做成能发布的短视频。</p>
    </header>

    <section class="image-home-grid" aria-label="视频创作入口">
      <RouterLink v-for="flow in flows" :key="flow.to" :to="flow.to" class="image-home-tile">
        <div class="image-home-stage" aria-hidden="true">
          <img :src="flow.cover" class="wuyao-cover-img" alt="" decoding="async" />
          <div class="wuyao-vignette"></div>
          <span class="video-home-play"><Play :size="20" fill="currentColor" :stroke-width="0" /></span>
        </div>
        <div class="image-home-copy">
          <div><h2>{{ flow.name }}</h2><p>{{ flow.intro }}</p></div>
          <span class="image-home-link">{{ flow.cta }}<ChevronRight :size="16" :stroke-width="2" aria-hidden="true" /></span>
        </div>
      </RouterLink>
    </section>
  </div>
</template>
