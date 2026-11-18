<script setup>
defineProps({
  number: { type: Number, default: null },
  name: { type: String, default: '' },
})
</script>

<template>
  <span v-if="number != null" class="partner-member-identity">
    <strong>{{ String(number).padStart(3, '0') }}</strong><span>（{{ name || '未填写称呼' }}）</span>
  </span>
  <span v-else>{{ name || '编号未记录' }}</span>
</template>

<style scoped>
.partner-member-identity { display: inline-flex; align-items: baseline; white-space: nowrap; }
.partner-member-identity strong {
  color: var(--color-error, #b42318);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
</style>
