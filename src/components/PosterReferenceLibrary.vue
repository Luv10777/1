<script setup>
import { computed, ref } from 'vue'
import { assets, assetSource } from '../stores/assetLibrary'

const props = defineProps({ files: { type: Array, required: true }, disabled: Boolean, sourceAssets: { type: Array, default: null } })
const emit = defineEmits(['choose', 'close'])
const query = ref('')
const pictures = computed(() => (props.sourceAssets || assets.value).filter(asset => (asset.kind === 'image' || asset.type === 'IMAGE') && assetSource(asset)
  && (!query.value.trim() || `${asset.name} ${asset.folder || ''}`.toLowerCase().includes(query.value.trim().toLowerCase()))))
const selected = asset => props.files.some(file => file.libraryId === asset.id)
</script>

<template>
  <div class="poster-reference-library" aria-label="从素材库选择参考图">
    <div class="poster-library-head"><div><strong>素材库图片</strong><small>点击图片加入或移除参考图</small></div><button type="button" aria-label="收起素材库" @click="emit('close')">×</button></div>
    <input v-model="query" type="search" aria-label="搜索素材库图片" placeholder="搜索图片或文件夹" />
    <div v-if="pictures.length" class="poster-library-grid">
      <button v-for="asset in pictures" :key="asset.id" type="button" class="poster-library-card" :class="{ selected: selected(asset) }" :aria-pressed="selected(asset)" :disabled="disabled || (files.length >= 6 && !selected(asset))" @click="emit('choose', asset)"><img :src="assetSource(asset)" :alt="asset.name" loading="lazy" /><span class="poster-library-card-copy"><strong :title="asset.name">{{ asset.name }}</strong><small>{{ asset.folder || '我的素材' }}</small></span><span v-if="selected(asset)" class="poster-library-check" aria-hidden="true">✓</span></button>
    </div>
    <p v-else class="poster-library-empty">{{ query ? '没有找到匹配的图片' : '素材库暂无可用图片' }}</p>
    <p class="poster-library-count">已添加 {{ files.length }} / 6 张参考图</p>
  </div>
</template>
