<script setup>
import { computed, ref } from 'vue'
import { niceMax, number } from '../../src/domain/partnerOps.js'

/**
 * 每天一根柱子的单系列柱状图。标题说明是什么指标，所以没有图例；悬停或键盘聚焦一根柱子显示当天数值；
 * 下方可展开表格，不依赖颜色读数。柱子最宽 24px，顶端 4px 圆角，从同一条基线长出。
 */
const props = defineProps({ points: { type: Array, default: () => [] }, field: { type: String, default: 'consumed' }, label: { type: String, default: '' } })
const hover = ref(null)
const showTable = ref(false)
const values = computed(() => props.points.map(p => Number(p[props.field] || 0)))
const max = computed(() => niceMax(values.value))
const total = computed(() => values.value.reduce((a, b) => a + b, 0))
const ticks = computed(() => [max.value, max.value / 2, 0])
const short = date => date.slice(5).replace('-', '/')
// 标签太密时只标一部分日期：最多约 10 个。
const labelEvery = computed(() => Math.max(1, Math.ceil(props.points.length / 10)))
</script>

<template>
  <div class="partner-chart">
    <p class="partner-chart-total">合计 <b class="mg-mono">{{ number(total) }}</b></p>
    <div class="partner-chart-body" role="img" :aria-label="`${label}每天的数值，合计 ${number(total)}`">
      <div class="partner-chart-axis" aria-hidden="true"><span v-for="t in ticks" :key="t">{{ number(t) }}</span></div>
      <div class="partner-chart-plot">
        <div class="partner-chart-grid" aria-hidden="true"><i /><i /><i /></div>
        <div class="partner-chart-bars">
          <button
            v-for="(p, i) in points" :key="p.date" type="button" class="partner-chart-col" :aria-label="`${p.date} ${label} ${number(values[i])}`"
            @mouseenter="hover = i" @mouseleave="hover = null" @focus="hover = i" @blur="hover = null"
          >
            <b :style="{ height: values[i] ? Math.max(2, values[i] / max * 100) + '%' : '0' }" />
            <span v-if="hover === i" class="partner-chart-tip" role="tooltip"><small>{{ p.date }}</small>{{ label }} <strong class="mg-mono">{{ number(values[i]) }}</strong></span>
          </button>
        </div>
        <div class="partner-chart-dates" aria-hidden="true"><span v-for="(p, i) in points" :key="p.date">{{ i % labelEvery === 0 || i === points.length - 1 ? short(p.date) : '' }}</span></div>
      </div>
    </div>
    <button class="mg-text-button partner-chart-toggle" type="button" :aria-expanded="showTable" @click="showTable = !showTable">{{ showTable ? '收起表格' : '查看表格' }}</button>
    <table v-if="showTable" class="management-table">
      <thead><tr><th>日期</th><th class="code-number">{{ label }}</th></tr></thead>
      <tbody><tr v-for="(p, i) in points" :key="p.date"><td>{{ p.date }}</td><td class="code-number mg-mono">{{ number(values[i]) }}</td></tr></tbody>
    </table>
  </div>
</template>
