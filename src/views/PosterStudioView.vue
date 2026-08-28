<script setup>
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import FloatingPromptBar from '../components/FloatingPromptBar.vue'

const generated = ref(false)
const templates = [
  { image: '/images/marketing-poster-case-1.png', title: '七夕 · 爱意正浓', tag: '浪漫晚宴' },
  { image: '/images/marketing-poster-case-2.png', title: '年味渐浓', tag: '新春雅宴' },
  { image: '/images/marketing-poster-case-3.png', title: '端阳 · 风雅入夏', tag: '节气主题' },
  { image: '/images/marketing-poster-case-1.png', title: '把今晚，留给彼此', tag: '品牌宣发', position: '62% center' },
  { image: '/images/marketing-poster-case-2.png', title: '新春有席，静候相逢', tag: '活动预热', position: '38% center' },
  { image: '/images/marketing-poster-case-3.png', title: '纯粹，是更高级的表达', tag: '产品故事', position: '68% center' },
  { image: '/images/marketing-poster-case-1.png', title: '一席好时光', tag: '门店促销', position: '30% center' },
  { image: '/images/marketing-poster-case-2.png', title: '见面正当时', tag: '节日活动', position: '72% center' },
]

const runGenerate = () => { generated.value = true }
</script>

<template>
  <div class="poster-studio-page">
    <header class="poster-studio-header"><div class="history-menu"><button type="button" class="history-trigger" aria-haspopup="menu">◷ <span>历史会话</span><b class="history-chevron">⌄</b></button><div class="history-dropdown" role="menu"><button type="button" role="menuitem"><span>最近使用</span><small>刚刚</small></button><button type="button" role="menuitem"><span>七夕活动海报</span><small>昨天</small></button><button type="button" role="menuitem"><span>门店周末促销</span><small>8 月 26 日</small></button></div></div></header>
    <main class="template-library"><div class="template-grid"><article v-for="(template, index) in templates" :key="`${template.title}-${index}`" class="template-card group"><div class="template-image-wrap"><img :src="template.image" :alt="template.title" :style="{ objectPosition: template.position || 'center' }" class="template-image" /><div class="template-overlay"><span>{{ template.tag }}</span><button type="button" @click="runGenerate">创作同款 <b>→</b></button></div></div></article></div><div v-if="generated" class="generated-toast"><span class="status-pulse" /> 已将模板方向带入创作舱，你可以继续补充灵感</div></main>
    <FloatingPromptBar @generate="runGenerate" />
  </div>
</template>
