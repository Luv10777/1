<template>
  <div class="video-projects-view">
    <div class="view-header">
      <h1>视频项目</h1>
      <button @click="showCreateDialog = true" class="btn-primary">
        创建新项目
      </button>
    </div>

    <div v-if="loading" class="loading-state">
      <Loading />
    </div>

    <div v-else-if="projects.length === 0" class="empty-state">
      <Empty message="暂无视频项目" />
    </div>

    <div v-else class="projects-grid">
      <div
        v-for="project in projects"
        :key="project.id"
        class="project-card"
        @click="viewProject(project.id)"
      >
        <div class="project-status">
          <StatusBadge :status="project.status" />
        </div>
        <h3>{{ project.projectName }}</h3>
        <p class="project-code">{{ project.projectCode }}</p>
        <div class="project-meta">
          <span>进度: {{ project.progress || 0 }}%</span>
          <span>{{ formatDate(project.createdAt) }}</span>
        </div>
      </div>
    </div>

    <!-- 创建项目对话框 -->
    <div v-if="showCreateDialog" class="modal-overlay" @click="showCreateDialog = false">
      <div class="modal-content" @click.stop>
        <h2>创建视频项目</h2>
        <form @submit.prevent="createProject">
          <div class="form-group">
            <label>项目名称</label>
            <input
              v-model="newProject.projectName"
              type="text"
              required
              placeholder="输入项目名称"
            />
          </div>
          <div class="form-group">
            <label>项目描述</label>
            <textarea
              v-model="newProject.userInput"
              rows="4"
              required
              placeholder="描述您想要创建的视频内容"
            ></textarea>
          </div>
          <div class="form-actions">
            <button type="button" @click="showCreateDialog = false" class="btn-secondary">
              取消
            </button>
            <button type="submit" class="btn-primary" :disabled="creating">
              {{ creating ? '创建中...' : '创建' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import Loading from '../components/Loading.vue'
import Empty from '../components/Empty.vue'
import StatusBadge from '../components/StatusBadge.vue'

const router = useRouter()
const loading = ref(true)
const projects = ref([])
const showCreateDialog = ref(false)
const creating = ref(false)
const newProject = ref({
  projectName: '',
  userInput: '',
  merchantId: 1
})

const loadProjects = async () => {
  try {
    loading.value = true
    const response = await fetch('http://localhost:8080/api/v1/workflow/projects?merchantId=1')
    const data = await response.json()
    if (data.code === 200) {
      projects.value = data.data
    }
  } catch (error) {
    console.error('加载项目失败:', error)
  } finally {
    loading.value = false
  }
}

const createProject = async () => {
  try {
    creating.value = true
    const response = await fetch('http://localhost:8080/api/v1/workflow/projects', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(newProject.value)
    })
    const data = await response.json()
    if (data.code === 200) {
      showCreateDialog.value = false
      newProject.value = { projectName: '', userInput: '', merchantId: 1 }
      loadProjects()
    }
  } catch (error) {
    console.error('创建项目失败:', error)
  } finally {
    creating.value = false
  }
}

const viewProject = (id) => {
  router.push(`/video-projects/${id}`)
}

const formatDate = (dateStr) => {
  if (!dateStr) return ''
  return new Date(dateStr).toLocaleDateString('zh-CN')
}

onMounted(() => {
  loadProjects()
})
</script>

<style scoped>
.video-projects-view {
  padding: 24px;
}

.view-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.view-header h1 {
  font-size: 24px;
  font-weight: 600;
  margin: 0;
}

.projects-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 20px;
}

.project-card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 20px;
  cursor: pointer;
  transition: all 0.2s;
}

.project-card:hover {
  border-color: #3b82f6;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.project-status {
  margin-bottom: 12px;
}

.project-card h3 {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 8px 0;
}

.project-code {
  color: #6b7280;
  font-size: 14px;
  margin: 0 0 12px 0;
}

.project-meta {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
  color: #6b7280;
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: white;
  border-radius: 8px;
  padding: 24px;
  width: 500px;
  max-width: 90%;
}

.modal-content h2 {
  margin: 0 0 20px 0;
  font-size: 20px;
}

.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  margin-bottom: 8px;
  font-weight: 500;
}

.form-group input,
.form-group textarea {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  font-size: 14px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}

.btn-primary {
  background: #3b82f6;
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}

.btn-primary:hover {
  background: #2563eb;
}

.btn-primary:disabled {
  background: #9ca3af;
  cursor: not-allowed;
}

.btn-secondary {
  background: white;
  color: #374151;
  border: 1px solid #e5e7eb;
  padding: 8px 16px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}

.btn-secondary:hover {
  background: #f9fafb;
}

.loading-state,
.empty-state {
  padding: 40px;
  text-align: center;
}
</style>
