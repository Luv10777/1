<script setup>
import { computed, ref } from 'vue'
import { FolderCheck, FolderPlus } from 'lucide-vue-next'
import LibraryFolderDialog from './LibraryFolderDialog.vue'
import { libraryApi, librarySavedState, markLibrarySaved } from '../services/library'

/**
 * 生成结果旁的「保存到作品库」：点开选位置（作品库或某个文件夹）。
 * 已经保存过的显示「已保存」，再点可以换个位置。class 等属性落在按钮上，方便各页面沿用自己的按钮样式。
 */
defineOptions({ inheritAttrs: false })
const props = defineProps({
  kind: { type: String, required: true, validator: value => ['IMAGE', 'VIDEO'].includes(value) },
  sourceId: { type: Number, required: true },
  disabled: Boolean,
  /** 只显示图标，适合图片角上的小按钮。 */
  compact: Boolean,
  iconSize: { type: Number, default: 15 },
})

const state = computed(() => librarySavedState(props.kind, props.sourceId))
const open = ref(false)
const busy = ref(false)
const error = ref('')
const label = computed(() => state.value.saved ? '已保存' : '保存到作品库')
const title = computed(() => state.value.saved
  ? `已保存到${state.value.folderName ? `「${state.value.folderName}」` : '作品库'}，点击可换个位置`
  : '保存到作品库')

function start() {
  if (props.disabled) return
  error.value = ''
  open.value = true
}

async function save(folderId) {
  busy.value = true
  error.value = ''
  try {
    const saved = await libraryApi.save(props.kind, props.sourceId, folderId)
    markLibrarySaved(props.kind, props.sourceId, saved)
    open.value = false
  } catch (failure) {
    error.value = failure.message || '保存失败，请重试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <button
    v-bind="$attrs" type="button" :disabled="disabled" :aria-pressed="state.saved" :aria-label="title" :title="title"
    :data-library-saved="state.saved || null" @click.stop="start"
  >
    <component :is="state.saved ? FolderCheck : FolderPlus" :size="iconSize" :stroke-width="1.8" aria-hidden="true" /><span v-if="!compact">{{ label }}</span>
  </button>
  <LibraryFolderDialog
    :open="open" :title="state.saved ? '换个保存位置' : '保存到作品库'" :confirm-text="state.saved ? '移到这里' : '保存'"
    :current-folder-id="state.folderId" :busy="busy" :error="error" @confirm="save" @cancel="open = false"
  />
</template>
