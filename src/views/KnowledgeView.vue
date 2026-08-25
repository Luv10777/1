<script setup>
import { ref, reactive, onMounted } from 'vue'
import { isDemoMode } from '../utils/config'
import Loading from '../components/Loading.vue'
import Empty from '../components/Empty.vue'
import StatusBadge from '../components/StatusBadge.vue'

const knowledge = ref([])
const loading = ref(true)
const filter = reactive({
  type: 'all',
  status: 'all'
})

const typeMap = {
  FILE: '文件',
  TEXT: '文本',
  URL: '链接',
  STRUCTURED: '结构化'
}

const statusMap = {
  DRAFT: { label: '草稿', color: 'info' },
  PUBLISHED: { label: '已发布', color: 'success' },
  EXPIRED: { label: '已过期', color: 'error' }
}

onMounted(async () => {
  await loadKnowledge()
})

async function loadKnowledge() {
  loading.value = true
  try {
    if (isDemoMode()) {
      await new Promise(resolve => setTimeout(resolve, 500))
      knowledge.value = [
        {
          id: 'knowledge_1',
          code: 'K1724580000001',
          title: '门店服务流程标准',
          type: 'STRUCTURED',
          status: 'PUBLISHED',
          verified: true,
          chunkCount: 15,
          createdAt: '2024-08-20T10:00:00Z'
        },
        {
          id: 'knowledge_2',
          code: 'K1724580000002',
          title: '产品知识手册',
          type: 'FILE',
          status: 'PUBLISHED',
          verified: true,
          chunkCount: 42,
          createdAt: '2024-08-21T14:00:00Z'
        },
        {
          id: 'knowledge_3',
          code: 'K1724580000003',
          title: '常见问题解答',
          type: 'TEXT',
          status: 'DRAFT',
          verified: false,
          chunkCount: 8,
          createdAt: '2024-08-24T09:00:00Z'
        }
      ]
    }
  } catch (error) {
    console.error('加载知识失败:', error)
  } finally {
    loading.value = false
  }
}

function getStatusConfig(status) {
  return statusMap[status] || { label: status, color: 'info' }
}

function formatDate(dateStr) {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleDateString('zh-CN')
}
</script>

<template>
  <div class="knowledge-page">
    <header class="page-header">
      <div class="header-top">
        <div class="title-group">
          <span class="eyebrow mono">KNOWLEDGE BASE</span>
          <h1>知识库</h1>
        </div>
        <div class="header-actions">
          <button class="primary-button">
            <span>+</span> 新建知识
          </button>
        </div>
      </div>
      <p class="header-desc">把门店经验整理为模型可以调用的知识单元</p>
    </header>

    <section class="filter-bar">
      <div class="filter-group">
        <label>类型</label>
        <select v-model="filter.type" class="filter-select">
          <option value="all">全部类型</option>
          <option value="FILE">文件</option>
          <option value="TEXT">文本</option>
          <option value="URL">链接</option>
          <option value="STRUCTURED">结构化</option>
        </select>
      </div>
      <div class="filter-group">
        <label>状态</label>
        <select v-model="filter.status" class="filter-select">
          <option value="all">全部状态</option>
          <option value="DRAFT">草稿</option>
          <option value="PUBLISHED">已发布</option>
        </select>
      </div>
    </section>

    <Loading v-if="loading" text="加载知识列表..." />

    <Empty
      v-else-if="knowledge.length === 0"
      icon="≡"
      title="暂无知识"
      description="添加第一条知识，让AI理解业务"
      action-text="新建知识"
    />

    <div v-else class="knowledge-list">
      <div v-for="item in knowledge" :key="item.id" class="knowledge-card">
        <div class="knowledge-header">
          <div class="knowledge-meta">
            <h3 class="knowledge-title">{{ item.title }}</h3>
            <span class="knowledge-code mono">{{ item.code }}</span>
          </div>
          <div class="knowledge-badges">
            <StatusBadge :status="getStatusConfig(item.status).color" :text="getStatusConfig(item.status).label" />
            <span v-if="item.verified" class="verified-badge">✓ 已验证</span>
          </div>
        </div>

        <div class="knowledge-info">
          <div class="info-item">
            <span class="label">类型</span>
            <span class="value">{{ typeMap[item.type] }}</span>
          </div>
          <div class="info-item">
            <span class="label">切片数</span>
            <span class="value">{{ item.chunkCount }}</span>
          </div>
          <div class="info-item">
            <span class="label">创建时间</span>
            <span class="value">{{ formatDate(item.createdAt) }}</span>
          </div>
        </div>

        <div class="knowledge-actions">
          <button class="link-button">查看</button>
          <button v-if="!item.verified" class="link-button">验证</button>
          <button class="link-button">编辑</button>
          <button class="link-button danger">删除</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.knowledge-page {
  padding: 24px;
  max-width: 1200px;
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

.knowledge-list {
  display: grid;
  gap: 16px;
}

.knowledge-card {
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(139, 92, 246, 0.2);
  border-radius: 12px;
  padding: 20px;
  transition: all 0.2s;
}

.knowledge-card:hover {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(139, 92, 246, 0.4);
}

.knowledge-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}

.knowledge-meta {
  flex: 1;
}

.knowledge-title {
  font-size: 16px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.95);
  margin: 0 0 4px 0;
}

.knowledge-code {
  font-size: 12px;
  color: rgba(139, 92, 246, 0.7);
}

.knowledge-badges {
  display: flex;
  align-items: center;
  gap: 8px;
}

.verified-badge {
  padding: 4px 10px;
  background: rgba(34, 197, 94, 0.1);
  border: 1px solid rgba(34, 197, 94, 0.3);
  border-radius: 4px;
  font-size: 11px;
  color: rgb(34, 197, 94);
  font-weight: 500;
}

.knowledge-info {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.info-item .label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
}

.info-item .value {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.9);
}

.knowledge-actions {
  display: flex;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

.link-button {
  padding: 6px 12px;
  background: transparent;
  border: 1px solid rgba(139, 92, 246, 0.3);
  border-radius: 6px;
  color: rgb(139, 92, 246);
  font-size: 13px;
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
