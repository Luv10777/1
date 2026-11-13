<script setup>
import { Check } from 'lucide-vue-next'

/**
 * 选项卡片：在抽屉和弹窗里代替下拉框，所有选项直接摊开，点一张卡片就选中。
 * options：[{ value, title, detail?, badge?, disabled? }]；value 用字符串比较。
 */
const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  options: { type: Array, default: () => [] },
  label: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])
const pick = option => { if (!props.disabled && !option.disabled) emit('update:modelValue', option.value) }
</script>

<template>
  <div class="partner-options" role="radiogroup" :aria-label="label">
    <button
      v-for="option in options" :key="option.value" type="button" role="radio" class="partner-option"
      :class="{ selected: String(option.value) === String(modelValue) }" :aria-checked="String(option.value) === String(modelValue)"
      :disabled="disabled || option.disabled" @click="pick(option)"
    >
      <span class="partner-option-mark"><Check v-if="String(option.value) === String(modelValue)" :size="13" /></span>
      <span class="partner-option-text"><strong>{{ option.title }}</strong><small v-if="option.detail">{{ option.detail }}</small></span>
      <span v-if="option.badge" class="partner-option-badge">{{ option.badge }}</span>
    </button>
  </div>
</template>
