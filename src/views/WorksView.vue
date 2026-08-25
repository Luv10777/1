<script setup>
import { ref, reactive, onMounted } from 'vue'
import { isDemoMode } from '../utils/config'
import Loading from '../components/Loading.vue'
import Empty from '../components/Empty.vue'
import StatusBadge from '../components/StatusBadge.vue'

const works = ref([])
const loading = ref(true)
const filter = reactive({
  reviewStatus: 'all',
  type: 'all'
})

const typeMap = {
  IMAGE: '图片',
  VIDEO: '视频',
  TEXT: '文案',
  MIXED: '混合'
}

const statusMap = {
  DRAFT: { label: '草稿', color: 'info' },
  PENDING: { label: '待审核', color: 'warning' },
  APPROVED: { label: '已通过', color: 'success' },
  REJECTED: { label: '已驳回', color: 'error' },
  PUBLISHED: { label: '已发布', color: 'success' }
}

onMounted(async () => {
  await loadWorks()
})

async function loadWorks() {
  loading.value = true
  try {
    if (isDemoMode()) {
      await new Promise(resolve => setTimeout(resolve, 500))
      works.value = [
        {
          id: 'work_1',
          code: 'W1724580000001',
          title: '秋季新品推广海报',
          type: 'IMAGE',
          reviewStatus: 'PUBLISHED',
          coverUrl: '/demo/work1.jpg',
          workflowId: 'poster_gen_v1',
          generationCost: 0.05,
          createdAt: '2024-08-20T10:00:00Z',
          publishedAt: '2024-08-20T15:00:00Z'
        },
        {
          id: 'work_2',
          code: 'W1724580000002',
          title: '门店宣传短视频',
          type: 'VIDEO',
          reviewStatus: 'APPROVED',
          coverUrl: '/demo/work2.jpg',
          workflowId: 'video_gen_v1',
          generationCost: 2.50,
          createdAt: '2024-08-22T14:00:00Z'
        },
        {
          id: 'work_3',
          code: 'W1724580000003',
          title: '小红书种草文案',
          type: 'TEXT',
          reviewStatus: 'PENDING',
          workflowId: 'text_gen_v1',
          generationCost: 0.02,
          createdAt: '2024-08-24T09:00:00Z'
        }
      ]
    }
  } catch (error) {
    console.error('加载作品失败:', error)
  } finally {
    loading.value = false
  }
}

function getStatusConfig(status) {
  return statusMap[status] || { label: status, color: 'info' }
}

function formatCost(cost) {
  return cost ? `¥${cost.toFixed(2)}` : '-'
}

function formatDate(dateStr) {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleDateString('zh-CN')
}
</script>

<template>
  <div class="works-page">
    <header class="page-header">
      <div class="header-top">
        <div class="title-group">
          <span class="eyebrow mono">WORKS LIBRARY</span>
          <h1>作品库</h1>
        </div>
      </div>
      <p class="header-desc">查看已生成、审核中和已发布的内容作品</p>
    </header>

    <section class="filter-bar">
      <div class="filter-group">
        <label>审核状态</label>
        <select v-model="filter.reviewStatus" class="filter-select">
          <option value="all">全部状态</option>
          <option value="DRAFT">草稿</option>
          <option value="PENDING">待审核</option>
          <option value="APPROVED">已通过</option>
          <option value="REJECTED">已驳回</option>
          <option value="PUBLISHED">已发布</option>
        </select>
      </div>
      <div class="filter-group">
        <label>作品类型</label>
        <select v-model="filter.type" class="filter-select">
          <option value="all">全部类型</option>
          <option value="IMAGE">图片</option>
          <option value="VIDEO">视频</option>
          <option value="TEXT">文案</option>
          <option value="MIXED">混合</option>
        </select>
      </div>
    </section>

    <Loading v-if="loading" text="加载作品列表..." />

    <Empty
      v-else-if="works.length === 0"
      icon="⌁"
      title="暂无作品"
      description="通过创意工作台生成第一个作品"
    />

    <div v-else class="works-grid">
      <div v-for="work in works" :key="work.id" class="work-card">
        <div class="work-preview">
          <div class="placeholder-preview">
            <span class="preview-icon">{{ typeMap[work.type] === '图片' ? '🖼️' : typeMap[work.type] === '视频' ? '🎬' : '📝' }}</span>
          </div>
          <span class="type-badge">{{ typeMap[work.type] }}</span>
        </div>

        <div class="work-info">
          <div class="work-header">
            <div class="work-meta">
              <h3 class="work-title">{{ work.title }}</h3>
              <span class="work-code mono">{{ work.code }}</span>
            </div>
            <StatusBadge :status="getStatusConfig(work.reviewStatus).color" :text="getStatusConfig(work.reviewStatus).label" />
          </div>

          <div class="work-details">
            <div class="detail-item">
              <span class="label">工作流</span>
              <span class="value mono">{{ work.workflowId }}</span>
            </div>
            <div class="detail-item">
              <span class="label">成本</span>
              <span class="value">{{ formatCost(work.generationCost) }}</span>
            </div>
            <div class="detail-item">
              <span class="label">创建时间</span>
              <span class="value">{{ formatDate(work.createdAt) }}</span>
            </div>
            <div v-if="work.publishedAt" class="detail-item">
              <span class="label">发布时间</span>
              <span class="value">{{ formatDate(work.publishedAt) }}</span>
            </div>
          </div>

          <div class="work-actions">
            <button class="link-button">预览</button>
            <button v-if="work.reviewStatus === 'PENDING'" class="link-button">审核</button>
            <button v-if="work.reviewStatus === 'APPROVED'" class="link-button success">发布</button>
            <button class="link-button danger">删除</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.works-page {
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

.filter-select {
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  color: rgba(255, 255, 255, 0.9);
  font-size: 13px;
  cursor: pointer;
}

.works-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 20px;
}

.work-card {
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(139, 92, 246, 0.2);
  border-radius: 12px;
  overflow: hidden;
  transition: all 0.2s;
}

.work-card:hover {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(139, 92, 246, 0.4);
}

.work-preview {
  position: relative;
  width: 100%;
  height: 180px;
  background: rgba(0, 0, 0, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
}

.placeholder-preview {
  display: flex;
  align-items: center;
  justify-content: center;
}

.preview-icon {
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

.work-info {
  padding: 16px;
}

.work-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}

.work-meta {
  flex: 1;
}

.work-title {
  font-size: 15px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.95);
  margin: 0 0 4px 0;
  line-height: 1.4;
}

.work-code {
  font-size: 11px;
  color: rgba(139, 92, 246, 0.7);
}

.work-details {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.detail-item .label {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.5);
}

.detail-item .value {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.9);
}

.work-actions {
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

.link-button.success {
  color: rgb(34, 197, 94);
  border-color: rgba(34, 197, 94, 0.3);
}

.link-button.success:hover {
  background: rgba(34, 197, 94, 0.1);
  border-color: rgb(34, 197, 94);
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
