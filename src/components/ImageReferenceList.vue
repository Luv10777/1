<script setup>
import AppSelect from './AppSelect.vue'
import { X } from 'lucide-vue-next'
defineProps({ files: { type: Array, required: true }, disabled: Boolean })
defineEmits(['remove', 'role'])
</script>

<template>
  <div v-if="files.length" class="creation-references">
    <article v-for="(file, index) in files" :key="file.preview" :title="file.name">
      <img :src="file.preview" :alt="file.name" />
      <AppSelect :value="file.role" :disabled="disabled" :aria-label="file.name + '的用途'" @change="$emit('role', index, $event)"><option value="SUBJECT">主体</option><option value="STYLE">风格</option></AppSelect>
      <button type="button" :disabled="disabled" :aria-label="'移除' + file.name" @click="$emit('remove', index)"><X :size="11" :stroke-width="2.4" aria-hidden="true" /></button>
    </article>
  </div>
</template>
