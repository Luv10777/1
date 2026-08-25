<script setup>
import { ref, reactive, onMounted } from 'vue'
import { isDemoMode } from '../utils/config'
import Loading from '../components/Loading.vue'
import Empty from '../components/Empty.vue'

const assets = ref([])
const loading = ref(true)
const filter = reactive({
  type: 'all',
  category: ''
})

const typeMap = {
  IMAGE: '图片',
  VIDEO: '视频',
  AUDIO: '音频',
  DOCUMENT: '文档'
}

onMounted(async () => {
  await loadAssets()
})

async function loadAssets() {
  loading.value = true
  try {
    if (isDemoMode()) {
      await new Promise(resolve => setTimeout(resolve, 500))
      assets.value = [
        {
          id: 'asset_1',
          code: 'A1724580000001',
          name: '青岚茶事秋季新品海报',
          type: 'IMAGE',
          category: '营销素材',
          fileUrl: '/demo/poster1.jpg',
          thumbnailUrl: '/demo/thumb1.jpg',
          width: 1080,
          height: 1920,
          fileSize: 2048000,
          mimeType: 'image/jpeg',
          usageCount: 12,
          status: 'AVAILABLE',
          createdAt: '2024-08-20T10:00:00Z'
        },
        {
          id: 'asset_2',
          code: 'A1724580000002',
          name: '门店宣传视频素材',
          type: 'VIDEO',
          category: '品牌宣传',
          fileUrl: '/demo/video1.mp4',
          thumbnailUrl: '/demo/video-thumb1.jpg',
          duration: 30,
          fileSize: 15360000,
          mimeType: 'video/mp4',
          usageCount: 5,
          status: 'AVAILABLE',
          createdAt: '2024-08-22T14:00:00Z'
        },
        {
          id: 'asset_3',
          code: 'A1724580000003',
          name: '产品介绍配音',
          type: 'AUDIO',
          category: '配音素材',
          fileUrl: '/demo/audio1.mp3',
          duration: 45,
          fileSize: 1024000,
          mimeType: 'audio/mpeg',
          usageCount: 3,
          status: 'AVAILABLE',
          createdAt: '2024-08-23T09:00:00Z'
        }
      ]
    }
  } catch (error) {
    console.error('加载素材失败:', error)
  } finally {
    loading.value = false
  }
}

function formatFileSize(bytes) {
  if (!bytes) return '-'
  const mb = bytes / 1024 / 1024
  if (mb < 1) return `${(bytes / 1024).toFixed(1)} KB`
  return `${mb.toFixed(1)} MB`
}

function formatDuration(seconds) {
  if (!seconds) return '-'
  const min = Math.floor(seconds / 60)
  const sec = seconds % 60
  return `${min}:${sec.toString().padStart(2, '0')}`
}

function formatDate(dateStr) {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleDateString('zh-CN')
}

const filteredAssets = ref([])
filteredAssets.value = assets.value
</script>

