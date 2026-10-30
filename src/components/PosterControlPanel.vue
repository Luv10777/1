<script setup>
import AppSelect from './AppSelect.vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ChevronRight, Plus, RotateCcw, X } from 'lucide-vue-next'
import { IMAGE_QUALITIES, IMAGE_RATIOS, POSTER_PURPOSE_GROUPS } from '../domain/imageCreation'
import { POSTER_STYLES } from '../domain/posterStyles'

const props = defineProps({ studio: { type: Object, required: true } })
const studio = computed(() => props.studio)
const promptInput = ref(null)
const fileInput = ref(null)
const dragging = ref(false)
const settingsOpen = ref(false)
const purposeGroup = ref(POSTER_PURPOSE_GROUPS.findIndex(group => group.options.some(option => option.label === studio.value.purpose)))
const currentPurposes = computed(() => POSTER_PURPOSE_GROUPS[Math.max(0, purposeGroup.value)].options)
const selectedStyle = computed(() => POSTER_STYLES.find(style => style.name === studio.value.style))
const serviceNotice = computed(() => studio.value.requiresLogin ? '登录后开始创作'
  : studio.value.capabilityState === 'checking' ? '正在检查创作服务…'
  : studio.value.capabilityState === 'error' ? `创作服务检查失败：${studio.value.capabilityError}`
  : !studio.value.configured ? '图片创作服务暂未接通' : '')

function hasFiles(event) { return Array.from(event.dataTransfer?.types || []).includes('Files') }
function onDragOver(event) {
  if (!hasFiles(event)) return
  event.preventDefault()
  dragging.value = true
}
function onDragLeave(event) { if (!event.relatedTarget) dragging.value = false }
function onDrop(event) {
  if (!hasFiles(event)) return
  event.preventDefault()
  dragging.value = false
  if (!studio.value.locked) studio.value.addFiles(event)
}
function onPaste(event) {
  if (studio.value.locked) return
  const images = Array.from(event.clipboardData?.files || []).filter(file => file.type.startsWith('image/'))
  if (!images.length) return
  event.preventDefault()
  studio.value.addFiles({ dataTransfer: { files: images } })
}
onMounted(() => {
  window.addEventListener('dragover', onDragOver)
  window.addEventListener('dragleave', onDragLeave)
  window.addEventListener('drop', onDrop)
  window.addEventListener('paste', onPaste)
})
onBeforeUnmount(() => {
  window.removeEventListener('dragover', onDragOver)
  window.removeEventListener('dragleave', onDragLeave)
  window.removeEventListener('drop', onDrop)
  window.removeEventListener('paste', onPaste)
})
</script>

