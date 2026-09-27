<script setup>
defineProps({ scenario: { type: Object, required: true }, index: { type: Number, required: true }, selected: Boolean, disabled: Boolean })
defineEmits(['select'])
</script>

<template>
  <button type="button" class="poster-inspiration-card group rounded-2xl border border-[#EBE6DF] bg-white text-left shadow-[0_4px_20px_rgba(0,0,0,0.04)]" :class="{ 'is-selected': selected }" :disabled="disabled" :aria-pressed="selected" @click="$emit('select', scenario)">
    <span class="poster-inspiration-thumb" aria-hidden="true"><img :src="scenario.image" alt="" loading="lazy" /><span class="poster-inspiration-thumb-line" /></span>
    <span class="poster-inspiration-copy"><span class="poster-inspiration-top"><small>{{ scenario.sector }}</small><b>{{ String(index + 1).padStart(2, '0') }}</b></span><strong>{{ scenario.title }}</strong><span>{{ scenario.description }}</span><em>取用这个方向 <span aria-hidden="true">↗</span></em></span>
  </button>
</template>
