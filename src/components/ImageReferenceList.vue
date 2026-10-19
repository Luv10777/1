<script setup>
import AppSelect from './AppSelect.vue'
defineProps({ files: { type: Array, required: true }, disabled: Boolean })
defineEmits(['remove', 'role'])
</script>

<template>
  <div v-if="files.length" class="creation-references">
    <article v-for="(file, index) in files" :key="file.preview">
      <img :src="file.preview" :alt="file.name" />
      <div><span :title="file.name">{{ file.name }}</span><AppSelect :value="file.role" :disabled="disabled" :aria-label="file.name + '的用途'" @change="$emit('role', index, $event)"><option value="SUBJECT">商品 / 门店主体</option><option value="STYLE">只参考风格</option></AppSelect></div>
      <button type="button" :disabled="disabled" :aria-label="'移除' + file.name" @click="$emit('remove', index)">×</button>
    </article>
  </div>
</template>
