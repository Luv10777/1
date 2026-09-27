<script setup>
import { ref, toRef } from 'vue'
import { IMAGE_RATIOS, POSTER_PURPOSE_GROUPS } from '../domain/imageCreation'
import ImageReferenceList from './ImageReferenceList.vue'

const props = defineProps({ studio: { type: Object, required: true } })
const studio = toRef(props, 'studio')
const promptInput = ref(null)
const fileInput = ref(null)
const referencesOpen = ref(false)
defineExpose({ focus: () => promptInput.value?.focus() })
</script>

<template>
  <div class="floating-prompt-shell creation-prompt-shell">
    <div v-if="referencesOpen" class="prompt-popover creation-reference-popover">
      <div class="creation-popover-title"><strong>添加参考照片</strong><button type="button" aria-label="关闭参考照片面板" @click="referencesOpen = false">×</button></div>
      <p>商品照片保留主体，风格参考只借鉴设计。最多 6 张。</p>
      <label class="drop-zone" tabindex="0" @keydown.enter.prevent="fileInput.click()" @keydown.space.prevent="fileInput.click()" @dragover.prevent @drop.prevent="studio.addFiles"><input ref="fileInput" type="file" accept="image/jpeg,image/png" multiple :disabled="studio.locked" @change="studio.addFiles" /><span class="drop-icon">＋</span><strong>选择或拖入商品 / 门店 / 参考海报</strong><small>JPG、PNG · 每张不超过 20 MB</small></label>
      <ImageReferenceList :files="studio.files" :disabled="studio.locked" @remove="studio.removeFile" @role="(index, role) => studio.files[index].role = role" />
      <p v-if="studio.error" class="creation-item-error" role="alert">{{ studio.error }}</p>
    </div>
    <form class="floating-prompt-bar" @submit.prevent="studio.generate">
      <div class="creation-prompt-context"><span><span class="prompt-pill-dot" />说一句需求，AI 帮你安排文案与画面</span></div>
      <textarea ref="promptInput" v-model="studio.brief" :disabled="studio.locked" rows="2" maxlength="2000" placeholder="说说门店、商品和活动... 例如：周末奶茶第二杯半价，突出招牌饮品，做张朋友圈海报" aria-label="描述你的海报需求" @keydown.meta.enter.prevent="studio.generate" @keydown.ctrl.enter.prevent="studio.generate" />
      <div class="prompt-toolbar">
        <fieldset class="prompt-tools" :disabled="studio.locked">
          <button type="button" class="prompt-pill" :class="{ active: referencesOpen }" :aria-expanded="referencesOpen" @click="referencesOpen = !referencesOpen">＋ 参考照片{{ studio.files.length ? ' · ' + studio.files.length : '' }}</button>
          <label class="prompt-pill creation-select-pill">画面重点<select v-model="studio.style" aria-label="海报画面重点"><option>帮我搭配</option><option>招牌产品</option><option>活动促销</option><option>新店开业</option><option>到店氛围</option><option>服务特色</option><option>节气节日</option></select></label>
          <label class="prompt-pill creation-select-pill">比例<select v-model="studio.ratio" aria-label="海报比例"><option v-for="ratio in IMAGE_RATIOS" :key="ratio" :disabled="!studio.supportsRatio(ratio)">{{ ratio }}</option></select></label>
          <label class="prompt-pill creation-select-pill purpose-select-pill">用途<select v-model="studio.purpose" aria-label="海报发布用途"><optgroup v-for="group in POSTER_PURPOSE_GROUPS" :key="group.label" :label="group.label"><option v-for="option in group.options" :key="option.label" :value="option.label">{{ option.label }}</option></optgroup></select></label>
        </fieldset>
        <button type="submit" class="prompt-send creation-send" :disabled="studio.busy || !studio.configured || studio.active || !!studio.pending">{{ studio.busy ? '正在提交…' : studio.active ? '创作中…' : '生成海报' }}<span v-if="!studio.busy && !studio.active" aria-hidden="true">→</span></button>
      </div>
      <div class="creation-prompt-foot"><span role="status">{{ studio.capabilities && !studio.configured ? '图片创作服务尚未接通，可先准备需求和照片' : studio.capabilities ? '自动理解需求 · 无需编写提示词' : '正在检查创作服务…' }}</span><span class="save-target">保存到：<strong>作品库</strong></span></div>
    </form>
  </div>
</template>