<template>
  <form class="poster-control-panel" @submit.prevent="studio.generate">
    <header class="poster-control-head"><h1>营销海报</h1></header>
    <div class="poster-control-scroll">
      <section class="poster-control-group">
        <div class="poster-group-heading"><label for="poster-brief">画面描述</label><button v-if="studio.lastPrompt" type="button" class="poster-recall" :disabled="studio.locked" title="填入上一条提示词" @click="studio.recallLastPrompt(); promptInput?.focus()"><RotateCcw :size="13" aria-hidden="true" />填入上一条</button></div>
        <div class="poster-composer">
          <textarea id="poster-brief" ref="promptInput" v-model="studio.brief" :disabled="studio.locked" rows="4" maxlength="2000" placeholder="例如：为门店的招牌桂花乌龙做一张秋日海报，突出真实茶饮与到店氛围…" @keydown.meta.enter.prevent="studio.generate" @keydown.ctrl.enter.prevent="studio.generate" />
        </div>
      </section>

      <section class="poster-control-group">
        <div class="poster-group-heading"><span>参考图</span></div>
        <div class="poster-references">
          <div v-for="(file, index) in studio.files" :key="file.preview" class="poster-reference-chip" :title="file.name">
            <img :src="file.preview" :alt="file.name" />
            <div class="poster-reference-preview"><img :src="file.preview" :alt="file.name" /><span>{{ file.name }}</span></div>
            <AppSelect :value="file.role" :disabled="studio.locked" :aria-label="`${file.name}的参考用途`" @change="studio.files[index].role = $event"><option value="SUBJECT">主体</option><option value="STYLE">风格</option></AppSelect>
            <button type="button" class="poster-reference-remove" :disabled="studio.locked" :aria-label="`移除 ${file.name}`" @click="studio.removeFile(index)"><X :size="10" :stroke-width="2.4" aria-hidden="true" /></button>
          </div>
          <button v-if="studio.files.length < 6" type="button" class="poster-add-reference" :class="{ 'is-empty': !studio.files.length }" :disabled="studio.locked" :aria-label="studio.files.length ? '添加参考图' : undefined" @click="fileInput?.click()"><Plus :size="22" :stroke-width="1.6" aria-hidden="true" /><span v-if="!studio.files.length">添加参考图</span></button>
        </div>
        <input ref="fileInput" class="sr-only" type="file" accept="image/jpeg,image/png" multiple :disabled="studio.locked" @change="studio.addFiles" />
      </section>

      <section class="poster-control-group">
        <div class="poster-group-heading"><span>风格</span></div>
        <div class="poster-style-row">
          <img v-if="selectedStyle" class="poster-style-row-thumb" :src="`/images/poster-styles/${selectedStyle.key}.webp`" alt="" />
          <span v-else class="poster-style-row-thumb is-empty" aria-hidden="true"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span>
          <span class="poster-style-row-name" :class="{ muted: studio.style === '帮我搭配' }">{{ studio.style === '帮我搭配' ? '帮我搭配 · 从海报风格里挑选' : studio.style }}</span>
          <button v-if="studio.style !== '帮我搭配'" type="button" class="poster-style-reset" :disabled="studio.locked" :aria-label="`取消风格 ${studio.style}`" title="改为帮我搭配" @click="studio.style = '帮我搭配'"><X :size="15" :stroke-width="1.8" aria-hidden="true" /></button>
        </div>
      </section>

      <section class="poster-settings">
        <button type="button" class="poster-settings-toggle" :aria-expanded="settingsOpen" aria-controls="poster-settings" @click="settingsOpen = !settingsOpen"><span>输出设置</span><small>{{ studio.ratio }} · {{ studio.quality }} · {{ studio.purpose }}</small><ChevronRight :size="15" class="poster-settings-chevron" :class="{ open: settingsOpen }" aria-hidden="true" /></button>
        <div v-show="settingsOpen" id="poster-settings" class="poster-settings-body">
          <div class="poster-setting-row"><span>比例</span><AppSelect v-model="studio.ratio" class="poster-row-select" :disabled="studio.locked" aria-label="海报比例"><option v-for="ratio in IMAGE_RATIOS" :key="ratio" :value="ratio" :disabled="!studio.supportsRatio(ratio)">{{ ratio }}</option></AppSelect></div>
          <div class="poster-setting-row"><span>画质</span><div class="poster-quality-grid" role="group" aria-label="输出画质"><button v-for="quality in IMAGE_QUALITIES" :key="quality" type="button" :disabled="studio.locked || !studio.supportsQuality(quality)" :title="studio.supportsQuality(quality) ? undefined : '当前比例不支持该画质'" :aria-pressed="studio.quality === quality" :class="{ active: studio.quality === quality }" @click="studio.quality = quality">{{ quality }}</button></div></div>
          <div class="poster-purpose-picker">
            <span>用途</span>
            <div class="poster-purpose-groups" role="tablist" aria-label="发布用途分类"><button v-for="(group, index) in POSTER_PURPOSE_GROUPS" :key="group.label" type="button" role="tab" :aria-selected="purposeGroup === index" :class="{ active: purposeGroup === index }" @click="purposeGroup = index">{{ group.label }}</button></div>
            <div class="poster-purpose-options" role="group" aria-label="发布用途"><button v-for="option in currentPurposes" :key="option.label" type="button" :disabled="studio.locked" :aria-pressed="studio.purpose === option.label" :class="{ active: studio.purpose === option.label }" @click="studio.purpose = option.label">{{ option.label }}</button></div>
          </div>
        </div>
      </section>
    </div>
    <footer class="poster-control-footer"><p v-if="studio.error" class="poster-control-error" role="alert">{{ studio.error }}</p><p v-show="serviceNotice" class="poster-service-state" role="status">{{ serviceNotice }} <button v-if="studio.capabilityState === 'error'" type="button" class="creation-service-retry" @click="studio.loadCapabilities">重试</button></p><button type="submit" class="poster-generate-button" :disabled="studio.busy || (!studio.configured && !studio.requiresLogin) || studio.active || !!studio.pending">{{ studio.busy ? '正在提交…' : studio.active ? '创作中…' : '生成海报' }}</button><p class="poster-footer-meta">{{ studio.imageCredits != null ? `每张 ${studio.imageCredits} 烟火，` : '' }}成功交付后扣费</p></footer>
    <div v-if="dragging" class="poster-drop-overlay" aria-hidden="true"><span>松开，添加到参考图</span></div>
  </form>
</template>
