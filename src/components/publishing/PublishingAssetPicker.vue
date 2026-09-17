<script setup>
import { computed, ref, watch } from 'vue'
import { Folder, ChevronRight, ArrowLeft, Search, X, Check, Image, Video, Music2 } from 'lucide-vue-next'
import { assets, nestedFolders, assetSource } from '../../stores/assetLibrary'

const props = defineProps({ kind: { type: String, default: 'image' }, limit: { type: Number, default: 9 }, busy: Boolean, error: { type: String, default: '' } })
const emit = defineEmits(['select'])
const dialog = ref(null)
const folder = ref(null)
const query = ref('')
const selected = ref([])
const label = computed(() => ({ image: '图片', video: '视频', audio: '音乐' })[props.kind])
const icon = computed(() => ({ image: Image, video: Video, audio: Music2 })[props.kind])
const matchingFolders = computed(() => nestedFolders.value.filter(f => !query.value || f.label.includes(query.value)))
const items = computed(() => assets.value.filter(a => a.kind === props.kind &&
  (query.value ? a.name.toLowerCase().includes(query.value.toLowerCase()) : a.folder === folder.value)))
const count = id => assets.value.filter(a => a.folder === id && a.kind === props.kind).length
watch(() => props.kind, () => { selected.value = [] })
function open() {
  folder.value = null
  query.value = ''
  selected.value = []
  dialog.value.showModal()
}
function toggle(asset) {
  if (selected.value.includes(asset.id)) selected.value = selected.value.filter(id => id !== asset.id)
  else if (props.limit === 1) selected.value = [asset.id]
  else if (selected.value.length < props.limit) selected.value.push(asset.id)
}
function confirm() {
  const chosen = selected.value.map(id => assets.value.find(a => a.id === id)).filter(a => a && assetSource(a))
  if (chosen.length) emit('select', chosen)
}
defineExpose({ open, close: () => dialog.value.close() })
</script>

<template>
  <dialog ref="dialog" class="pub-library-dialog" aria-labelledby="pub-library-title" @cancel="busy && $event.preventDefault()" @click="!busy && $event.target === $event.currentTarget && dialog.close()">
    <header class="pub-library-header">
      <div><h2 id="pub-library-title"><Folder :size="19" />从素材库选择{{ label }}</h2><p>{{ kind === 'image' ? `可选择 ${limit} 张图片` : `选择 1 个${label}文件` }}</p></div>
      <button class="pub-icon-button" aria-label="关闭素材库" :disabled="busy" @click="dialog.close()"><X :size="20" /></button>
    </header>
    <div class="pub-library-search"><Search :size="16" /><input v-model="query" autofocus type="search" aria-label="搜索素材和文件夹" placeholder="搜索素材和文件夹" :disabled="busy" /></div>
    <nav class="pub-library-path" aria-label="素材文件夹路径"><button :disabled="busy" @click="folder = null; query = ''"><ArrowLeft v-if="folder" :size="14" />全部文件</button><template v-if="folder"><ChevronRight :size="13" /><span>{{ folder }}</span></template><span class="pub-library-kind">{{ label }}</span></nav>
    <div class="pub-library-content" :aria-busy="busy">
      <div v-if="folder === null" class="pub-library-folders">
        <button v-for="item in matchingFolders" :key="item.id" :disabled="busy" @click="folder = item.id; query = ''"><Folder :size="23" /><span><b>{{ item.label }}</b><small>{{ count(item.id) }} 个{{ label }}</small></span><ChevronRight :size="14" /></button>
      </div>
      <div v-if="items.length" class="pub-library-items">
        <button v-for="item in items" :key="item.id" class="pub-library-item" :class="{ selected: selected.includes(item.id) }" :aria-pressed="selected.includes(item.id)" :disabled="busy || !assetSource(item) || (limit > 1 && selected.length >= limit && !selected.includes(item.id))" @click="toggle(item)">
          <div class="pub-library-thumb"><img v-if="item.src" :src="item.src" :alt="item.name" loading="lazy" /><component :is="icon" v-else :size="28" /><span v-if="selected.includes(item.id)" class="pub-library-check"><Check :size="14" /></span></div>
          <b>{{ item.name }}</b><small>{{ assetSource(item) ? item.format : '源文件暂不可用' }}</small>
        </button>
      </div>
      <div v-else class="pub-library-empty"><component :is="icon" :size="26" /><p>{{ query ? '没有找到匹配的素材' : folder ? `此文件夹暂无${label}` : `暂无未归档${label}，可进入文件夹选择` }}</p></div>
    </div>
    <p v-if="error" class="pub-error" role="alert">{{ error }}</p>
    <footer class="pub-library-footer"><span>{{ selected.length }} / {{ limit }} 已选</span><button class="draft-btn" :disabled="busy" @click="dialog.close()">取消</button><button class="publish-btn" :disabled="busy || !selected.length" @click="confirm">{{ busy ? '正在载入…' : '使用所选素材' }}</button></footer>
  </dialog>
</template>
