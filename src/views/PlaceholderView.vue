<script setup>
import { computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'

const route = useRoute()
const meta = computed(() => route.meta)
</script>

<template>
  <div class="placeholder-page">
    <div class="page-heading"><div><p class="eyebrow">{{ meta.eyebrow }}</p><h1><span v-if="meta.icon" class="placeholder-icon">{{ meta.icon }}</span>{{ meta.title }}</h1><p class="page-intro">{{ meta.description }}</p></div><span class="coming-badge">尚未开放</span></div>
    <section class="placeholder-hero panel-dark"><div v-if="meta.icon" class="placeholder-hero-icon">{{ meta.icon }}</div><div><p class="eyebrow accent">NOT AVAILABLE YET</p><h2>这个功能还没有开发。</h2><p>页面只保留了入口，后端没有接通，也没有任何真实数据。上面的介绍是计划中的用途，不代表已经能用。</p><div class="placeholder-actions"><RouterLink to="/image/create" class="primary-button compact">回到内容工具 <span>→</span></RouterLink><RouterLink to="/tasks" class="text-link">查看任务中心</RouterLink></div></div></section>
  </div>
</template>
