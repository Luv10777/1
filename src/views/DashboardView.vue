<script setup>
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { auth } from '../stores/auth'

const selectedStore = ref('青岚茶事 · 杭州城西店')
const route = useRoute()
const stores = ['青岚茶事 · 杭州城西店', '青岚茶事 · 湖滨店', '山止咖啡 · 黄龙店']
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 12) return '早上好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const metrics = [
  { label: '本周内容产出', value: '38', unit: '条', delta: '+18.4%', note: '较上周', tone: 'violet' },
  { label: '待审核任务', value: '07', unit: '项', delta: '需要关注', note: '预计 2h 内完成', tone: 'amber' },
  { label: '内容互动率', value: '6.8', unit: '%', delta: '+2.1%', note: '较上周', tone: 'cyan' },
]

const pulseBars = [28, 44, 38, 62, 54, 78, 64, 72, 48, 84, 68, 92, 74, 88, 80, 96]
</script>

<template>
  <div class="dashboard-page">
    <div class="page-heading dashboard-heading">
      <div><p class="eyebrow">{{ route?.meta?.eyebrow || 'TODAY / OPERATIONS' }}</p><h1>{{ greeting }}，{{ auth.user?.name || '林知夏' }} <span class="heading-mark">✦</span></h1><p class="page-intro">这里是你的增长脉冲。今天，先从一个小动作开始。</p></div>
      <div class="heading-controls"><label class="store-select"><span class="eyebrow">当前门店</span><select v-model="selectedStore"><option v-for="store in stores" :key="store">{{ store }}</option></select><span class="select-chevron">⌄</span></label><button class="primary-button compact"><span>＋</span> 新建任务</button></div>
    </div>

    <section class="pulse-hero panel-dark">
      <div class="pulse-copy"><div class="pulse-label"><span class="status-pulse" /> 今日增长脉冲 <span class="mono">/ 08.25</span></div><h2>把每一次内容动作，<br /><span>都变成下一次到店理由。</span></h2><p>你的内容系统正在稳定运行。现在有 3 个动作值得优先处理。</p><div class="pulse-actions"><RouterLink to="/tasks" class="text-link">查看任务队列 <span>→</span></RouterLink><RouterLink to="/analytics" class="text-link muted">查看本周分析</RouterLink></div></div>
      <div class="pulse-chart-wrap"><div class="chart-topline"><span class="mono">CONTENT VELOCITY</span><span class="chart-value">+24.8%</span></div><div class="pulse-chart"><span v-for="(height, index) in pulseBars" :key="index" class="pulse-bar" :style="{ height: `${height}%`, animationDelay: `${index * 35}ms` }" /></div><div class="chart-axis"><span>08.19</span><span>08.21</span><span>08.23</span><span>08.25</span></div></div>
    </section>

    <section class="metrics-grid">
      <article v-for="metric in metrics" :key="metric.label" class="metric-card" :class="`metric-${metric.tone}`"><div class="metric-header"><span>{{ metric.label }}</span><span class="metric-glyph">{{ metric.tone === 'violet' ? '↗' : metric.tone === 'amber' ? '!' : '◔' }}</span></div><div class="metric-number">{{ metric.value }}<small>{{ metric.unit }}</small></div><div class="metric-foot"><span class="metric-delta">{{ metric.delta }}</span><span>{{ metric.note }}</span></div></article>
      <article class="metric-card model-card"><div class="metric-header"><span>模型与连接</span><span class="metric-glyph">✦</span></div><div class="model-status"><span class="status-pulse" /><strong>3 / 3 在线</strong></div><div class="model-list"><span>FluAPI <i>READY</i></span><span>ToAPIs <i>READY</i></span><span>素材服务 <i>READY</i></span></div></article>
    </section>

    <section class="workspace-grid">
      <article class="panel task-panel"><div class="panel-heading"><div><p class="eyebrow">NEXT BEST ACTION</p><h3>接下来做什么</h3></div><RouterLink to="/tasks" class="panel-link">全部任务 <span>→</span></RouterLink></div><div class="task-list"><div class="task-row"><div class="task-icon violet">Aa</div><div class="task-copy"><strong>完成 3 条夏日新品文案</strong><span>文案重写 · 品牌「青岚茶事」</span></div><span class="task-time">约 12 min</span><button class="row-arrow" aria-label="打开任务">→</button></div><div class="task-row"><div class="task-icon cyan">▧</div><div class="task-copy"><strong>审核小红书封面组图</strong><span>内容审核 · 4 张待确认</span></div><span class="task-time">约 8 min</span><button class="row-arrow" aria-label="打开任务">→</button></div><div class="task-row"><div class="task-icon amber">◌</div><div class="task-copy"><strong>查看上周门店复盘</strong><span>运营分析 · 周报已生成</span></div><span class="task-time">约 5 min</span><button class="row-arrow" aria-label="打开任务">→</button></div></div></article>
      <article class="panel activity-panel"><div class="panel-heading"><div><p class="eyebrow">RECENT WORK</p><h3>最近工作</h3></div><RouterLink to="/works" class="panel-link">作品库 <span>→</span></RouterLink></div><div class="activity-list"><div class="activity-row"><div class="activity-thumb thumb-violet">Aa</div><div class="activity-copy"><strong>夏日新品 · 三平台标题</strong><span>文案重写 <i>已完成</i></span></div><span class="activity-date mono">08:42</span></div><div class="activity-row"><div class="activity-thumb thumb-cyan">▧</div><div class="activity-copy"><strong>城西店 · 周末活动封面</strong><span>AI 图片创作 <i>审核中</i></span></div><span class="activity-date mono">昨天</span></div><div class="activity-row"><div class="activity-thumb thumb-amber">◌</div><div class="activity-copy"><strong>上周评论情绪摘要</strong><span>运营分析 <i>已完成</i></span></div><span class="activity-date mono">08.23</span></div></div></article>
    </section>

    <footer class="dashboard-footer"><span><span class="status-pulse" /> 梧曜星枢系统运行正常</span><span class="mono">LAST SYNC 08:46:12 CST</span></footer>
  </div>
</template>
