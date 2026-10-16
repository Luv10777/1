<script setup>
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="video-create-page">
    <header class="video-create-header">
      <div class="video-create-title-row">
        <div>
          <h1>视频创作</h1>
          <p class="video-create-subtitle">把脚本、商品信息和品牌资产组合成高转化短视频，或一键唤醒专属数字人。</p>
        </div>
        <div class="video-create-status"><span class="video-create-status-dot" /> 云端工作区已就绪</div>
      </div>
    </header>

    <main>
      <section class="video-entry-grid" aria-label="视频创作入口">
        <RouterLink to="/video/workbench" class="video-entry-card video-entry-card-cinnabar">
          <div class="video-entry-orb" aria-hidden="true" />
          <div class="video-entry-card-top">
            <div class="video-entry-icon video-entry-icon-cinnabar"><span class="material-symbols-outlined">movie_edit</span></div>
          </div>
          <div class="video-entry-copy">
            <h2>视频生成</h2>
            <p>支持文本生视频、图生视频与智能混剪。将零散素材一键自动包装为极具网感的营销短视频。</p>
          </div>
          <span class="video-entry-cta">进入创作台 <span aria-hidden="true">→</span></span>
        </RouterLink>

        <RouterLink to="/digital-human/studio" class="video-entry-card video-entry-card-cyan">
          <div class="video-entry-orb" aria-hidden="true" />
          <div class="video-entry-card-top">
            <div class="video-entry-icon video-entry-icon-cyan"><span class="material-symbols-outlined">record_voice_over</span></div>
          </div>
          <div class="video-entry-copy">
            <h2>数字人视频生成</h2>
            <p>输入文案或语音，一键生成唇形同步、情感丰富的真人级数字人播报视频，支持多国语言。</p>
          </div>
          <span class="video-entry-cta">进入录影棚 <span aria-hidden="true">→</span></span>
        </RouterLink>
      </section>
    </main>
  </div>
</template>
