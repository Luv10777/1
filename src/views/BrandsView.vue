<script setup>
import { ref, reactive, onMounted } from 'vue'
import { brandService } from '../services/brand'
import Loading from '../components/Loading.vue'
import Empty from '../components/Empty.vue'

const brands = ref([])
const loading = ref(true)
const showCreateModal = ref(false)
const editingBrand = ref(null)

const form = reactive({
  name: '',
  positioning: '',
  targetAudience: '',
  languageStyle: '',
  primaryColor: '#2D5016',
  logoAssets: '',
  platformStyles: ''
})

onMounted(async () => {
  await loadBrands()
})

async function loadBrands() {
  loading.value = true
  try {
    const merchantId = 'merchant_1' // TODO: 从当前上下文获取
    const response = await brandService.list(merchantId, { page: 0, size: 20 })
    if (response.success) {
      brands.value = response.data.items || []
    }
  } catch (error) {
    console.error('加载品牌失败:', error)
  } finally {
    loading.value = false
  }
}

function openCreateModal() {
  editingBrand.value = null
  resetForm()
  showCreateModal.value = true
}

function openEditModal(brand) {
  editingBrand.value = brand
  Object.assign(form, {
    name: brand.name,
    positioning: brand.positioning || '',
    targetAudience: brand.targetAudience || '',
    languageStyle: brand.languageStyle || '',
    primaryColor: brand.primaryColor || '#2D5016',
    logoAssets: brand.logoAssets || '',
    platformStyles: brand.platformStyles || ''
  })
  showCreateModal.value = true
}

function resetForm() {
  form.name = ''
  form.positioning = ''
  form.targetAudience = ''
  form.languageStyle = ''
  form.primaryColor = '#2D5016'
  form.logoAssets = ''
  form.platformStyles = ''
}

async function handleSubmit() {
  try {
    const merchantId = 'merchant_1'
    if (editingBrand.value) {
      await brandService.update(editingBrand.value.id, form)
    } else {
      await brandService.create(merchantId, form)
    }
    showCreateModal.value = false
    await loadBrands()
  } catch (error) {
    console.error('保存品牌失败:', error)
    alert(error.message || '保存失败')
  }
}

async function handleDelete(brand) {
  if (!confirm(`确定要删除品牌"${brand.name}"吗？`)) return
  try {
    await brandService.delete(brand.id)
    await loadBrands()
  } catch (error) {
    console.error('删除品牌失败:', error)
    alert(error.message || '删除失败')
  }
}

function getPlatformStyles(platformStyles) {
  if (!platformStyles) return []
  try {
    const data = typeof platformStyles === 'string' ? JSON.parse(platformStyles) : platformStyles
    return Object.entries(data).map(([key, value]) => ({ platform: key, style: value }))
  } catch {
    return []
  }
}
</script>

