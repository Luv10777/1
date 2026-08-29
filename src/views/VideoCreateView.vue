<script setup>
import { ref } from 'vue'
import { RouterLink } from 'vue-router'

const recentTasks = [
  { id: 'v_1001', title: '双十一美妆大促混剪.mp4', type: 'AI 视频', status: 'completed', time: '10分钟前', cover: 'https://images.unsplash.com/photo-1596462502278-27bfdc403348?w=300&q=80' },
  { id: 'v_1002', title: 'CEO 年度寄语数字人.mp4', type: '数字人', status: 'rendering', time: '正在渲染 · 68%', cover: 'https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?w=300&q=80' },
  { id: 'v_1003', title: '秋季新品全景展示.mp4', type: 'AI 视频', status: 'completed', time: '昨天 15:30', cover: 'https://images.unsplash.com/photo-1441986300917-64674bd600d8?w=300&q=80' },
]

const selectedTask = ref(null)

const openTask = (task) => {
  selectedTask.value = task
}
</script>

<template>
  <div class="video-create-page">
    <header class="video-create-header">
      <div class="video-create-title-row">
        <div>
          <h1>AI 视频创作 <span class="video-create-spark">✦</span></h1>
          <p class="video-create-subtitle">把脚本、商品信息和品牌资产组合成高转化短视频，或一键唤醒专属数字人。</p>
        </div>
        <div class="video-create-status"><span class="video-create-status-dot" /> 云端工作区已就绪</div>
      </div>
    </header>

    <main>
      <section class="video-entry-grid" aria-label="视频创作入口">
        <RouterLink to="/video/workbench" class="video-entry-card video-entry-card-violet">
          <div class="video-entry-orb" aria-hidden="true" />
          <div class="video-entry-card-top">
            <div class="video-entry-icon video-entry-icon-violet"><span class="material-symbols-outlined">movie_edit</span></div>
          </div>
          <div class="video-entry-copy">
            <h2>AI 视频生成</h2>
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

      <section class="recent-video-section" aria-labelledby="recent-video-title">
        <div class="recent-video-heading">
          <div>
            <p class="video-create-kicker">ACTIVITY STREAM <span>·</span> LAST 7 DAYS</p>
            <h2 id="recent-video-title">最近创作</h2>
          </div>
          <RouterLink to="/tasks" class="recent-video-view-all">查看全部任务 <span aria-hidden="true">↗</span></RouterLink>
        </div>

        <div class="recent-video-list">
          <button v-for="task in recentTasks" :key="task.id" class="recent-video-row" type="button" @click="openTask(task)">
            <img class="recent-video-cover" :src="task.cover" :alt="`${task.title} 封面`" loading="lazy">
            <span class="recent-video-play" aria-hidden="true"><span class="material-symbols-outlined">play_arrow</span></span>
            <span class="recent-video-details">
              <strong>{{ task.title }}</strong>
              <span>{{ task.type }} <i>·</i> {{ task.id }}</span>
            </span>
            <span class="recent-video-status" :class="`is-${task.status}`">
              <span class="recent-video-status-icon material-symbols-outlined">{{ task.status === 'completed' ? 'check_circle' : 'progress_activity' }}</span>
              {{ task.status === 'completed' ? '已完成' : '渲染中' }}
            </span>
            <span class="recent-video-time">{{ task.time }}</span>
            <span class="recent-video-arrow" aria-hidden="true">→</span>
          </button>
        </div>

        <div v-if="selectedTask" class="recent-video-toast" role="status">
          <span class="material-symbols-outlined">task_alt</span>
          <span>已打开「{{ selectedTask.title }}」的任务详情</span>
          <button type="button" aria-label="关闭提示" @click="selectedTask = null">×</button>
        </div>
      </section>
    </main>
  </div>
</template>
