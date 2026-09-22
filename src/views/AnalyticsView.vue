<script setup>
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
const period = ref('7')
const measures = [
  { label: '全网曝光量', value: '128.6 万', detail: '平台内容曝光合计' },
  { label: '多平台同步数', value: '42 条', detail: '已完成同步的内容' },
  { label: 'POI 到店引导', value: '2,486 次', detail: '地点详情与导航点击' },
  { label: '团购转化估算', value: '18.4 万元', detail: '示例订单金额，非结算依据' },
]
const trend = computed(() => period.value === '7' ? [24, 38, 32, 56, 47, 64, 79] : [18, 24, 32, 29, 48, 56, 49, 69, 75, 66, 84, 92])
const points = computed(() => trend.value.map((value, i) => `${48 + i * (604 / (trend.value.length - 1))},${236 - value * 2}`).join(' '))
const channels = [
 { name: '抖音', exposure: '76.2 万', poi: '1,568', orders: '312', color: 'var(--color-accent)' },
 { name: '小红书', exposure: '34.1 万', poi: '621', orders: '118', color: 'var(--color-info)' },
 { name: '视频号', exposure: '18.3 万', poi: '297', orders: '66', color: 'var(--color-primary)' },
]
</script>
<template>
  <div class="analytics-page paper-page">
    <header class="page-heading"><div><p class="eyebrow">经营有据 · 方志有声</p><h1>每一次被看见，都有迹可循。</h1><p class="page-intro">从内容曝光到地点引导，再到团购转化，了解门店的每一步。</p></div><label class="analytics-period">统计周期<select v-model="period"><option value="7">近 7 日</option><option value="30">近 30 日</option></select></label></header>
    <p class="demo-data-label">示例数据 · 平台统计接口尚未接入。POI 点击不等于实际到店；团购转化仅作估算。</p>
    <section class="metrics-grid"><article v-for="metric in measures" :key="metric.label" class="metric-card"><div class="metric-header">{{ metric.label }}</div><div class="metric-number">{{ metric.value }}</div><p class="metric-foot">{{ metric.detail }}</p></article></section>
    <div class="analytics-panels"><section class="panel"><div class="panel-heading"><div><p class="eyebrow">内容到店路径</p><h3>POI 引导趋势</h3></div><span class="chart-key">● 到店引导示例</span></div><svg class="analytics-chart" viewBox="0 0 700 280" role="img" :aria-label="`近 ${period} 日到店引导趋势示例：整体上升`"><g v-for="n in [0, 25, 50, 75, 100]" :key="n"><line x1="48" x2="652" :y1="236 - n * 2" :y2="236 - n * 2" stroke="var(--color-border-subtle)" /><text x="8" :y="240 - n * 2" fill="var(--color-text-muted)" font-size="11">{{ n }}</text></g><polyline :points="points" fill="none" stroke="var(--color-accent)" stroke-width="3" stroke-linejoin="round" /><text x="48" y="268" fill="var(--color-text-muted)" font-size="11">周期开始</text><text x="600" y="268" fill="var(--color-text-muted)" font-size="11">周期结束</text></svg></section><section class="panel conversion-note"><p class="eyebrow">不止于流量</p><h2>这不是流量，<br />这是生意。</h2><p>让每一条内容都带上门店位置和可用团购券，给顾客一个明确的到店理由。</p><RouterLink to="/publishing" class="secondary-button">去配置门店挂载 →</RouterLink></section></div>
    <section class="panel analytics-table"><div class="panel-heading"><h3>各平台转化明细 · 示例</h3><RouterLink to="/publishing/platforms" class="panel-link">管理账号矩阵 →</RouterLink></div><div class="table-scroll"><table><thead><tr><th>平台</th><th>曝光量</th><th>POI 引导</th><th>估算订单</th></tr></thead><tbody><tr v-for="channel in channels" :key="channel.name"><th><i :style="{ background: channel.color }" />{{ channel.name }}</th><td>{{ channel.exposure }}</td><td>{{ channel.poi }}</td><td>{{ channel.orders }}</td></tr></tbody></table></div></section>
  </div>
</template>
