<template>
  <div class="video-project-detail">
    <div v-if="loading" class="loading-state">
      <Loading />
    </div>

    <div v-else-if="!project" class="error-state">
      <ErrorState message="项目不存在" />
    </div>

    <div v-else class="project-container">
      <!-- 项目头部 -->
      <div class="project-header">
        <div class="header-left">
          <button @click="$router.back()" class="btn-back">← 返回</button>
          <div>
            <h1>{{ project.projectName }}</h1>
            <p class="project-code">{{ project.projectCode }}</p>
          </div>
        </div>
        <div class="header-right">
          <StatusBadge :status="project.status" />
          <button
            v-if="project.status === 'DRAFT'"
            @click="startWorkflow"
            class="btn-primary"
            :disabled="starting"
          >
            {{ starting ? '启动中...' : '启动工作流' }}
          </button>
        </div>
      </div>

      <!-- 工作流进度 -->
      <div v-if="workflowRun" class="workflow-section">
        <h2>工作流进度</h2>
        <div class="progress-bar">
          <div
            class="progress-fill"
            :style="{ width: calculateProgress() + '%' }"
          ></div>
        </div>
        <div class="workflow-steps">
          <div
            v-for="(step, index) in workflowSteps"
            :key="step.name"
            class="step-item"
            :class="getStepStatus(step.name)"
          >
            <div class="step-number">{{ index + 1 }}</div>
            <div class="step-info">
              <div class="step-name">{{ step.label }}</div>
              <div class="step-status">{{ getStepStatusText(step.name) }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 人工审核区域 -->
      <div v-if="needsHumanReview" class="review-section">
        <h2>等待人工审核</h2>
        <div class="review-content">
          <p>当前步骤: {{ workflowRun.currentStepName }}</p>
          <div class="review-actions">
            <button @click="approveStep" class="btn-success">通过</button>
            <button @click="rejectStep" class="btn-danger">驳回</button>
          </div>
        </div>
      </div>

      <!-- 项目详情 -->
      <div class="project-info">
        <h2>项目信息</h2>
        <div class="info-grid">
          <div class="info-item">
            <label>创建时间</label>
            <span>{{ formatDate(project.createdAt) }}</span>
          </div>
          <div class="info-item">
            <label>更新时间</label>
            <span>{{ formatDate(project.updatedAt) }}</span>
          </div>
          <div class="info-item">
            <label>项目描述</label>
            <p>{{ project.userInput }}</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import Loading from '../components/Loading.vue'
import ErrorState from '../components/ErrorState.vue'
import StatusBadge from '../components/StatusBadge.vue'

const route = useRoute()
const loading = ref(true)
const starting = ref(false)
const project = ref(null)
const workflowRun = ref(null)

const workflowSteps = [
  { name: 'VALIDATE_INPUT', label: '验证输入' },
  { name: 'CAPTURE_MERCHANT_SNAPSHOT', label: '捕获商家快照' },
  { name: 'GENERATE_CREATIVE', label: '生成创意' },
  { name: 'HUMAN_APPROVE_CREATIVE', label: '审核创意' },
  { name: 'GENERATE_SCRIPT', label: '生成脚本' },
  { name: 'HUMAN_APPROVE_SCRIPT', label: '审核脚本' },
  { name: 'GENERATE_STORYBOARD', label: '生成分镜' },
  { name: 'HUMAN_APPROVE_STORYBOARD', label: '审核分镜' },
  { name: 'GENERATE_REFERENCE_IMAGES', label: '生成参考图' },
  { name: 'SELECT_REFERENCE_IMAGES', label: '选择参考图' },
  { name: 'GENERATE_SHOTS', label: '生成镜头视频' },
  { name: 'CHECK_SHOTS', label: '检查镜头' },
  { name: 'COMPOSE_VIDEO', label: '合成视频' },
  { name: 'FINAL_QA', label: '最终质检' },
  { name: 'HUMAN_REVIEW', label: '人工审核' },
  { name: 'PUBLISH_TO_LIBRARY', label: '发布到素材库' }
]

const needsHumanReview = computed(() => {
  return workflowRun.value?.pausedForHumanReview === true
})

const loadProject = async () => {
  try {
    // TODO: 实际 API 调用
    project.value = {
      id: route.params.id,
      projectName: '测试项目',
      projectCode: 'VP123456',
      status: 'DRAFT',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      userInput: '创建一个展示产品的短视频'
    }
  } catch (error) {
    console.error('加载项目失败:', error)
  } finally {
    loading.value = false
  }
}

const startWorkflow = async () => {
  try {
    starting.value = true
    const response = await fetch(
      `http://localhost:8080/api/v1/workflow/projects/${project.value.id}/start`,
      { method: 'POST' }
    )
    const data = await response.json()
    if (data.code === 200) {
      workflowRun.value = data.data
      project.value.status = 'RUNNING'
    }
  } catch (error) {
    console.error('启动工作流失败:', error)
  } finally {
    starting.value = false
  }
}

const approveStep = async () => {
  try {
    await fetch(`http://localhost:8080/api/v1/workflow/runs/${workflowRun.value.id}/human-review`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ approved: true, comment: '' })
    })
    // 重新加载
    loadProject()
  } catch (error) {
    console.error('审核失败:', error)
  }
}

