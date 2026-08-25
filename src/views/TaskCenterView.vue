<script setup>
import { ref, reactive, onMounted } from 'vue'
import { isDemoMode } from '../utils/config'
import Loading from '../components/Loading.vue'
import Empty from '../components/Empty.vue'
import StatusBadge from '../components/StatusBadge.vue'

const tasks = ref([])
const loading = ref(true)
const filter = reactive({
  status: 'all',
  type: 'all'
})

const statusMap = {
  PENDING: { label: '待处理', color: 'info' },
  QUEUED: { label: '队列中', color: 'info' },
  PROCESSING: { label: '处理中', color: 'warning' },
  COMPLETED: { label: '已完成', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
  CANCELLED: { label: '已取消', color: 'error' }
}

const typeMap = {
  TEXT_GENERATION: '文本生成',
  IMAGE_GENERATION: '图片生成',
  VIDEO_GENERATION: '视频生成',
  BATCH_GENERATION: '批量生成'
}

onMounted(async () => {
  await loadTasks()
})

async function loadTasks() {
  loading.value = true
  try {
    // Mock数据
    if (isDemoMode()) {
      await new Promise(resolve => setTimeout(resolve, 500))
      tasks.value = [
        {
          id: 'task_1',
          code: 'T1724580000001',
          type: 'IMAGE_GENERATION',
          modelAlias: 'IMAGE_PRIMARY',
          status: 'COMPLETED',
          progress: 100,
          estimatedCost: 0.05,
          actualCost: 0.05,
          createdAt: '2024-08-25T14:30:00Z',
          completedAt: '2024-08-25T14:30:45Z'
        },
        {
          id: 'task_2',
          code: 'T1724580000002',
          type: 'TEXT_GENERATION',
          modelAlias: 'TEXT_FAST',
          status: 'PROCESSING',
          progress: 60,
          estimatedCost: 0.02,
          createdAt: '2024-08-25T15:00:00Z'
        },
        {
          id: 'task_3',
          code: 'T1724580000003',
          type: 'VIDEO_GENERATION',
          modelAlias: 'VIDEO_PRIMARY',
          status: 'PENDING',
          progress: 0,
          estimatedCost: 5.00,
          createdAt: '2024-08-25T15:10:00Z'
        }
      ]
    }
  } catch (error) {
    console.error('加载任务失败:', error)
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
  const now = new Date()
  const diff = now - date
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}小时前`
  return date.toLocaleDateString('zh-CN')
}

const filteredTasks = ref([])
filteredTasks.value = tasks.value
</script>

<template>
  <div class="task-center">
    <header class="page-header">
      <div class="header-top">
        <div class="title-group">
          <span class="eyebrow mono">TASK CENTER</span>
          <h1>任务中心</h1>
        </div>
        <div class="header-actions">
          <button class="secondary-button" @click="loadTasks">
            <span>↻</span> 刷新
          </button>
        </div>
      </div>
      <p class="header-desc">所有AI生成任务的统一管理和监控</p>
    </header>

    <section class="filter-bar">
      <div class="filter-group">
        <label>状态</label>
        <select v-model="filter.status" class="filter-select">
          <option value="all">全部状态</option>
          <option value="PENDING">待处理</option>
          <option value="PROCESSING">处理中</option>
          <option value="COMPLETED">已完成</option>
          <option value="FAILED">失败</option>
        </select>
      </div>
      <div class="filter-group">
        <label>类型</label>
        <select v-model="filter.type" class="filter-select">
          <option value="all">全部类型</option>
          <option value="TEXT_GENERATION">文本生成</option>
          <option value="IMAGE_GENERATION">图片生成</option>
          <option value="VIDEO_GENERATION">视频生成</option>
          <option value="BATCH_GENERATION">批量生成</option>
        </select>
      </div>
    </section>

    <Loading v-if="loading" text="加载任务列表..." />

    <Empty
      v-else-if="tasks.length === 0"
      icon="◌"
      title="暂无任务"
      description="还没有创建任何AI生成任务"
    />

    <div v-else class="task-list">
      <div v-for="task in tasks" :key="task.id" class="task-card">
        <div class="task-header">
          <div class="task-meta">
            <span class="task-code mono">{{ task.code }}</span>
            <span class="task-type">{{ typeMap[task.type] }}</span>
          </div>
          <StatusBadge :status="getStatusConfig(task.status).color" :text="getStatusConfig(task.status).label" />
        </div>

        <div class="task-body">
          <div class="task-info-row">
            <span class="label">模型</span>
            <span class="value mono">{{ task.modelAlias }}</span>
          </div>
          <div class="task-info-row">
            <span class="label">预估成本</span>
            <span class="value">{{ formatCost(task.estimatedCost) }}</span>
          </div>
          <div v-if="task.actualCost" class="task-info-row">
            <span class="label">实际成本</span>
            <span class="value">{{ formatCost(task.actualCost) }}</span>
          </div>
          <div class="task-info-row">
            <span class="label">创建时间</span>
            <span class="value">{{ formatDate(task.createdAt) }}</span>
          </div>
        </div>

        <div v-if="task.status === 'PROCESSING'" class="task-progress">
          <div class="progress-bar">
            <div class="progress-fill" :style="{ width: task.progress + '%' }" />
          </div>
          <span class="progress-text">{{ task.progress }}%</span>
        </div>

        <div class="task-actions">
          <button v-if="task.status === 'COMPLETED'" class="link-button">查看结果</button>
          <button v-if="task.status === 'FAILED'" class="link-button">重试</button>
          <button v-if="task.status === 'PENDING' || task.status === 'QUEUED'" class="link-button danger">取消</button>
        </div>
      </div>
    </div>

    <footer class="page-footer">
      <p class="demo-notice">
        <span class="status-pulse offline" />
        演示模式 · 任务处理需要后端服务运行
      </p>
    </footer>
  </div>
</template>

<style scoped>
.task-center {
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

.header-actions {
  display: flex;
  gap: 12px;
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

.task-list {
  display: grid;
  gap: 16px;
}

.task-card {
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(139, 92, 246, 0.2);
  border-radius: 12px;
  padding: 20px;
  transition: all 0.2s;
}

.task-card:hover {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(139, 92, 246, 0.4);
}

.task-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.task-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.task-code {
  font-size: 13px;
  color: rgba(139, 92, 246, 0.9);
}

.task-type {
  font-size: 16px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.9);
}

.task-body {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.task-info-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
}

.task-info-row .label {
  color: rgba(255, 255, 255, 0.5);
}

.task-info-row .value {
  color: rgba(255, 255, 255, 0.9);
}

.task-progress {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.progress-bar {
  flex: 1;
  height: 6px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, rgb(139, 92, 246), rgb(168, 85, 247));
  transition: width 0.3s;
}

.progress-text {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
  font-weight: 500;
  min-width: 40px;
  text-align: right;
}

.task-actions {
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

.page-footer {
  margin-top: 32px;
  padding-top: 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
  text-align: center;
}

.demo-notice {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.5);
  margin: 0;
}

.status-pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgb(156, 163, 175);
}

.status-pulse.offline {
  animation: pulse-gray 2s ease-in-out infinite;
}

@keyframes pulse-gray {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
</style>
