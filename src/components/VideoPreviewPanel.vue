<script setup>
defineProps({
  isGenerating: Boolean,
  isMorphing: Boolean,
  outputUrl: { type: String, default: '' },
  format: { type: String, default: 'auto' },
  modelLabel: { type: String, required: true },
  duration: { type: Number, default: 10 },
  resolution: { type: String, default: '720p' },
  stageLabel: { type: String, default: '画面渲染中' },
  activeWorkspace: { type: String, default: 'video' },
  continueText: { type: String, default: '可以切换创作记录，或开启一段新的创作。' },
  error: { type: String, default: '' },
})
defineEmits(['switch-workspace'])
</script>

<template>
  <div class="video-workbench-canvas relative flex-1 bg-[#0A0A0B] flex flex-col items-center justify-center overflow-hidden" aria-label="成片预览">
    <div class="video-canvas-heading absolute top-6 w-full px-8 z-10 flex justify-between items-center pointer-events-none">
      <h2 class="text-gray-400 font-medium">成片预览</h2>
      <div class="video-mode-switch" role="group" aria-label="切换工作台">
        <button type="button" :class="{ active: activeWorkspace === 'video' }" :aria-pressed="activeWorkspace === 'video'" @click="$emit('switch-workspace', '/video/workbench')"><span class="material-symbols-outlined">auto_awesome</span>视频工作台</button>
        <button type="button" :class="{ active: activeWorkspace === 'digital-human' }" :aria-pressed="activeWorkspace === 'digital-human'" @click="$emit('switch-workspace', '/digital-human/studio')"><span class="material-symbols-outlined">record_voice_over</span>数字人摄影棚</button>
      </div>
    </div>

    <div v-if="isGenerating" class="video-render-stage" role="status" aria-live="polite">
      <div class="video-seal-loader" aria-hidden="true"><svg viewBox="0 0 80 80" fill="none"><rect class="video-seal-track" x="5" y="5" width="70" height="70" rx="3" /><rect class="video-seal-stroke" x="5" y="5" width="70" height="70" rx="3" /></svg><img src="/images/brand/yifangzhi-mark.png" alt="" /></div>
      <div class="video-generating-copy"><strong>正在生成视频</strong><p>模型正在渲染画面，长视频或高分辨率任务可能需要更久。</p></div>
      <div class="video-render-meta"><span>{{ modelLabel }}</span><i>·</i><span>{{ duration }} 秒</span><i>·</i><span>{{ resolution }}</span></div>
      <span class="video-render-stage-label"><i />{{ stageLabel }}</span>
      <p class="video-render-continue">{{ continueText }}</p>
    </div>
    <div v-else class="video-device-stage">
      <div class="video-player device-player relative z-10 overflow-hidden shadow-[0_0_120px_rgba(99,102,241,0.15)] transition-all duration-700 ease-[cubic-bezier(0.23,1,0.32,1)]" :class="[['3:4', '9:16'].includes(format) ? 'is-portrait device-phone' : 'is-landscape device-browser', { 'is-morphing': isMorphing, 'is-generating': isGenerating }]">
        <div class="device-static-border" :class="['3:4', '9:16'].includes(format) ? 'is-phone-border' : 'is-browser-border'" aria-hidden="true" />

        <div class="device-screen relative z-10 w-full h-full bg-[#0A0A0B] flex flex-col justify-center items-center overflow-hidden transition-all duration-700 ease-[cubic-bezier(0.23,1,0.32,1)]" :class="['3:4', '9:16'].includes(format) ? 'rounded-[calc(2.5rem-2px)]' : 'rounded-[calc(0.75rem-2px)]'">
          <div class="device-phone-ui absolute inset-0 pointer-events-none transition-opacity duration-500" :class="['3:4', '9:16'].includes(format) ? 'is-visible' : 'is-hidden'">
            <div class="device-island absolute top-3 left-1/2 -translate-x-1/2 w-24 h-7 bg-black rounded-full shadow-[inset_0_-1px_2px_rgba(255,255,255,0.1)] z-30" />
            <div class="device-home absolute bottom-3 left-1/2 -translate-x-1/2 w-32 h-1.5 bg-white/20 rounded-full z-30" />
          </div>

          <div class="device-mac-ui absolute top-0 left-0 w-full h-10 bg-white/[0.02] border-b border-white/5 flex items-center px-4 gap-1.5 pointer-events-none z-30 transition-opacity duration-500" :class="format === '16:9' ? 'is-visible' : 'is-hidden'">
            <div class="device-dot device-dot-red w-2.5 h-2.5 rounded-full" />
            <div class="device-dot device-dot-yellow w-2.5 h-2.5 rounded-full" />
            <div class="device-dot device-dot-green w-2.5 h-2.5 rounded-full" />
          </div>

          <div class="device-state-content relative z-20 flex flex-col items-center gap-4 mt-8">
            <video v-if="outputUrl" class="video-output-preview" :src="outputUrl" controls playsinline />
            <span v-else class="device-film-icon text-cinnabar-400/80 transition-transform duration-700 hover:scale-110">
              <svg class="w-12 h-12" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M7 4v16M17 4v16M3 8h4m10 0h4M3 12h18M3 16h4m10 0h4M4 20h16a1 1 0 001-1V5a1 1 0 00-1-1H4a1 1 0 00-1 1v14a1 1 0 001 1z" /></svg>
            </span>
            <div class="device-copy text-center space-y-2">
              <strong class="text-lg font-medium text-gray-200 tracking-wide"><span>{{ error ? '视频生成失败' : outputUrl ? '视频已完成' : '你的故事将在这里成片' }}</span></strong>
              <p class="text-xs text-gray-500">{{ error || (outputUrl ? '这一段商家故事，已成片。' : '添加素材并描述需求，开始生成') }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="video-canvas-hint absolute bottom-8 z-10 text-xs text-gray-600 tracking-wider pointer-events-none"><span>把商家的日常，写成值得记住的影像。</span></div>
  </div>
</template>
