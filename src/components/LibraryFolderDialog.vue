<script setup>
import { nextTick, ref, watch } from 'vue'
import { Check, Folder, FolderPlus, Inbox, X } from 'lucide-vue-next'
import { libraryApi } from '../services/library'

/** 选一个作品库位置：未归类（不放进文件夹）或某个文件夹，也可以当场新建文件夹。 */
const props = defineProps({
  open: { type: Boolean, required: true },
  title: { type: String, default: '保存到作品库' },
  confirmText: { type: String, default: '保存' },
  currentFolderId: { type: Number, default: null },
  busy: Boolean,
  error: { type: String, default: '' },
})
const emit = defineEmits(['confirm', 'cancel', 'folders-changed'])

const folders = ref([])
const loading = ref(false)
const loadError = ref('')
const selected = ref(null)
const creating = ref(false)
const newName = ref('')
const createError = ref('')
const createBusy = ref(false)
const nameInput = ref(null)

watch(() => props.open, open => {
  if (!open) return
  selected.value = props.currentFolderId
  creating.value = false
  newName.value = ''
  createError.value = ''
  load()
}, { immediate: true })

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    folders.value = (await libraryApi.folders()).folders || []
  } catch (error) {
    loadError.value = error.message || '文件夹暂时无法读取'
  } finally {
    loading.value = false
  }
}

async function startCreate() {
  creating.value = true
  createError.value = ''
  await nextTick()
  nameInput.value?.focus()
}

async function createFolder() {
  const name = newName.value.trim()
  if (!name || createBusy.value) return
  createBusy.value = true
  createError.value = ''
  try {
    const folder = await libraryApi.createFolder(name)
    folders.value = [...folders.value, folder]
    selected.value = folder.id
    creating.value = false
    newName.value = ''
    emit('folders-changed')
  } catch (error) {
    createError.value = error.message || '文件夹创建失败'
  } finally {
    createBusy.value = false
  }
}

function onKeydown(event) {
  if (event.key === 'Escape' && !props.busy) emit('cancel')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="library-folder-scrim" @click.self="!busy && emit('cancel')" @keydown="onKeydown">
      <section class="library-folder-dialog" role="dialog" aria-modal="true" :aria-label="title">
        <header>
          <h2>{{ title }}</h2>
          <button type="button" class="library-folder-close" aria-label="关闭" :disabled="busy" @click="emit('cancel')"><X :size="16" aria-hidden="true" /></button>
        </header>
        <p v-if="loadError" class="library-folder-error" role="alert">{{ loadError }} <button type="button" @click="load">重试</button></p>
        <div class="library-folder-list" role="radiogroup" aria-label="保存位置">
          <button type="button" role="radio" :aria-checked="selected === null" :class="{ active: selected === null }" @click="selected = null">
            <Inbox :size="16" aria-hidden="true" /><span>未归类（不放进文件夹）</span><Check v-if="selected === null" :size="15" aria-hidden="true" />
          </button>
          <button
            v-for="folder in folders" :key="folder.id" type="button" role="radio" :aria-checked="selected === folder.id"
            :class="{ active: selected === folder.id }" @click="selected = folder.id"
          >
            <Folder :size="16" aria-hidden="true" /><span>{{ folder.name }}</span><small>{{ folder.workCount }}</small><Check v-if="selected === folder.id" :size="15" aria-hidden="true" />
          </button>
          <p v-if="loading && !folders.length" class="library-folder-hint">正在读取文件夹…</p>
        </div>
        <form v-if="creating" class="library-folder-create" @submit.prevent="createFolder">
          <input ref="nameInput" v-model="newName" maxlength="30" placeholder="文件夹名称" aria-label="新文件夹名称" :disabled="createBusy" />
          <button type="submit" :disabled="!newName.trim() || createBusy">{{ createBusy ? '正在创建…' : '创建' }}</button>
          <button type="button" class="is-plain" :disabled="createBusy" @click="creating = false">取消</button>
        </form>
        <button v-else type="button" class="library-folder-new" @click="startCreate"><FolderPlus :size="15" aria-hidden="true" />新建文件夹</button>
        <p v-if="createError || error" class="library-folder-error" role="alert">{{ createError || error }}</p>
        <footer>
          <button type="button" class="is-plain" :disabled="busy" @click="emit('cancel')">取消</button>
          <button type="button" class="is-primary" :disabled="busy || creating" :aria-busy="busy" @click="emit('confirm', selected)">{{ busy ? '正在保存…' : confirmText }}</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.library-folder-scrim { position: fixed; inset: 0; z-index: 1100; display: grid; place-items: center; padding: 20px; background: rgba(18, 18, 18, .42); }
.library-folder-dialog { display: grid; gap: 12px; width: min(380px, 100%); max-height: calc(100vh - 40px); overflow: auto; padding: 18px 20px; border: 1px solid var(--color-border-subtle); border-radius: 8px; color: var(--color-primary); background: var(--color-bg-surface); box-shadow: var(--shadow-paper-elevated); }
.library-folder-dialog header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.library-folder-dialog h2 { margin: 0; font: 600 16px/1.3 var(--font-serif); }
.library-folder-close { display: grid; place-items: center; width: 28px; height: 28px; padding: 0; border: 1px solid var(--color-border-subtle); border-radius: 4px; color: var(--color-text-muted); background: transparent; cursor: pointer; }
.library-folder-list { display: grid; gap: 4px; max-height: 280px; overflow: auto; }
.library-folder-list button { display: flex; align-items: center; gap: 9px; min-height: 38px; padding: 0 10px; border: 1px solid var(--color-border-subtle); border-radius: 4px; color: var(--color-primary); background: transparent; font-size: 13px; text-align: left; cursor: pointer; }
.library-folder-list button span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.library-folder-list button small { color: var(--color-text-muted); font: 12px var(--font-mono); }
.library-folder-list button:hover { background: var(--color-bg-subtle); }
.library-folder-list button.active { border-color: var(--color-accent); color: var(--color-accent-text); }
.library-folder-new { display: inline-flex; align-items: center; justify-self: start; gap: 6px; padding: 0; border: 0; color: var(--color-accent-text); background: transparent; font-size: 13px; cursor: pointer; }
.library-folder-create { display: flex; gap: 6px; }
.library-folder-create input { flex: 1; min-width: 0; height: 34px; padding: 0 10px; border: 1px solid var(--color-border-subtle); border-radius: 4px; color: var(--color-primary); background: var(--color-bg-surface); font-size: 13px; }
.library-folder-create input:focus { border-color: var(--color-accent); outline: 0; }
.library-folder-dialog footer { display: flex; justify-content: flex-end; gap: 8px; }
.library-folder-dialog footer button, .library-folder-create button { min-height: 34px; padding: 0 14px; border: 1px solid var(--color-border-subtle); border-radius: 4px; color: var(--color-primary); background: transparent; font-size: 13px; cursor: pointer; }
.library-folder-dialog .is-primary, .library-folder-create button[type="submit"] { border-color: var(--color-accent); color: var(--color-on-accent); background: var(--color-accent); }
.library-folder-dialog button:disabled { opacity: .55; cursor: default; }
.library-folder-hint { margin: 4px 0; color: var(--color-text-muted); font-size: 12px; }
.library-folder-error { margin: 0; color: var(--color-error); font-size: 12px; }
.library-folder-error button { padding: 0; border: 0; color: inherit; background: transparent; text-decoration: underline; cursor: pointer; }
</style>
