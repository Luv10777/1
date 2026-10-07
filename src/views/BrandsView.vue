<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { brandApi } from '../services/brandApi'
import { auth } from '../stores/auth'

const brands = ref([])
const loading = ref(true)
const showModal = ref(false)
const editingBrand = ref(null)
const activeSection = ref('identity')
const notice = ref('')
const errors = ref({})
const saving = ref(false)
const uploading = reactive({ logo: false, miniProgramQr: false, wechatQr: false })
const logoInput = ref(null)
const miniQrInput = ref(null)
const wechatQrInput = ref(null)
const industryOpen = ref(false)
const industryOptions = ['餐饮 / 茶饮', '美妆 / 个护', '服饰 / 鞋包', '家居 / 生活方式', '教育 / 服务', '科技 / 互联网', '其他']
let sectionObserver
let scrollFrame

// 图片槽位保存的是 { assetId, url }：assetId 交给后端，url 只用于预览。
const blankForm = () => ({ name: '', industry: '', slogan: '', positioning: '', targetAudience: '', languageStyle: '', logo: null, viGuidelines: '', miniProgramQr: null, wechatQr: null, intro: '', website: '', history: '', coreTeam: '', brandStory: '', culture: '', primaryColor: '#0f766e' })
const form = reactive(blankForm())
const sections = [
  { id: 'identity', no: '01', label: '品牌识别', hint: '名称、赛道与 Slogan' },
  { id: 'voice', no: '02', label: '表达方式', hint: '定位、人群与语气' },
  { id: 'visual', no: '03', label: '视觉资产', hint: 'Logo、VI 与二维码' },
  { id: 'story', no: '04', label: '品牌叙事', hint: '简介、历程与故事' },
  { id: 'culture', no: '05', label: '团队与文化', hint: '核心团队与价值观' }
]
const completionFields = computed(() => [
  form.name,
  form.industry,
  form.slogan,
  form.positioning,
  form.targetAudience,
  form.languageStyle,
  form.logo,
  form.viGuidelines,
  form.miniProgramQr,
  form.wechatQr,
  form.intro,
  form.website,
  form.history,
  form.coreTeam,
  form.brandStory,
  form.culture
])
const completion = computed(() => {
  const filled = completionFields.value.filter(value => typeof value === 'string' ? value.trim().length > 0 : Boolean(value)).length
  return Math.round((filled / completionFields.value.length) * 100)
})

