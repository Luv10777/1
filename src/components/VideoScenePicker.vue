<script setup>
import { ref } from 'vue'
import { Check, X } from 'lucide-vue-next'
import { videoSceneCover } from '../services/videoScenes'

// 视频工作台「场景」一栏点开的面板：每个场景一张卡片，点一张即选中并关闭。
defineProps({
  scenes: { type: Array, default: () => [] },
  modelValue: { type: String, default: 'free' },
})
const emit = defineEmits(['update:modelValue'])
const dialog = ref(null)

const open = () => dialog.value?.showModal()
const close = () => dialog.value?.close()
const pick = key => { emit('update:modelValue', key); close() }
defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="vs-scene-dialog" aria-labelledby="vs-scene-title" @click.self="close">
    <div class="vs-scene-sheet">
      <header>
        <h2 id="vs-scene-title">选择创作场景</h2>
        <button type="button" class="vs-scene-close" aria-label="关闭" @click="close"><X :size="18" :stroke-width="1.8" aria-hidden="true" /></button>
      </header>
      <p class="vs-scene-intro">场景只补你没写到的拍法，你写的内容优先。</p>
      <div class="vs-scene-grid">
        <button v-for="scene in scenes" :key="scene.key" type="button" class="vs-scene-card" :class="{ active: modelValue === scene.key }" :aria-pressed="modelValue === scene.key" @click="pick(scene.key)">
          <span class="vs-scene-cover" :data-scene="scene.key">
            <img v-if="videoSceneCover(scene.key)" :src="videoSceneCover(scene.key)" alt="" loading="lazy">
            <span v-else class="material-symbols-outlined" aria-hidden="true">{{ scene.icon }}</span>
            <Check v-if="modelValue === scene.key" class="vs-scene-check" :size="14" :stroke-width="2.6" aria-hidden="true" />
          </span>
          <strong>{{ scene.name }}</strong>
          <small>{{ scene.summary }}</small>
        </button>
      </div>
    </div>
  </dialog>
</template>
