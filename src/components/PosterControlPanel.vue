<script setup>
import AppSelect from './AppSelect.vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { IMAGE_QUALITIES, IMAGE_RATIOS, POSTER_PURPOSE_GROUPS, imageDimensions } from '../domain/imageCreation'
import { POSTER_SCENARIOS } from '../domain/posterScenarios'

const props = defineProps({ studio: { type: Object, required: true }, prompted: Boolean })
const studio = computed(() => props.studio)
const promptInput = ref(null)
const fileInput = ref(null)
const dragging = ref(false)
const purposeGroup = ref(POSTER_PURPOSE_GROUPS.findIndex(group => group.options.some(option => option.label === studio.value.purpose)))
const currentPurposes = computed(() => POSTER_PURPOSE_GROUPS[Math.max(0, purposeGroup.value)].options)
const qualityDimensions = quality => {
  try { return imageDimensions(quality, studio.value.ratio) }
  catch { return { width: 0, height: 0 } }
}
const serviceNotice = computed(() => studio.value.requiresLogin ? '登录后开始创作'
  : studio.value.capabilityState === 'checking' ? '正在检查创作服务…'
  : studio.value.capabilityState === 'error' ? `创作服务检查失败：${studio.value.capabilityError}`
  : !studio.value.configured ? '图片创作服务暂未接通' : '')
defineExpose({ focus: () => promptInput.value?.focus() })

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
      <section class="poster-control-section"><div class="poster-section-heading"><strong>经营场景</strong><small>可在右侧浏览灵感</small></div><div class="poster-focus-pills" role="group" aria-label="海报画面重点"><button v-for="scenario in POSTER_SCENARIOS" :key="scenario.focus" type="button" :disabled="studio.locked" :aria-pressed="studio.style === scenario.focus" :class="{ active: studio.style === scenario.focus }" @click="studio.style = scenario.focus">{{ scenario.focus }}</button></div></section>

      <section class="poster-control-section">
        <div class="poster-section-heading"><label for="poster-brief"><strong>画面描述</strong></label><button v-if="studio.lastPrompt" type="button" class="poster-recall" :disabled="studio.locked" title="填入上一条提示词" @click="studio.recallLastPrompt(); promptInput?.focus()">↶ 填入上一条</button></div>
        <div class="poster-composer" :class="{ 'is-prompted': prompted }">
          <textarea id="poster-brief" ref="promptInput" v-model="studio.brief" :disabled="studio.locked" rows="5" maxlength="2000" placeholder="例如：为门店的招牌桂花乌龙做一张秋日海报，突出真实茶饮与到店氛围…" @keydown.meta.enter.prevent="studio.generate" @keydown.ctrl.enter.prevent="studio.generate" />
          <div class="poster-inline-references"><div v-for="(file, index) in studio.files" :key="file.preview" class="poster-reference-chip"><img :src="file.preview" :alt="file.name" /><button type="button" class="poster-reference-remove" :disabled="studio.locked" :aria-label="`移除 ${file.name}`" @click="studio.removeFile(index)">×</button><div class="poster-reference-preview"><img :src="file.preview" :alt="file.name" /><span>{{ file.name }}</span></div><AppSelect :value="file.role" :disabled="studio.locked" :aria-label="`${file.name}的参考用途`" @change="studio.files[index].role = $event"><option value="SUBJECT">主体</option><option value="STYLE">风格</option></AppSelect></div><div class="poster-reference-actions"><button type="button" class="poster-add-reference" :disabled="studio.locked || studio.files.length >= 6" @click="fileInput?.click()"><span aria-hidden="true">＋</span> 从本地添加</button></div></div>
        </div>
        <input ref="fileInput" class="sr-only" type="file" accept="image/jpeg,image/png" multiple :disabled="studio.locked" @change="studio.addFiles" />
        <p class="poster-field-note">可拖入图片或粘贴截图 · 最多 6 张 JPG/PNG</p>
      </section>

      <details class="poster-output-settings">
        <summary><strong>输出设置</strong><span>{{ studio.ratio }} · {{ studio.quality }} · {{ studio.purpose }}</span></summary>
        <section class="poster-control-section"><div class="poster-section-heading"><strong>海报比例</strong></div><div class="poster-ratio-grid" role="group" aria-label="海报比例"><button v-for="ratio in IMAGE_RATIOS" :key="ratio" type="button" :disabled="studio.locked || !studio.supportsRatio(ratio)" :aria-pressed="studio.ratio === ratio" :class="{ active: studio.ratio === ratio }" @click="studio.ratio = ratio"><span class="poster-ratio-icon" :style="{ aspectRatio: ratio.replace(':', ' / ') }" />{{ ratio }}</button></div></section>

        <section class="poster-control-section"><div class="poster-section-heading"><strong>输出画质</strong><small>按当前比例生成原生尺寸</small></div><div class="poster-quality-grid" role="group" aria-label="输出画质"><button v-for="quality in IMAGE_QUALITIES" :key="quality" type="button" :disabled="studio.locked || !studio.supportsQuality(quality)" :aria-pressed="studio.quality === quality" :class="{ active: studio.quality === quality }" @click="studio.quality = quality"><strong>{{ quality }}</strong><small>{{ qualityDimensions(quality).width ? `${qualityDimensions(quality).width} × ${qualityDimensions(quality).height}` : '当前比例不可用' }}</small></button></div></section>

        <section class="poster-control-section"><div class="poster-section-heading"><strong>发布用途</strong><small>决定平台构图、文案密度和留白</small></div><div class="poster-purpose-groups" role="tablist" aria-label="发布用途分类"><button v-for="(group, index) in POSTER_PURPOSE_GROUPS" :key="group.label" type="button" role="tab" :aria-selected="purposeGroup === index" :class="{ active: purposeGroup === index }" @click="purposeGroup = index">{{ group.label }}</button></div><div class="poster-purpose-options" role="group" aria-label="发布用途"><button v-for="option in currentPurposes" :key="option.label" type="button" :disabled="studio.locked" :aria-pressed="studio.purpose === option.label" :class="{ active: studio.purpose === option.label }" @click="studio.purpose = option.label">{{ option.label }}</button></div></section>
      </details>
    </div>
    <footer class="poster-control-footer"><p v-if="studio.error" class="poster-control-error" role="alert">{{ studio.error }}</p><p class="poster-footer-meta">{{ studio.imageCredits != null ? `每张 ${studio.imageCredits} 烟火 · ` : '' }}成功交付后扣费 · 存入作品库<RouterLink v-if="studio.billing.summary && !studio.billing.summary.memberActive" to="/billing" class="generation-billing-link">请先开通会员 →</RouterLink></p><p v-show="serviceNotice" class="poster-service-state" role="status">{{ serviceNotice }} <button v-if="studio.capabilityState === 'error'" type="button" class="creation-service-retry" @click="studio.loadCapabilities">重试</button></p><button type="submit" class="poster-generate-button bg-[#B9382C] text-white shadow-sm shadow-[#B9382C]/20 hover:bg-[#A32F24]" :disabled="studio.busy || (!studio.configured && !studio.requiresLogin) || studio.active || !!studio.pending">{{ studio.busy ? '正在提交…' : studio.active ? '创作中…' : '生成海报' }} <span aria-hidden="true">↗</span></button></footer>
    <div v-if="dragging" class="poster-drop-overlay" aria-hidden="true"><span>松开，添加到参考图</span></div>
  </form>
</template>
