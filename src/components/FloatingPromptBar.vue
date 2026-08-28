<script setup>
import { ref } from 'vue'

const emit = defineEmits(['generate'])
const prompt = ref('')
const activePopover = ref(null)
const uploadOpen = ref(false)
const uploadedName = ref('')
const fileInput = ref(null)

const styles = ['简约风', '复古怀旧', '东方雅致', '轻盈自然']
const ratios = [
  { label: '1:1', name: '正方形' },
  { label: '3:4', name: '人物肖像' },
  { label: '9:16', name: '活动宣传' },
]
const selectedStyle = ref(styles[0])
const selectedRatio = ref(ratios[1].label)

const toggle = (name) => { activePopover.value = activePopover.value === name ? null : name }
const chooseStyle = (style) => { selectedStyle.value = style; activePopover.value = null }
const chooseRatio = (ratio) => { selectedRatio.value = ratio.label; activePopover.value = null }
const openUpload = () => { activePopover.value = null; uploadOpen.value = true }
const onFile = (event) => {
  const file = event.target?.files?.[0] || event.dataTransfer?.files?.[0]
  if (file) uploadedName.value = file.name
}
const submit = () => emit('generate', { prompt: prompt.value, style: selectedStyle.value, ratio: selectedRatio.value })
</script>

<template>
  <div class="floating-prompt-shell">
    <div v-if="activePopover === 'style'" class="prompt-popover style-popover">
      <p class="popover-title">选择视觉风格</p>
      <button v-for="style in styles" :key="style" type="button" class="style-option" :class="{ selected: selectedStyle === style }" @click="chooseStyle(style)"><span class="style-thumb" :class="`style-thumb-${styles.indexOf(style) + 1}`" /><span>{{ style }}</span><i v-if="selectedStyle === style">✓</i></button>
    </div>
    <div v-if="activePopover === 'ratio'" class="prompt-popover ratio-popover">
      <p class="popover-title">选择画面比例</p>
      <button v-for="ratio in ratios" :key="ratio.label" type="button" class="ratio-option" :class="{ selected: selectedRatio === ratio.label }" @click="chooseRatio(ratio)"><span class="ratio-icon" :class="`ratio-icon-${ratio.label.replace(':', '-')}`" /><span><strong>{{ ratio.label }}</strong><small>{{ ratio.name }}</small></span><i v-if="selectedRatio === ratio.label">✓</i></button>
    </div>

    <div class="floating-prompt-bar">
      <textarea v-model="prompt" rows="2" placeholder="说说你的灵感..." aria-label="描述你的海报灵感" @keydown.meta.enter.prevent="submit" @keydown.ctrl.enter.prevent="submit" />
      <div class="prompt-toolbar">
        <div class="prompt-tools">
          <button type="button" class="prompt-pill active" aria-pressed="true" @click="toggle('agent')"><span class="prompt-pill-dot" /> Agent 模式</button>
          <button type="button" class="prompt-pill" @click="openUpload">＋ 参考海报</button>
          <button type="button" class="prompt-pill" :class="{ active: activePopover === 'style' }" :aria-expanded="activePopover === 'style'" aria-haspopup="dialog" @click="toggle('style')">风格 · {{ selectedStyle }}</button>
          <button type="button" class="prompt-pill" :class="{ active: activePopover === 'ratio' }" :aria-expanded="activePopover === 'ratio'" aria-haspopup="menu" @click="toggle('ratio')">比例 · {{ selectedRatio }}</button>
          <button type="button" class="prompt-pill" @click="prompt = `${prompt}${prompt ? '，' : ''}请优化构图、层次与转化重点`">润色提示词</button>
        </div>
        <div class="prompt-submit-group"><span class="save-target">保存到：<strong>作品库</strong></span><button type="button" class="prompt-send" aria-label="生成海报" @click="submit"><span class="material-symbols-outlined" aria-hidden="true">send</span></button></div>
      </div>
    </div>
  </div>

  <div v-if="uploadOpen" class="upload-overlay" @click.self="uploadOpen = false">
    <section class="upload-modal" role="dialog" aria-modal="true" aria-labelledby="upload-title"><button class="upload-close" type="button" aria-label="关闭" @click="uploadOpen = false">×</button><p class="eyebrow accent">参考素材</p><h2 id="upload-title">添加参考海报</h2><p class="upload-intro">拖入一张喜欢的海报，AI 会参考它的构图、节奏与视觉语气。</p><label class="drop-zone" @dragover.prevent @drop.prevent="onFile"><input ref="fileInput" type="file" accept="image/*" @change="onFile" /><span class="drop-icon">＋</span><strong>{{ uploadedName || '拖拽图片到这里' }}</strong><small>支持 PNG、JPG，最大 20 MB</small><span class="upload-button">从本地上传</span></label><button class="secondary-button upload-done" type="button" :disabled="!uploadedName" @click="uploadOpen = false">{{ uploadedName ? '使用这张参考图' : '稍后添加' }}</button></section>
  </div>
</template>
