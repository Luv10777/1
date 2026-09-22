<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { auth } from '../stores/auth'
import { selectedStore, stores } from '../stores/merchantContext'

const month = new Date().getMonth()
const seasons = [
 { name: '冬日', idea: '用一份暖心热饮，记录冬日的小店日常' },
 { name: '春日', idea: '春意正好，适合讲一段时令新品的故事' },
 { name: '夏日', idea: '暑气渐盛，适合记录清凉饮品与晚间小聚' },
 { name: '秋日', idea: '秋意渐浓，适合筹备节气滋补特惠' },
]
const season = seasons[Math.floor(((month + 1) % 12) / 3)]
const termPairs = [['小寒','大寒'],['立春','雨水'],['惊蛰','春分'],['清明','谷雨'],['立夏','小满'],['芒种','夏至'],['小暑','大暑'],['立秋','处暑'],['白露','秋分'],['寒露','霜降'],['立冬','小雪'],['大雪','冬至']]
const nextTerm = termPairs[month][new Date().getDate() < 15 ? 0 : 1]
const today = new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(new Date())
const greeting = computed(() => { const hour = new Date().getHours(); return hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好' })
const metrics = [
  { label: '全网曝光量', value: '128.6', unit: '万', delta: '+18.4%', note: '较上周', tone: 'accent' },
  { label: '多平台同步数', value: '42', unit: '条', delta: '+12', note: '本周已刊印', tone: 'blue' },
  { label: '挂载 POI 到店引导数', value: '2,486', unit: '次', delta: '+26.8%', note: '有效引导', tone: 'green' },
  { label: '团购转化估算', value: '¥18.4', unit: '万', delta: '+9.2%', note: '本月累计', tone: 'orange' },
]
const pulseBars = [32, 48, 41, 68, 57, 73, 62, 86, 69, 92, 78, 96]
</script>

<template>
  <div class="dashboard-page paper-page">
    <div class="page-heading dashboard-heading"><div><p class="eyebrow">今日 · {{ today }}</p><h1>{{ greeting }}，{{ auth.user?.name || '林知夏' }}</h1><p class="page-intro">一方水土，一方志。今天也为门店留下几笔真实而有用的记录。</p></div><div class="heading-controls"><label class="store-select"><span class="eyebrow">当前门店</span><select v-model="selectedStore"><option v-for="store in stores" :key="store">{{ store }}</option></select><span class="select-chevron">⌄</span></label><RouterLink to="/creative" class="primary-button compact">＋ 新建内容</RouterLink></div></div>
    <section class="season-banner paper-banner"><div class="season-mark">{{ season.name }}</div><div><p class="eyebrow accent">{{ nextTerm }}经营灵感 · {{ today }}</p><h2>{{ season.idea }} · 为{{ nextTerm }}做准备</h2><p>把一碗热气、一束花、一段慢下来的时间，写进今天的内容里。</p></div><RouterLink to="/creative" class="secondary-button">查看灵感 →</RouterLink></section>
    <section class="creation-hero paper-hero"><div class="creation-copy"><p class="eyebrow accent">一方志 · 快速刊印</p><h2>拖入店铺素材，<br /><span>10 分钟生成全网视频。</span></h2><p>AI 识别店铺特征，提炼真实烟火，自动适配抖音、小红书与视频号。</p><RouterLink to="/publishing" class="primary-button">开始一次创作 <span>→</span></RouterLink></div><div class="hero-steps"><div class="hero-step"><span>01</span><b>上传素材</b><small>照片 / 视频 / 菜单</small></div><div class="hero-line" /><div class="hero-step"><span>02</span><b>AI 提炼</b><small>识别门店特征</small></div><div class="hero-line" /><div class="hero-step"><span>03</span><b>一键刊印</b><small>多端同步发布</small></div></div></section>
    <p class="demo-data-label">经营数据示例 · 尚未接入平台统计，以下数值与清单仅用于展示。</p><section class="metrics-grid paper-metrics"><article v-for="metric in metrics" :key="metric.label" class="metric-card paper-metric" :class="`metric-${metric.tone}`"><div class="metric-header"><span>{{ metric.label }}</span><span class="metric-glyph">↗</span></div><div class="metric-number">{{ metric.value }}<small>{{ metric.unit }}</small></div><div class="metric-foot"><span class="metric-delta">{{ metric.delta }}</span><span>{{ metric.note }}</span></div></article></section>
    <section class="dashboard-columns"><article class="panel paper-panel action-panel"><div class="panel-heading"><div><p class="eyebrow">接下来做什么</p><h3>今日编撰清单</h3></div><RouterLink to="/tasks" class="panel-link">查看全部 →</RouterLink></div><div class="task-list"><div class="task-row"><div class="task-icon accent">01</div><div class="task-copy"><strong>完成 3 条秋分滋补文案</strong><span>内容创作 · 青岚茶事</span></div><span class="task-time">约 12 分钟</span><RouterLink to="/tasks" class="row-arrow" aria-label="查看任务">→</RouterLink></div><div class="task-row"><div class="task-icon blue">02</div><div class="task-copy"><strong>审核小红书封面组图</strong><span>多端发布 · 4 张待确认</span></div><span class="task-time">约 8 分钟</span><RouterLink to="/tasks" class="row-arrow" aria-label="查看任务">→</RouterLink></div><div class="task-row"><div class="task-icon green">03</div><div class="task-copy"><strong>查看上周到店转化复盘</strong><span>数据分析 · 周报已生成</span></div><span class="task-time">约 5 分钟</span><RouterLink to="/analytics" class="row-arrow" aria-label="查看转化数据">→</RouterLink></div></div></article><article class="panel paper-panel trend-panel"><div class="panel-heading"><div><p class="eyebrow">到店趋势 · 示例</p><h3>烟火被看见，也走进店里</h3></div><span class="trend-total">+24.8%</span></div><div class="trend-chart"><span v-for="(height, index) in pulseBars" :key="index" class="trend-bar" :style="{ height: `${height}%`, animationDelay: `${index * 40}ms` }" /></div><div class="trend-axis"><span>周一</span><span>周四</span><span>周日</span></div></article></section>
    <footer class="dashboard-footer"><span><span class="status-pulse" /> 方志编撰中 · 演示模式</span><span class="mono">一方志 · 为每一方商家立传</span></footer>
  </div>
</template>