<template>
  <div class="brands-page">
    <header class="page-header">
      <div class="header-top">
        <div class="title-group">
          <span class="eyebrow mono">BRAND LIBRARY</span>
          <h1>品牌库</h1>
        </div>
        <div class="header-actions">
          <button class="primary-button" @click="openCreateModal">
            <span>+</span> 新建品牌
          </button>
        </div>
      </div>
      <p class="header-desc">沉淀品牌定位、语言风格和视觉识别资产</p>
    </header>

    <Loading v-if="loading" text="加载品牌列表..." />

    <Empty
      v-else-if="brands.length === 0"
      icon="◈"
      title="暂无品牌"
      description="创建第一个品牌规范，统一内容生成的风格"
      action-text="新建品牌"
      @action="openCreateModal"
    />

    <div v-else class="brands-grid">
      <div v-for="brand in brands" :key="brand.id" class="brand-card">
        <div class="brand-header">
          <div class="brand-color" :style="{ background: brand.primaryColor }" />
          <div class="brand-info">
            <h3 class="brand-name">{{ brand.name }}</h3>
            <span class="brand-code mono">{{ brand.code }}</span>
          </div>
        </div>

        <div v-if="brand.positioning" class="brand-section">
          <label>品牌定位</label>
          <p>{{ brand.positioning }}</p>
        </div>

        <div v-if="brand.targetAudience" class="brand-section">
          <label>目标人群</label>
          <p>{{ brand.targetAudience }}</p>
        </div>

        <div v-if="brand.languageStyle" class="brand-section">
          <label>语言风格</label>
          <p>{{ brand.languageStyle }}</p>
        </div>

        <div v-if="getPlatformStyles(brand.platformStyles).length > 0" class="brand-section">
          <label>平台风格</label>
          <div class="platform-tags">
            <span v-for="item in getPlatformStyles(brand.platformStyles)" :key="item.platform" class="platform-tag">
              {{ item.platform }}
            </span>
          </div>
        </div>

        <div class="brand-actions">
          <button class="link-button" @click="openEditModal(brand)">编辑</button>
          <button class="link-button danger" @click="handleDelete(brand)">删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑模态框 -->
    <div v-if="showCreateModal" class="modal-overlay" @click.self="showCreateModal = false">
      <div class="modal-content">
        <div class="modal-header">
          <h2>{{ editingBrand ? '编辑品牌' : '新建品牌' }}</h2>
          <button class="close-button" @click="showCreateModal = false">×</button>
        </div>

        <form class="modal-form" @submit.prevent="handleSubmit">
          <div class="form-group">
            <label>品牌名称 <span class="required">*</span></label>
            <input v-model="form.name" type="text" placeholder="如：青岚茶事" required />
          </div>

          <div class="form-group">
            <label>品牌定位</label>
            <textarea v-model="form.positioning" rows="3" placeholder="新中式茶饮品牌，传承东方美学，融入现代生活方式" />
          </div>

          <div class="form-group">
            <label>目标人群</label>
            <textarea v-model="form.targetAudience" rows="2" placeholder="25-40岁都市白领，追求品质生活，注重健康养生" />
          </div>

          <div class="form-group">
            <label>语言风格</label>
            <textarea v-model="form.languageStyle" rows="2" placeholder="温和、雅致、有文化底蕴。避免过于口语化" />
          </div>

          <div class="form-group">
            <label>主色调</label>
            <div class="color-input-group">
              <input v-model="form.primaryColor" type="color" class="color-picker" />
              <input v-model="form.primaryColor" type="text" placeholder="#2D5016" class="color-text" />
            </div>
          </div>

          <div class="modal-actions">
            <button type="button" class="secondary-button" @click="showCreateModal = false">取消</button>
            <button type="submit" class="primary-button">{{ editingBrand ? '保存' : '创建' }}</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<style scoped>
.brands-page {
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

.brands-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 20px;
}

.brand-card {
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(139, 92, 246, 0.2);
  border-radius: 12px;
  padding: 20px;
  transition: all 0.2s;
}

.brand-card:hover {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(139, 92, 246, 0.4);
}

.brand-header {
  display: flex;
  gap: 16px;
  margin-bottom: 20px;
}

.brand-color {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.brand-info {
  flex: 1;
}

.brand-name {
  font-size: 18px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.95);
  margin: 0 0 4px 0;
}

.brand-code {
  font-size: 12px;
  color: rgba(139, 92, 246, 0.7);
}

.brand-section {
  margin-bottom: 16px;
}

.brand-section label {
  display: block;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
  margin-bottom: 6px;
  font-weight: 500;
}

.brand-section p {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.8);
  line-height: 1.6;
  margin: 0;
}

.platform-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.platform-tag {
  padding: 4px 10px;
  background: rgba(139, 92, 246, 0.1);
  border: 1px solid rgba(139, 92, 246, 0.3);
  border-radius: 4px;
  font-size: 12px;
  color: rgb(139, 92, 246);
}

.brand-actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
  padding-top: 16px;
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

/* Modal styles */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.modal-content {
  background: rgb(17, 24, 39);
  border: 1px solid rgba(139, 92, 246, 0.3);
  border-radius: 12px;
  max-width: 600px;
  width: 100%;
  max-height: 90vh;
  overflow-y: auto;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.modal-header h2 {
  font-size: 20px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.95);
  margin: 0;
}

.close-button {
  background: none;
  border: none;
  color: rgba(255, 255, 255, 0.5);
  font-size: 28px;
  cursor: pointer;
  padding: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  transition: all 0.2s;
}

.close-button:hover {
  background: rgba(255, 255, 255, 0.05);
  color: rgba(255, 255, 255, 0.9);
}

.modal-form {
  padding: 24px;
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.7);
  margin-bottom: 8px;
  font-weight: 500;
}

.required {
  color: rgb(239, 68, 68);
}

.form-group input[type="text"],
.form-group textarea {
  width: 100%;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  color: rgba(255, 255, 255, 0.9);
  font-size: 14px;
  font-family: inherit;
  transition: all 0.2s;
}

.form-group input:focus,
.form-group textarea:focus {
  outline: none;
  border-color: rgb(139, 92, 246);
  background: rgba(255, 255, 255, 0.08);
}

.color-input-group {
  display: flex;
  gap: 12px;
}

.color-picker {
  width: 60px;
  height: 40px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  cursor: pointer;
}

.color-text {
  flex: 1;
}

.modal-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
}
</style>