onMounted(async () => { document.querySelector('.page-scroll')?.classList.add('brands-scroll'); document.addEventListener('click', closeIndustryMenu); await loadBrands() })
watch(showModal, async (visible) => {
  if (!visible) { sectionObserver?.disconnect(); sectionObserver = null; if (scrollFrame) cancelAnimationFrame(scrollFrame); return }
  await nextTick()
  setupSectionObserver()
})
onBeforeUnmount(() => { sectionObserver?.disconnect(); if (scrollFrame) cancelAnimationFrame(scrollFrame); document.querySelector('.page-scroll')?.classList.remove('brands-scroll'); document.removeEventListener('click', closeIndustryMenu) })
async function loadBrands() { loading.value = true; try { brands.value = await brandApi.list() } catch (error) { notice.value = error.message || '加载品牌失败' } finally { loading.value = false } }
function resetForm() { Object.assign(form, blankForm()) }
function openCreateModal() { editingBrand.value = null; resetForm(); errors.value = {}; notice.value = ''; activeSection.value = 'identity'; showModal.value = true }
function openEditModal(brand) {
  editingBrand.value = brand
  const next = blankForm()
  for (const key of Object.keys(next)) if (brand[key] != null) next[key] = brand[key]
  Object.assign(form, next)
  errors.value = {}; notice.value = ''; activeSection.value = 'identity'; industryOpen.value = false; showModal.value = true
}
function closeIndustryMenu() { industryOpen.value = false }
function selectIndustry(option) { form.industry = option; errors.value = { ...errors.value, industry: '' }; industryOpen.value = false }
function handleIndustryKey(event) {
  if (event.key === 'Escape') { industryOpen.value = false; return }
  if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); industryOpen.value = !industryOpen.value }
  if (event.key === 'ArrowDown' && !industryOpen.value) { event.preventDefault(); industryOpen.value = true }
}
async function uploadImage(event, key) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || uploading[key]) return
  notice.value = ''
  uploading[key] = true
  try { form[key] = await brandApi.uploadImage(file) } catch (error) { notice.value = error.message || '图片上传失败，请重试。' } finally { uploading[key] = false }
}
function validate() {
  const next = {}
  if (!form.name.trim()) next.name = '请填写品牌名'
  if (!form.industry.trim()) next.industry = '请选择所属行业 / 赛道'
  if (form.website.trim() && !/^https?:\/\/\S+$/.test(form.website.trim())) next.website = '网址需以 http:// 或 https:// 开头'
  errors.value = next
  return Object.keys(next).length === 0
}
async function handleSubmit() {
  if (saving.value) return
  notice.value = ''
  if (!validate()) { notice.value = '还有信息需要修正，请检查标记项。'; return }
  if (Object.values(uploading).some(Boolean)) { notice.value = '图片还在上传，请稍候再保存。'; return }
  saving.value = true
  try {
    if (editingBrand.value) await brandApi.update(editingBrand.value.id, { ...form, version: editingBrand.value.version })
    else await brandApi.create(form)
    showModal.value = false
    await loadBrands()
  } catch (error) { notice.value = error.message || '保存失败' } finally { saving.value = false }
}
async function handleDelete(brand) { if (!confirm(`确定要删除品牌“${brand.name}”吗？`)) return; notice.value = ''; try { await brandApi.remove(brand.id); await loadBrands() } catch (error) { notice.value = error.message || '删除失败' } }
async function handleDefault(brand) { notice.value = ''; try { await brandApi.makeDefault(brand.id); await loadBrands() } catch (error) { notice.value = error.message || '设置默认品牌失败' } }
function scrollToSection(id) {
  activeSection.value = id
  const root = document.querySelector('.brand-form')
  const target = document.getElementById(id)
  if (!root || !target) return
  const start = root.scrollTop
  const destination = Math.max(0, target.offsetTop - 12)
  const distance = destination - start
  if (Math.abs(distance) < 2) return
  if (scrollFrame) cancelAnimationFrame(scrollFrame)
  const startedAt = performance.now()
  const duration = Math.min(420, Math.max(160, Math.abs(distance) * 0.32))
  const ease = (progress) => 1 - Math.pow(1 - progress, 3)
  const frame = (now) => {
    const progress = Math.min(1, (now - startedAt) / duration)
    root.scrollTop = start + distance * ease(progress)
    if (progress < 1) scrollFrame = requestAnimationFrame(frame)
  }
  scrollFrame = requestAnimationFrame(frame)
}
function setupSectionObserver() {
  const root = document.querySelector('.brand-form')
  if (!root) return
  sectionObserver?.disconnect()
  sectionObserver = new IntersectionObserver((entries) => {
    const visible = entries.filter(entry => entry.isIntersecting)
    if (!visible.length) return
    const rootTop = root.getBoundingClientRect().top
    visible.sort((a, b) => Math.abs(a.boundingClientRect.top - rootTop) - Math.abs(b.boundingClientRect.top - rootTop))
    activeSection.value = visible[0].target.id
  }, { root, rootMargin: '-8% 0px -68% 0px', threshold: [0, 0.18, 0.45, 0.8] })
  root.querySelectorAll('.form-card[id]').forEach(section => sectionObserver.observe(section))
}
function displayImage(image) { return image?.url || '' }
</script>