<template>
  <div class="assets-page">
    <header class="page-header">
      <div class="header-top">
        <div class="title-group">
          <span class="eyebrow mono">ASSET LIBRARY</span>
          <h1>素材库</h1>
        </div>
        <div class="header-actions">
          <button class="primary-button">
            <span>↑</span> 上传素材
          </button>
        </div>
      </div>
      <p class="header-desc">统一管理图片、视频、音频与文档素材</p>
    </header>

    <section class="filter-bar">
      <div class="filter-group">
        <label>类型</label>
        <select v-model="filter.type" class="filter-select">
          <option value="all">全部类型</option>
          <option value="IMAGE">图片</option>
          <option value="VIDEO">视频</option>
          <option value="AUDIO">音频</option>
          <option value="DOCUMENT">文档</option>
        </select>
      </div>
      <div class="filter-group">
        <label>分类</label>
        <input v-model="filter.category" type="text" class="filter-input" placeholder="输入分类筛选" />
      </div>
    </section>

    <Loading v-if="loading" text="加载素材列表..." />

    <Empty
      v-else-if="assets.length === 0"
      icon="□"
      title="暂无素材"
      description="上传第一个素材，开始构建内容资产库"
      action-text="上传素材"
    />

    <div v-else class="assets-grid">
      <div v-for="asset in assets" :key="asset.id" class="asset-card">
        <div class="asset-preview">
          <div v-if="asset.type === 'IMAGE'" class="preview-image">
            <div class="placeholder-icon">🖼️</div>
          </div>
          <div v-else-if="asset.type === 'VIDEO'" class="preview-video">
            <div class="placeholder-icon">▶️</div>
            <span class="duration-badge">{{ formatDuration(asset.duration) }}</span>
          </div>
          <div v-else-if="asset.type === 'AUDIO'" class="preview-audio">
            <div class="placeholder-icon">🎵</div>
            <span class="duration-badge">{{ formatDuration(asset.duration) }}</span>
          </div>
          <div v-else class="preview-document">
            <div class="placeholder-icon">📄</div>
          </div>
          <span class="type-badge">{{ typeMap[asset.type] }}</span>
        </div>

        <div class="asset-info">
          <h3 class="asset-name">{{ asset.name }}</h3>
          <span class="asset-code mono">{{ asset.code }}</span>

          <div class="asset-meta">
            <div class="meta-row">
              <span class="label">分类</span>
              <span class="value">{{ asset.category }}</span>
            </div>
            <div class="meta-row">
              <span class="label">大小</span>
              <span class="value">{{ formatFileSize(asset.fileSize) }}</span>
            </div>
            <div v-if="asset.width" class="meta-row">
              <span class="label">尺寸</span>
              <span class="value">{{ asset.width }}×{{ asset.height }}</span>
            </div>
            <div class="meta-row">
              <span class="label">使用</span>
              <span class="value">{{ asset.usageCount }}次</span>
            </div>
            <div class="meta-row">
              <span class="label">上传</span>
              <span class="value">{{ formatDate(asset.createdAt) }}</span>
            </div>
          </div>

          <div class="asset-actions">
            <button class="link-button">预览</button>
            <button class="link-button">编辑</button>
            <button class="link-button danger">删除</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.assets-page {
  padding: 24px;
  max-width: 1400px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 32px;
}

.header-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}

.title-group h1 {
  font-size: 28px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.95);
  margin: 8px 0 0 0;
}

.eyebrow {
  font-size: 11px;
  color: rgba(139, 92, 246, 0.8);
  letter-spacing: 0.1em;
}

.header-desc {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.5);
  margin: 0;
}

.filter-bar {
  display: flex;
  gap: 16px;
  margin-bottom: 24px;
  padding: 16px;
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(139, 92, 246, 0.1);
  border-radius: 8px;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.filter-group label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.6);
  font-weight: 500;
}

.filter-select,
.filter-input {
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  color: rgba(255, 255, 255, 0.9);
  font-size: 13px;
}

.assets-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 20px;
}

.asset-card {
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(139, 92, 246, 0.2);
  border-radius: 12px;
  overflow: hidden;
  transition: all 0.2s;
}

.asset-card:hover {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(139, 92, 246, 0.4);
}

.asset-preview {
  position: relative;
  width: 100%;
  height: 180px;
  background: rgba(0, 0, 0, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
}

.placeholder-icon {
  font-size: 48px;
  opacity: 0.5;
}

.type-badge {
  position: absolute;
  top: 12px;
  right: 12px;
  padding: 4px 10px;
  background: rgba(0, 0, 0, 0.7);
  border-radius: 4px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.9);
  font-weight: 500;
}

.duration-badge {
  position: absolute;
  bottom: 12px;
  right: 12px;
  padding: 4px 8px;
  background: rgba(0, 0, 0, 0.7);
  border-radius: 4px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.9);
  font-family: monospace;
}

.asset-info {
  padding: 16px;
}

.asset-name {
  font-size: 15px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.95);
  margin: 0 0 4px 0;
  line-height: 1.4;
}

.asset-code {
  font-size: 11px;
  color: rgba(139, 92, 246, 0.7);
}

.asset-meta {
  margin: 16px 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.meta-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}

.meta-row .label {
  color: rgba(255, 255, 255, 0.5);
}

.meta-row .value {
  color: rgba(255, 255, 255, 0.8);
}

.asset-actions {
  display: flex;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

.link-button {
  padding: 6px 12px;
  background: transparent;
  border: 1px solid rgba(139, 92, 246, 0.3);
  border-radius: 6px;
  color: rgb(139, 92, 246);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.link-button:hover {
  background: rgba(139, 92, 246, 0.1);
  border-color: rgb(139, 92, 246);
}

.link-button.danger {
  color: rgb(239, 68, 68);
  border-color: rgba(239, 68, 68, 0.3);
}

.link-button.danger:hover {
  background: rgba(239, 68, 68, 0.1);
  border-color: rgb(239, 68, 68);
}
</style>
