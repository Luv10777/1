<script setup>
import { computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'

const route = useRoute()
const meta = computed(() => route.meta)
const queue = [
  { label: '连接模型服务', status: '已就绪' },
  { label: '配置品牌上下文', status: '待配置' },
  { label: '创建第一个任务', status: '待开始' },
]
</script>

<template>
  <div class="placeholder-page">
    <div class="page-heading"><div><p class="eyebrow">{{ meta.eyebrow }}</p><h1><span class="placeholder-icon">{{ meta.icon }}</span>{{ meta.title }}</h1><p class="page-intro">{{ meta.description }}</p></div><span class="coming-badge">PHASE 1 / PREVIEW</span></div>
    <section class="placeholder-hero panel-dark"><div class="placeholder-hero-icon">{{ meta.icon }}</div><div><p class="eyebrow accent">WORKSPACE BOUNDARY</p><h2>这个工作区正在搭建中。</h2><p>第一阶段先把页面边界、权限模型和任务入口固定下来。真实模型调用、供应商连接与生产数据将在后续阶段接入。</p><div class="placeholder-actions"><RouterLink to="/dashboard" class="primary-button compact">回到运营总览 <span>→</span></RouterLink><RouterLink to="/tasks" class="text-link">查看任务中心</RouterLink></div></div></section>
    <section class="placeholder-grid"><article class="panel"><div class="panel-heading"><div><p class="eyebrow">MODULE CHECKLIST</p><h3>模块边界</h3></div><span class="mono muted-text">03 ITEMS</span></div><div class="check-list"><div v-for="item in queue" :key="item.label" class="check-row"><span class="check-symbol" :class="{ done: item.status === '已就绪' }">{{ item.status === '已就绪' ? '✓' : '·' }}</span><span>{{ item.label }}</span><span class="check-status">{{ item.status }}</span></div></div></article><article class="panel boundary-panel"><p class="eyebrow">WHY THIS PAGE EXISTS</p><h3>先建立可复用的操作边界</h3><p>所有入口都会保留稳定 URL、统一状态反馈和移动端布局。等真实接口准备好后，可以直接替换数据层，不改动用户已经熟悉的路径。</p><div class="boundary-foot"><span class="status-pulse" /> Mock API ready <span class="mono">/ v0.1</span></div></article></section>
  </div>
</template>