<template>
  <div class="brands-page">
    <header class="brands-header"><div><p class="eyebrow">BRAND LIBRARY / 01</p><h1>品牌库</h1><p class="header-desc">把品牌的识别、叙事与文化沉淀成一份可被团队和 AI 准确引用的品牌档案。</p></div><button v-if="auth.isOwner" class="primary-button" @click="openCreateModal"><span>＋</span> 新建品牌</button></header>
    <div v-if="notice && !showModal" class="page-notice">{{ notice }}</div>
    <div v-if="loading" class="loading-state">正在加载品牌档案…</div>
    <div v-else-if="brands.length" class="brand-list"><article v-for="brand in brands" :key="brand.id" class="brand-card"><div class="brand-card-top"><div class="brand-mark" :style="{ '--brand-color': brand.primaryColor || '#0f766e' }"><img v-if="displayImage(brand.logo)" :src="displayImage(brand.logo)" alt="品牌 Logo" /><span v-else>{{ (brand.name || '品').slice(0, 1) }}</span></div><div class="brand-card-title"><h2>{{ brand.name }}</h2><p>{{ brand.slogan || '尚未填写 Slogan' }}</p></div><span class="brand-status">{{ brand.defaultBrand ? '默认品牌' : '已启用' }}</span></div><div class="brand-meta"><span>{{ brand.industry || '未填写行业' }}</span><span>{{ brand.storeCount }} 家门店</span><span v-if="brand.website">{{ brand.website }}</span><span v-if="brand.updatedAt">更新于 {{ new Date(brand.updatedAt).toLocaleDateString('zh-CN') }}</span></div><p v-if="brand.intro" class="brand-intro">{{ brand.intro }}</p><div class="brand-card-footer"><span class="asset-count">品牌档案 · {{ [brand.logo, brand.viGuidelines, brand.miniProgramQr, brand.wechatQr].filter(Boolean).length }} 项视觉资产</span><div v-if="auth.isOwner"><button v-if="!brand.defaultBrand" class="link-button" @click="handleDefault(brand)">设为默认</button><button class="link-button" @click="openEditModal(brand)">编辑档案</button><button class="link-button danger" @click="handleDelete(brand)">删除</button></div></div></article></div>
    <div v-else class="empty-state"><div class="empty-mark">✦</div><h2>{{ auth.isOwner ? '建立你的第一份品牌档案' : '还没有品牌档案' }}</h2><p>{{ auth.isOwner ? '先完成品牌名与所属赛道，其他内容可以稍后补充。' : '品牌档案由管理员建立和维护，建好后会显示在这里。' }}</p><button v-if="auth.isOwner" class="primary-button" @click="openCreateModal">新建品牌</button></div>
    <Transition name="brand-dialog" appear><div v-if="showModal" class="modal-overlay" @click.self="showModal = false"><section class="brand-modal"><header class="modal-header"><div><h2>{{ editingBrand ? '编辑品牌档案' : '新建品牌档案' }}</h2></div><button class="close-button" aria-label="关闭" @click="showModal = false">×</button></header><div class="modal-layout"><aside class="modal-aside"><div class="completion-card"><div class="completion-top"><span>资料完成度</span><strong>{{ completion }}%</strong></div><div class="completion-track"><i :style="{ width: `${completion}%` }" /></div></div><nav><p class="aside-label">PROFILE SECTIONS</p><button v-for="section in sections" :key="section.id" :class="{ active: activeSection === section.id }" @click="scrollToSection(section.id)"><span>{{ section.no }}</span><b>{{ section.label }}</b><small>{{ section.hint }}</small></button></nav><div class="aside-tip"><span>⌁</span><p>填写“表达方式”后，直播讲解和弹幕回复会参考你的品牌语气。</p></div></aside>
      <form class="brand-form" @submit.prevent="handleSubmit">
        <section id="identity" class="form-card"><div class="card-heading"><span class="step-number">01</span><div><p class="eyebrow">IDENTITY</p><h3>品牌识别</h3></div></div><div class="field-grid two-col"><label class="field"><span>品牌名 <b>*</b></span><input v-model="form.name" :class="{ invalid: errors.name }" maxlength="80" placeholder="例如：青岚茶事" /><em v-if="errors.name">{{ errors.name }}</em></label><div class="field"><span>行业 / 赛道 <b>*</b></span><div class="custom-select" :class="{ open: industryOpen, invalid: errors.industry }" @click.stop><button class="custom-select-trigger" type="button" aria-haspopup="listbox" :aria-expanded="industryOpen" @click="industryOpen = !industryOpen" @keydown="handleIndustryKey"><span :class="{ placeholder: !form.industry }">{{ form.industry || '请选择行业' }}</span><svg viewBox="0 0 20 20" aria-hidden="true"><path d="m6 8 4 4 4-4" /></svg></button><Transition name="select-pop"><div v-if="industryOpen" class="custom-select-menu" role="listbox"><button v-for="option in industryOptions" :key="option" type="button" role="option" :aria-selected="form.industry === option" :class="{ selected: form.industry === option }" @click="selectIndustry(option)"><span>{{ option }}</span><svg v-if="form.industry === option" viewBox="0 0 20 20" aria-hidden="true"><path d="m5.5 10.5 3 3 6-7" /></svg></button></div></Transition></div><em v-if="errors.industry">{{ errors.industry }}</em></div></div><label class="field"><span>Slogan</span><input v-model="form.slogan" maxlength="120" placeholder="一句话说清品牌承诺，例如：一杯好茶，见天地" /><small>建议 8–24 字，直播讲解时可能会引用</small></label></section>
        <section id="voice" class="form-card"><div class="card-heading"><span class="step-number">02</span><div><p class="eyebrow">VOICE</p><h3>表达方式</h3></div></div><label class="field"><span>品牌定位</span><textarea v-model="form.positioning" rows="2" maxlength="500" placeholder="例如：新中式茶饮，主打当季鲜果与原叶茶" /></label><label class="field"><span>目标人群</span><textarea v-model="form.targetAudience" rows="2" maxlength="500" placeholder="例如：25–40 岁、注重品质的都市白领" /></label><label class="field"><span>表达风格</span><textarea v-model="form.languageStyle" rows="2" maxlength="500" placeholder="例如：温和、雅致，不用网络流行语" /><small>本品牌门店的直播讲解和弹幕回复会参考这里的写法</small></label></section>
        <section id="visual" class="form-card"><div class="card-heading"><span class="step-number">03</span><div><p class="eyebrow">VISUAL SYSTEM</p><h3>视觉资产</h3></div></div><div class="upload-grid"><label class="upload-field"><span>品牌 Logo</span><div class="upload-box" :class="{ filled: displayImage(form.logo) }" @click="logoInput?.click"><input ref="logoInput" type="file" accept="image/png,image/jpeg" hidden @change="uploadImage($event, 'logo')" /><img v-if="displayImage(form.logo)" :src="displayImage(form.logo)" alt="Logo 预览" /><template v-else><strong>＋</strong><b>{{ uploading.logo ? '上传中…' : form.logo ? '已上传，预览暂不可用' : '上传 Logo' }}</b><small>PNG / JPG，5MB 以内</small></template></div><button v-if="form.logo" type="button" class="link-button remove-image" @click.prevent="form.logo = null">移除 Logo</button></label><label class="field"><span>VI 规范</span><textarea v-model="form.viGuidelines" rows="6" maxlength="2000" placeholder="记录品牌色、字体、留白和禁用场景等规范" /><small>可粘贴链接或简要描述，方便团队统一执行</small></label></div><label class="field color-field"><span>品牌主色</span><div class="color-row"><input v-model="form.primaryColor" type="color" aria-label="品牌主色" /><code>{{ form.primaryColor }}</code></div></label><div class="qr-grid"><label class="upload-field"><span>小程序二维码</span><div class="qr-box" :class="{ filled: displayImage(form.miniProgramQr) }" @click="miniQrInput?.click"><input ref="miniQrInput" type="file" accept="image/png,image/jpeg" hidden @change="uploadImage($event, 'miniProgramQr')" /><img v-if="displayImage(form.miniProgramQr)" :src="displayImage(form.miniProgramQr)" alt="小程序二维码" /><template v-else><strong>⌘</strong><small>{{ uploading.miniProgramQr ? '上传中…' : form.miniProgramQr ? '已上传，预览暂不可用' : '点击上传' }}</small></template></div><button v-if="form.miniProgramQr" type="button" class="link-button remove-image" @click.prevent="form.miniProgramQr = null">移除</button></label><label class="upload-field"><span>公众号二维码</span><div class="qr-box" :class="{ filled: displayImage(form.wechatQr) }" @click="wechatQrInput?.click"><input ref="wechatQrInput" type="file" accept="image/png,image/jpeg" hidden @change="uploadImage($event, 'wechatQr')" /><img v-if="displayImage(form.wechatQr)" :src="displayImage(form.wechatQr)" alt="公众号二维码" /><template v-else><strong>⌘</strong><small>{{ uploading.wechatQr ? '上传中…' : form.wechatQr ? '已上传，预览暂不可用' : '点击上传' }}</small></template></div><button v-if="form.wechatQr" type="button" class="link-button remove-image" @click.prevent="form.wechatQr = null">移除</button></label></div></section>
        <section id="story" class="form-card"><div class="card-heading"><span class="step-number">04</span><div><p class="eyebrow">BRAND STORY</p><h3>品牌叙事</h3></div></div><label class="field"><span>品牌简介</span><textarea v-model="form.intro" rows="3" maxlength="1000" placeholder="用 2–3 句话介绍品牌是谁、为谁服务、解决什么问题" /></label><label class="field"><span>品牌网址</span><input v-model="form.website" :class="{ invalid: errors.website }" maxlength="300" placeholder="https://www.example.com" /><em v-if="errors.website">{{ errors.website }}</em></label><label class="field"><span>发展历程</span><textarea v-model="form.history" rows="4" maxlength="2000" placeholder="按年份记录重要节点，例如：2021 年成立 · 2023 年推出首家线下店" /></label><label class="field"><span>品牌故事</span><textarea v-model="form.brandStory" rows="4" maxlength="4000" placeholder="记录品牌诞生的契机、坚持与想带给用户的改变" /></label></section>
        <section id="culture" class="form-card"><div class="card-heading"><span class="step-number">05</span><div><p class="eyebrow">TEAM & CULTURE</p><h3>团队与文化</h3></div></div><label class="field"><span>核心团队</span><textarea v-model="form.coreTeam" rows="3" maxlength="2000" placeholder="例如：创始人 / 主理人、设计负责人及其分工" /></label><label class="field"><span>企业文化</span><textarea v-model="form.culture" rows="4" maxlength="2000" placeholder="记录使命、愿景、价值观与团队工作方式" /></label></section>
        <div class="form-footer"><p v-if="notice" class="form-notice">{{ notice }}</p><div><button type="button" class="ghost-button" @click="showModal = false">取消</button><button type="submit" class="primary-button" :disabled="saving">{{ saving ? '保存中…' : editingBrand ? '保存修改' : '创建品牌' }}</button></div></div>
      </form></div></section></div></Transition>
  </div>