const rejectStep = async () => {
  const comment = prompt('请输入驳回原因:')
  if (!comment) return

  try {
    await fetch(`http://localhost:8080/api/v1/workflow/runs/${workflowRun.value.id}/human-review`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ approved: false, comment })
    })
    loadProject()
  } catch (error) {
    console.error('审核失败:', error)
  }
}

const calculateProgress = () => {
  if (!workflowRun.value) return 0
  const currentIndex = workflowSteps.findIndex(
    s => s.name === workflowRun.value.currentStepName
  )
  return ((currentIndex + 1) / workflowSteps.length) * 100
}

const getStepStatus = (stepName) => {
  if (!workflowRun.value) return ''
  const currentIndex = workflowSteps.findIndex(
    s => s.name === workflowRun.value.currentStepName
  )
  const stepIndex = workflowSteps.findIndex(s => s.name === stepName)
  if (stepIndex < currentIndex) return 'completed'
  if (stepIndex === currentIndex) return 'current'
  return 'pending'
}

const getStepStatusText = (stepName) => {
  const status = getStepStatus(stepName)
  if (status === 'completed') return '已完成'
  if (status === 'current') return '进行中'
  return '待执行'
}

const formatDate = (dateStr) => {
  if (!dateStr) return ''
  return new Date(dateStr).toLocaleString('zh-CN')
}

onMounted(() => {
  loadProject()
})
</script>

<style scoped>
.video-project-detail {
  padding: 24px;
}

.project-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 32px;
}

.header-left {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.btn-back {
  background: none;
  border: none;
  font-size: 16px;
  cursor: pointer;
  color: #6b7280;
}

.project-header h1 {
  margin: 0 0 8px 0;
  font-size: 24px;
}

.project-code {
  color: #6b7280;
  font-size: 14px;
  margin: 0;
}

.header-right {
  display: flex;
  gap: 12px;
  align-items: center;
}

.workflow-section,
.review-section,
.project-info {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 24px;
  margin-bottom: 24px;
}

.workflow-section h2,
.review-section h2,
.project-info h2 {
  margin: 0 0 20px 0;
  font-size: 18px;
}

.progress-bar {
  background: #f3f4f6;
  height: 8px;
  border-radius: 4px;
  overflow: hidden;
  margin-bottom: 24px;
}

.progress-fill {
  background: #3b82f6;
  height: 100%;
  transition: width 0.3s;
}

.workflow-steps {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}

.step-item {
  display: flex;
  gap: 12px;
  padding: 12px;
  border-radius: 6px;
  border: 1px solid #e5e7eb;
}

.step-item.completed {
  background: #f0fdf4;
  border-color: #86efac;
}

.step-item.current {
  background: #eff6ff;
  border-color: #60a5fa;
}

.step-number {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #f3f4f6;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  flex-shrink: 0;
}

.step-item.completed .step-number {
  background: #22c55e;
  color: white;
}

.step-item.current .step-number {
  background: #3b82f6;
  color: white;
}

.step-info {
  flex: 1;
}

.step-name {
  font-weight: 500;
  margin-bottom: 4px;
}

.step-status {
  font-size: 12px;
  color: #6b7280;
}

.review-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.review-actions {
  display: flex;
  gap: 12px;
}

.btn-success {
  background: #22c55e;
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 4px;
  cursor: pointer;
}

.btn-danger {
  background: #ef4444;
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 4px;
  cursor: pointer;
}

.info-grid {
  display: grid;
  gap: 16px;
}

.info-item label {
  display: block;
  font-weight: 500;
  margin-bottom: 4px;
  color: #6b7280;
}

.btn-primary {
  background: #3b82f6;
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 4px;
  cursor: pointer;
}

.btn-primary:disabled {
  background: #9ca3af;
}
</style>
