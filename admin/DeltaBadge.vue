<script setup>
import { computed } from 'vue'
import { change } from '../src/domain/creditStats.js'

/** 和上期比的涨跌：涨为绿色，跌为红色。pending 时显示说明文字（如登录记录还在积累）。 */
const props = defineProps({ current: { type: Number, default: null }, previous: { type: Number, default: null }, label: { type: String, default: '' }, pending: { type: String, default: '' } })
const result = computed(() => change(props.current, props.previous))
</script>

<template>
  <span v-if="pending" class="ad-delta none">{{ pending }}</span>
  <span v-else class="ad-delta" :class="result.tone" :title="previous == null ? '' : `上期 ${Number(previous).toLocaleString('zh-CN')}`">
    <b>{{ result.text }}</b><template v-if="label && result.tone !== 'none'"> {{ label }}</template>
  </span>
</template>