</template>

<style scoped>
:global(.page-scroll.brands-scroll){background:var(--color-bg-canvas);color:var(--color-primary)}.brands-page{max-width:1400px;margin:0 auto;padding:24px 32px 72px;font-family:var(--font-sans);color:var(--color-primary)}.brands-header{display:flex;justify-content:space-between;align-items:flex-end;margin-bottom:30px}.eyebrow{margin:0;color:var(--color-text-muted);font-size:10px;font-weight:700;letter-spacing:.14em}.brands-header h1{margin:9px 0 7px;font-size:34px;line-height:1.15;letter-spacing:.01em;color:var(--color-primary)}.header-desc{max-width:650px;margin:0;color:var(--color-text-muted);font-size:14px;line-height:1.65}.primary-button,.ghost-button,.link-button{font-family:var(--font-sans);font-size:13px;font-weight:700;cursor:pointer}.primary-button{border:1px solid var(--color-accent);border-radius:4px;padding:11px 17px;color:var(--color-on-accent);background:var(--color-accent);box-shadow:none}.primary-button:hover{background:var(--color-accent-hover)}.primary-button span{font-size:16px;margin-right:3px}.ghost-button{padding:11px 16px;border:1px solid var(--color-border-subtle);border-radius:4px;color:var(--color-text-muted);background:var(--color-bg-surface)}.brand-list{display:grid;grid-template-columns:repeat(auto-fill,minmax(360px,1fr));gap:16px}.brand-card{padding:21px 22px 17px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface);box-shadow:var(--shadow-paper);transition:.2s}.brand-card:hover{transform:translateY(-2px);border-color:var(--color-border-subtle);box-shadow:var(--shadow-paper)}.brand-card-top{display:flex;align-items:center;gap:12px}.brand-mark{display:grid;place-items:center;width:50px;height:50px;border-radius:8px;color:#fff;background:var(--brand-color);font-size:21px;font-weight:800;overflow:hidden}.brand-mark img{width:100%;height:100%;object-fit:contain;background:#fff}.brand-card-title{min-width:0;flex:1}.brand-card-title h2{margin:0 0 5px;font-size:18px;color:var(--color-primary)}.brand-card-title p{margin:0;color:var(--color-text-muted);font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.brand-status{padding:4px 8px;border-radius:4px;color:var(--color-success);background:color-mix(in srgb, var(--color-success) 7%, var(--color-bg-surface));font-size:10px;font-weight:700}.brand-meta{display:flex;gap:10px;flex-wrap:wrap;margin:18px 0 12px;padding-top:14px;border-top:1px solid var(--color-border-subtle);color:var(--color-text-muted);font-size:11px}.brand-meta span+span{padding-left:10px;border-left:1px solid var(--color-border-subtle)}.brand-intro{margin:0 0 17px;color:var(--color-primary);font-size:12px;line-height:1.65}.brand-card-footer{display:flex;justify-content:space-between;align-items:center;gap:10px}.asset-count{color:var(--color-text-muted);font-size:10px}.link-button{padding:5px 8px;border:0;background:transparent;color:var(--color-accent-text)}.link-button.danger{color:var(--color-error)}.empty-state,.loading-state{padding:86px 20px;text-align:center}.empty-state{border:1px dashed var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface)}.empty-mark{margin-bottom:10px;color:var(--color-accent-text);font-size:32px}.empty-state h2{margin:0 0 8px;font-size:20px}.empty-state p,.loading-state{color:var(--color-text-muted);font-size:13px}.empty-state .primary-button{margin-top:18px}.page-notice{margin-bottom:16px;padding:11px 14px;border-radius:8px;color:var(--color-text-muted);background:var(--color-bg-canvas);font-size:12px}
.modal-overlay{position:fixed;inset:0;z-index:1000;display:grid;place-items:center;padding:20px;background:rgba(24,42,42,.42);backdrop-filter:blur(5px)}.brand-modal{width:min(1080px,100%);max-height:92vh;overflow:hidden;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface);box-shadow:var(--shadow-paper)}.modal-header{display:flex;align-items:center;justify-content:space-between;padding:23px 28px 20px;border-bottom:1px solid var(--color-border-subtle);background:var(--color-bg-surface)}.modal-header h2{margin:6px 0 0;font-size:23px;letter-spacing:.01em}.close-button{width:32px;height:32px;border:0;border-radius:4px;color:var(--color-text-muted);background:transparent;font-size:26px;cursor:pointer}.close-button:hover{background:var(--color-bg-subtle);color:var(--color-primary)}.modal-layout{display:grid;grid-template-columns:220px minmax(0,1fr);height:calc(92vh - 89px);min-height:0}.modal-aside{min-height:0;padding:22px 15px;overflow:auto;border-right:1px solid var(--color-border-subtle);background:var(--color-bg-canvas)}.completion-card{padding:14px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface)}.completion-top{display:flex;justify-content:space-between;align-items:center;color:var(--color-text-muted);font-size:11px}.completion-top strong{color:var(--color-text-muted);font-size:19px}.completion-track{height:6px;margin:12px 0 8px;border-radius:8px;background:var(--color-bg-subtle);overflow:hidden}.completion-track i{display:block;height:100%;border-radius:inherit;background:var(--color-accent);transition:.2s}.completion-card small{color:var(--color-text-muted);font-size:10px}.aside-label{margin:22px 10px 8px;color:var(--color-text-muted);font-size:9px;font-weight:700;letter-spacing:.14em}.modal-aside nav button{display:grid;grid-template-columns:28px 1fr;gap:2px 8px;width:100%;padding:10px;border:0;border-radius:4px;text-align:left;color:var(--color-text-muted);background:transparent;cursor:pointer}.modal-aside nav button:hover,.modal-aside nav button.active{color:var(--color-accent-text);background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}.modal-aside nav button span{grid-row:span 2;font-size:10px;font-weight:700}.modal-aside nav button b{font-size:12px}.modal-aside nav button small{font-size:10px;color:var(--color-text-muted)}.aside-tip{display:flex;gap:7px;margin:18px 5px 0;padding:11px;border-radius:8px;color:var(--color-text-muted);background:var(--color-bg-surface)}.aside-tip span{font-size:17px}.aside-tip p{margin:0;font-size:10px;line-height:1.55}.brand-form{min-height:0;padding:20px 25px 28px;overflow:auto;scroll-behavior:smooth}.form-card{scroll-margin-top:15px;margin-bottom:15px;padding:21px 22px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface)}.card-heading{display:flex;align-items:center;gap:10px;margin-bottom:20px}.step-number{display:grid;place-items:center;width:29px;height:29px;border-radius:8px;color:var(--color-accent-text);background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface));font-size:11px;font-weight:800}.card-heading h3{margin:4px 0 0;font-size:17px}.card-heading>small{margin-left:auto;color:var(--color-text-muted);font-size:10px}.field-grid{display:grid;gap:16px}.two-col{grid-template-columns:1fr 1fr}.field,.upload-field{display:grid;gap:7px;margin:0 0 16px}.field>span,.upload-field>span{display:flex;align-items:center;gap:6px;color:var(--color-primary);font-size:12px;font-weight:700}.field b{color:var(--color-text-muted)}.field i,.upload-field i{color:var(--color-text-muted);font-size:10px;font-style:normal;font-weight:500}.field input,.field select,.field textarea{width:100%;box-sizing:border-box;padding:10px 11px;border:1px solid var(--color-border-subtle);border-radius:4px;outline:0;color:var(--color-primary);background:var(--color-bg-surface);font:500 13px/1.5 inherit;transition:.15s;resize:vertical}.field input{height:40px}.field input:focus,.field select:focus,.field textarea:focus{border-color:var(--color-accent);box-shadow:var(--shadow-paper)}.field input.invalid,.field select.invalid{border-color:var(--color-error)}.field small,.field em{color:var(--color-text-muted);font-size:10px;font-style:normal}.field em{color:var(--color-text-muted)}.upload-grid{display:grid;grid-template-columns:190px 1fr;gap:18px}.upload-box{display:grid;place-items:center;align-content:center;min-height:148px;border:1px dashed var(--color-border-subtle);border-radius:8px;color:var(--color-text-muted);background:var(--color-bg-surface);cursor:pointer}.upload-box:hover,.qr-box:hover{border-color:var(--color-border-subtle);background:var(--color-bg-subtle)}.upload-box strong{color:var(--color-text-muted);font-size:27px}.upload-box b{margin-top:5px;font-size:12px}.upload-box small{margin-top:5px;font-size:10px}.upload-box img{width:100%;height:148px;object-fit:contain}.qr-grid{display:grid;grid-template-columns:190px 190px;gap:18px}.qr-box{display:grid;place-items:center;min-height:132px;border:1px dashed var(--color-border-subtle);border-radius:8px;color:var(--color-text-muted);background:var(--color-bg-surface);cursor:pointer;text-align:center}.qr-box strong{font-size:25px;color:var(--color-text-muted)}.qr-box small{font-size:10px}.qr-box img{width:100%;height:132px;object-fit:contain}.form-footer{display:flex;justify-content:space-between;align-items:center;padding:4px 2px}.form-footer>div{display:flex;gap:9px;margin-left:auto}.form-notice{margin:0;color:var(--color-error);font-size:11px}
@media (max-width:900px){.brands-page{padding:22px 20px 58px}.modal-layout{grid-template-columns:1fr}.modal-aside{display:none}.brand-form{max-height:calc(92vh - 89px)}}@media (max-width:620px){.brands-page{padding:18px 14px 48px}.brands-header{display:block}.brands-header .primary-button{margin-top:18px}.brands-header h1{font-size:30px}.brand-list{grid-template-columns:1fr}.brand-card-footer{align-items:flex-end;flex-direction:column}.brand-card-footer>div{width:100%;display:flex;justify-content:flex-end}.modal-overlay{padding:0}.brand-modal{max-height:100vh;height:100vh;border-radius:0}.modal-header{padding:18px 19px}.brand-form{padding:16px}.two-col,.upload-grid,.qr-grid{grid-template-columns:1fr}.form-card{padding:18px}.upload-box{min-height:130px}.upload-box img{height:130px}.form-footer{display:block}.form-footer>div{margin-top:12px;justify-content:flex-end}.form-footer .primary-button{flex:1}}@media(prefers-reduced-motion:reduce){.brand-card{transition:none}.brand-form{scroll-behavior:auto}}
/* Brand archive typography and rhythm refinement */
.brands-page{--brand-rounded-font:var(--font-sans);font-family:var(--font-sans);-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility}
.brands-header h1,.modal-header h2,.brand-card-title h2,.form-card h3{font-family:var(--font-serif);font-weight:750}
.brands-header h1{font-size:36px;letter-spacing:.01em;line-height:1.18}.header-desc{font-size:13px;letter-spacing:.01em;line-height:1.75}.modal-header{padding:25px 30px 23px}.modal-header h2{font-size:24px;letter-spacing:.01em}.brand-form{padding:24px 28px 34px}.form-card{padding:24px 25px;border-radius:8px}.card-heading{margin-bottom:22px}.card-heading h3{font-size:18px;letter-spacing:.01em}.field>span,.upload-field>span{font-size:13px;letter-spacing:.01em}.field input,.field select,.field textarea{font-size:14px;line-height:1.6}.field input{height:42px}.field small,.field em{font-size:11px;line-height:1.5}.modal-aside{padding:24px 16px}.modal-aside nav button{padding:11px 10px}.modal-aside nav button b{font-size:13px}.modal-aside nav button small{font-size:11px}.completion-top{font-size:12px}.completion-top strong{font-size:20px}
.field input,.field select,.field textarea,.custom-select-trigger,.custom-select-menu button{font-family:var(--font-sans);font-weight:400;letter-spacing:.012em}.field textarea{line-height:1.85}.custom-select-menu button{letter-spacing:.01em}.upload-box b,.upload-box small,.qr-box small{font-family:var(--font-sans);font-weight:400}.field input::placeholder,.field textarea::placeholder{font-family:var(--font-sans);font-weight:400;letter-spacing:.01em}
.upload-grid,.qr-grid{align-items:start}.upload-grid>.field,.upload-grid>.upload-field,.qr-grid>.upload-field{align-self:start;min-width:0;display:flex;flex-direction:column;align-items:stretch;gap:7px}.upload-field>span,.field>span{display:flex;align-items:center;min-height:20px;height:20px;line-height:20px}.upload-box,.qr-box{margin-top:0}
.upload-grid>* ,.qr-grid>*{margin-top:0!important;padding-top:0!important}
.upload-box strong,.qr-box strong{font-family:var(--font-sans);font-weight:600;line-height:1;font-variation-settings:"wght" 600}.upload-box b,.qr-box small{font-family:var(--font-sans);font-weight:600;letter-spacing:.02em;line-height:1.45;font-variation-settings:"wght" 560;color:var(--color-primary)}.upload-box small{font-size:11px;font-weight:500;letter-spacing:.035em;color:var(--color-text-muted);font-variation-settings:"wght" 500}.qr-box small{font-size:12px;font-weight:500;color:var(--color-text-muted);font-variation-settings:"wght" 500}
.modal-aside nav button{transition:color .2s ease,background .24s ease,transform .24s cubic-bezier(.22,1,.36,1)}.modal-aside nav button:hover{transform:translateX(2px);background:var(--color-bg-subtle)}.modal-aside nav button.active{transform:translateX(3px);background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}
.brand-dialog-enter-active,.brand-dialog-leave-active{transition:opacity .28s ease}.brand-dialog-enter-active .brand-modal,.brand-dialog-leave-active .brand-modal{transition:opacity .34s cubic-bezier(.22,1,.36,1),transform .42s cubic-bezier(.22,1,.36,1),filter .34s ease}.brand-dialog-enter-from,.brand-dialog-leave-to{opacity:0}.brand-dialog-enter-from .brand-modal{opacity:0;transform:translateY(18px) scale(.965);filter:blur(3px)}.brand-dialog-leave-to .brand-modal{opacity:0;transform:translateY(10px) scale(.985);filter:blur(2px)}.close-button,.primary-button,.ghost-button{transition:transform .2s cubic-bezier(.22,1,.36,1),background .2s ease,box-shadow .2s ease,border-color .2s ease}.close-button:hover{transform:rotate(7deg) scale(1.05)}.close-button:active,.primary-button:active,.ghost-button:active{transform:scale(.96)}.brand-dialog-enter-active .modal-header{animation:dialog-header-in .46s .08s both cubic-bezier(.22,1,.36,1)}.brand-dialog-enter-active .form-card{animation:dialog-card-in .5s both cubic-bezier(.22,1,.36,1);animation-delay:calc(var(--card-index, 0) * 45ms + 90ms)}@keyframes dialog-header-in{from{opacity:0;transform:translateY(-8px)}to{opacity:1;transform:none}}@keyframes dialog-card-in{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:none}}
.custom-select{position:relative}.custom-select-trigger{display:flex;align-items:center;justify-content:space-between;width:100%;height:42px;padding:0 11px;border:1px solid var(--color-border-subtle);border-radius:4px;outline:0;color:var(--color-primary);background:var(--color-bg-surface);font:500 14px/1.6 inherit;cursor:pointer;transition:border-color .18s ease,box-shadow .18s ease,background .18s ease}.custom-select-trigger:hover{border-color:var(--color-border-subtle);background:var(--color-bg-subtle)}.custom-select.open .custom-select-trigger,.custom-select-trigger:focus-visible{border-color:var(--color-accent);box-shadow:var(--shadow-paper);background:var(--color-bg-surface)}.custom-select-trigger span.placeholder{color:var(--color-text-muted)}.custom-select-trigger svg{width:17px;height:17px;fill:none;stroke:#315858;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round;transition:transform .22s cubic-bezier(.22,1,.36,1),stroke .18s}.custom-select.open .custom-select-trigger svg{transform:rotate(180deg);stroke:#0f766e}.custom-select.invalid .custom-select-trigger{border-color:var(--color-error)}.custom-select-menu{position:absolute;z-index:20;top:calc(100% + 7px);left:0;right:0;padding:6px;border:1px solid var(--color-border-subtle);border-radius:4px;background:var(--color-bg-surface);box-shadow:var(--shadow-paper);transform-origin:top center}.custom-select-menu button{display:flex;align-items:center;justify-content:space-between;width:100%;min-height:36px;padding:0 10px;border:0;border-radius:7px;color:var(--color-primary);background:transparent;font:500 13px/1.4 inherit;text-align:left;cursor:pointer;transition:background .15s ease,color .15s ease,transform .15s ease}.custom-select-menu button:hover{color:var(--color-text-muted);background:var(--color-bg-subtle);transform:translateX(2px)}.custom-select-menu button.selected{color:var(--color-accent-text);background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface));font-weight:700}.custom-select-menu button svg{width:16px;height:16px;fill:none;stroke:#0f766e;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}.select-pop-enter-active,.select-pop-leave-active{transition:opacity .16s ease,transform .2s cubic-bezier(.22,1,.36,1)}.select-pop-enter-from,.select-pop-leave-to{opacity:0;transform:translateY(-6px) scale(.98)}
/* Modal choreography: a soft backdrop, a weighted lift, and gentle content reveal. */
.brand-dialog-enter-active,.brand-dialog-leave-active{transition:opacity .28s ease}
.brand-dialog-enter-active .brand-modal,.brand-dialog-leave-active .brand-modal{transition:opacity .34s cubic-bezier(.22,1,.36,1),transform .42s cubic-bezier(.22,1,.36,1),filter .34s ease}
.brand-dialog-enter-from,.brand-dialog-leave-to{opacity:0}
.brand-dialog-enter-from .brand-modal{opacity:0;transform:translateY(18px) scale(.965);filter:blur(3px)}
.brand-dialog-leave-to .brand-modal{opacity:0;transform:translateY(10px) scale(.985);filter:blur(2px)}
.close-button,.primary-button,.ghost-button{transition:transform .2s cubic-bezier(.22,1,.36,1),background .2s ease,box-shadow .2s ease,border-color .2s ease}
.close-button:hover{transform:rotate(7deg) scale(1.05)}
.close-button:active,.primary-button:active,.ghost-button:active{transform:scale(.96)}
.brand-dialog-enter-active .modal-header{animation:dialog-header-in .46s .08s both cubic-bezier(.22,1,.36,1)}
.brand-dialog-enter-active .form-card{animation:dialog-card-in .5s both cubic-bezier(.22,1,.36,1)}
.brand-dialog-enter-active .form-card:nth-of-type(1){animation-delay:.10s}.brand-dialog-enter-active .form-card:nth-of-type(2){animation-delay:.15s}.brand-dialog-enter-active .form-card:nth-of-type(3){animation-delay:.20s}.brand-dialog-enter-active .form-card:nth-of-type(4){animation-delay:.25s}
@keyframes dialog-header-in{from{opacity:0;transform:translateY(-8px)}to{opacity:1;transform:none}}@keyframes dialog-card-in{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:none}}
@media(prefers-reduced-motion:reduce){.brand-dialog-enter-active,.brand-dialog-leave-active,.brand-dialog-enter-active .brand-modal,.brand-dialog-leave-active .brand-modal{transition:none}.brand-dialog-enter-active .modal-header,.brand-dialog-enter-active .form-card{animation:none}}.brands-page{transition:color .25s ease}.brands-header h1{transition:color .25s ease}.brand-card,.empty-state{transition:background .25s ease,border-color .25s ease,box-shadow .25s ease,transform .2s ease}.brand-card-title h2{transition:color .25s ease}.brand-meta{transition:border-color .25s ease,color .25s ease}
.color-row{display:flex;align-items:center;gap:12px}.color-row input{width:56px;height:36px;padding:3px;cursor:pointer}.color-row code{color:var(--color-text-muted);font:500 12px/1 var(--font-mono)}
.remove-image{justify-self:start;padding:0;font-size:11px;font-weight:500}.primary-button:disabled{opacity:.6;cursor:default}
.brand-dialog-enter-active .form-card:nth-of-type(5){animation-delay:.30s}
</style>
