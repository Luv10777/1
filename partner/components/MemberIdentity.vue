<script setup>
defineProps({
  number: { type: Number, default: null },
  name: { type: String, default: '' },
})
</script>

<template>
  <span class="partner-member-identity">
    <span v-if="number != null" class="partner-member-number">#{{ number }}</span>
    <span class="partner-member-name">{{ name || (number == null ? '编号未记录' : '未填写称呼') }}</span>
  </span>
</template>

<style scoped>
.partner-member-identity {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
}
.partner-member-number {
  color: var(--color-error, #b42318);
  font-size: .76em;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.partner-member-name { font-size: 1em; font-weight: 600; }
</style>
