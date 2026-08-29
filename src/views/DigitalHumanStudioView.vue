<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'

const avatarInput = ref(null)
const assetsInput = ref(null)
const avatarFile = ref(null)
const productFile = ref(null)
const packageFile = ref(null)
const showAvatarLibrary = ref(false)
const showAssetLibrary = ref(false)
const script = ref('')
const duration = ref(10)
const ratio = ref('9:16')
const isGenerating = ref(false)
const notice = ref('')
const showAllAvatars = ref(false)
const router = useRouter()

const inspirations = [
  { title: '护肤精华', type: '美妆口播', image: 'https://images.unsplash.com/photo-1556228578-8c89e6adf883?w=320&q=80' },
  { title: '智能剃须刀', type: '数码测评', image: 'https://images.unsplash.com/photo-1621607512214-68297480165e?w=320&q=80' },
  { title: '城市轻食', type: '门店推荐', image: 'https://images.unsplash.com/photo-1547592180-85f173990554?w=320&q=80' },
  { title: '春日香氛', type: '生活方式', image: 'https://images.unsplash.com/photo-1547887538-e3a2f32cb1cc?w=320&q=80' },
]
const mockProduct = '/images/digital-human/product-showcase.jpg'
const digitalHumanAvatars = [
  { id: 'avatar-1', label: '1号数字人', name: 'Ethan', image: '/images/digital-human/avatar-1.png' },
  { id: 'avatar-2', label: '2号数字人', name: 'Mason', image: '/images/digital-human/avatar-2.png' },
  { id: 'avatar-3', label: '3号数字人', name: 'Leo', image: '/images/digital-human/avatar-3.png' },
  { id: 'avatar-4', label: '4号数字人', name: 'Sophie', image: '/images/digital-human/avatar-4.png' },
  { id: 'avatar-5', label: '5号数字人', name: 'Ava', image: '/images/digital-human/avatar-5.png' },
  { id: 'avatar-6', label: '6号数字人', name: 'Mia', image: '/images/digital-human/avatar-6.png' },
  { id: 'avatar-7', label: '7号数字人', name: 'Olivia', image: '/images/digital-human/avatar-7.png' },
  { id: 'avatar-8', label: '8号数字人', name: 'Grace', image: '/images/digital-human/avatar-8.png' },
  { id: 'avatar-9', label: '9号数字人', name: 'Emma', image: '/images/digital-human/avatar-9.png' },
]
const avatarLibrary = digitalHumanAvatars
const assetLibrary = [
  { name: '护肤精华', image: mockProduct },
  ...inspirations.slice(0, 3).map(item => ({ name: item.title, image: item.image })),
]

const setUpload = (type, event) => {
  const files = [...(event.target.files || [])].filter(file => file.type.startsWith('image/'))
  if (!files.length) return
  if (type === 'assets') {
    ;[productFile, packageFile].forEach(target => { if (target.value?.url?.startsWith('blob:')) URL.revokeObjectURL(target.value.url) })
    productFile.value = { name: files[0].name, url: URL.createObjectURL(files[0]) }
    packageFile.value = files[1] ? { name: files[1].name, url: URL.createObjectURL(files[1]) } : null
  } else {
    const target = { avatar: avatarFile }[type]
    if (target.value?.url?.startsWith('blob:')) URL.revokeObjectURL(target.value.url)
    target.value = { name: files[0].name, url: URL.createObjectURL(files[0]) }
  }
  event.target.value = ''
}
const selectLibraryAsset = (type, asset) => {
  if (type === 'avatar') {
    if (avatarFile.value?.url?.startsWith('blob:')) URL.revokeObjectURL(avatarFile.value.url)
    avatarFile.value = { name: asset.name, url: asset.image }
    showAvatarLibrary.value = false
    return
  }
  if (packageFile.value?.url?.startsWith('blob:')) URL.revokeObjectURL(packageFile.value.url)
  packageFile.value = { name: asset.name, url: asset.image }
  showAssetLibrary.value = false
}
const clearUpload = (type) => {
  const target = { avatar: avatarFile, product: productFile, package: packageFile }[type]
  if (target.value?.url?.startsWith('blob:')) URL.revokeObjectURL(target.value.url)
  target.value = null
}
const generate = () => {
  if (!avatarFile.value && !script.value.trim()) { notice.value = '上传一张数字人照片或输入播报文案后再开始。'; return }
  isGenerating.value = !isGenerating.value
  notice.value = isGenerating.value ? '数字人视频已进入渲染队列。' : '已暂停本次生成，可随时继续。'
}
const switchWorkspace = (path) => {
  if (path === router.currentRoute.value.path) return
  if (typeof document.startViewTransition === 'function') {
    document.startViewTransition(() => router.push(path))
    return
  }
  router.push(path)
}
onBeforeUnmount(() => [avatarFile, productFile, packageFile].forEach(item => { if (item.value?.url?.startsWith('blob:')) URL.revokeObjectURL(item.value.url) }))
</script>

<template>
  <div class="studio-page digital-human-page">
    <main class="dh-workspace">
      <aside class="dh-form-panel" aria-label="数字人视频配置">
        <div class="dh-form-scroll">
          <section class="dh-form-section dh-asset-section"><div class="dh-section-heading"><div><h2>上传数字人形象</h2></div></div><div class="dh-upload-actions"><button type="button" class="dh-source-button" @click="avatarInput?.click()"><span class="material-symbols-outlined">upload</span><span>从本地上传</span></button><button type="button" class="dh-source-button" @click="showAvatarLibrary = !showAvatarLibrary"><span class="material-symbols-outlined">photo_library</span><span>从素材库上传</span></button></div><div v-if="showAvatarLibrary" class="dh-library-grid"><button v-for="asset in avatarLibrary" :key="asset.name" type="button" class="dh-library-item" @click="selectLibraryAsset('avatar', asset)"><img :src="asset.image" :alt="asset.name"><span>{{ asset.name }}</span></button></div><div v-if="avatarFile" class="dh-upload-preview"><img :src="avatarFile.url" alt="已选数字人形象"><span class="dh-upload-file">{{ avatarFile.name }}</span><button type="button" aria-label="移除数字人形象" @click="clearUpload('avatar')">×</button></div><input ref="avatarInput" class="video-file-input" type="file" accept="image/*" @change="setUpload('avatar', $event)"><p class="dh-field-hint">支持 JPG、PNG、WEBP · 大小 6MB 以内</p></section>
          <section class="dh-form-section dh-asset-section"><div class="dh-section-heading"><div><h2>上传所需图片</h2></div></div><div class="dh-upload-actions"><button type="button" class="dh-source-button" @click="assetsInput?.click()"><span class="material-symbols-outlined">upload</span><span>从本地上传</span></button><button type="button" class="dh-source-button" @click="showAssetLibrary = !showAssetLibrary"><span class="material-symbols-outlined">photo_library</span><span>从素材库上传</span></button></div><div v-if="showAssetLibrary" class="dh-library-grid"><button v-for="asset in assetLibrary" :key="asset.name" type="button" class="dh-library-item" @click="selectLibraryAsset('asset', asset)"><img :src="asset.image" :alt="asset.name"><span>{{ asset.name }}</span></button></div><div v-if="productFile || packageFile" class="dh-upload-preview-list"><div v-for="asset in [productFile, packageFile].filter(Boolean)" :key="asset.name" class="dh-upload-preview"><img :src="asset.url" :alt="asset.name"><span class="dh-upload-file">{{ asset.name }}</span></div></div><input ref="assetsInput" class="video-file-input" type="file" accept="image/*" multiple @change="setUpload('assets', $event)"></section>
          <section class="dh-form-section dh-copy-section"><div class="dh-section-heading"><div><h2>描述内容</h2></div><span class="dh-counter">{{ script.length }}/500</span></div><div class="dh-textarea-shell"><textarea v-model="script" maxlength="500" placeholder="输入视频文案或商品描述，例如：今天给大家分享一款适合敏感肌的春日精华，轻薄好吸收，换季也能保持水润光泽。" /></div></section>
          <section class="dh-form-section dh-setting-section"><div class="dh-section-heading"><div><h2>视频设置</h2></div></div><div class="dh-setting-row"><span>视频时长</span><div class="dh-duration-options"><button v-for="item in [5, 10, 15]" :key="item" type="button" :class="{ selected: duration === item }" @click="duration = item">{{ item }}s</button></div></div><div class="dh-setting-row"><span>画面比例</span><div class="dh-ratio-options"><button type="button" :class="{ selected: ratio === '16:9' }" @click="ratio = '16:9'"><i class="dh-ratio-icon landscape" />16:9 横屏</button><button type="button" :class="{ selected: ratio === '9:16' }" @click="ratio = '9:16'"><i class="dh-ratio-icon portrait" />9:16 竖屏</button></div></div></section>
        </div>
        <div class="dh-form-footer"><button class="dh-generate-button" type="button" :aria-pressed="isGenerating" @click="generate"><span>{{ isGenerating ? '正在生成…' : '立即生成' }}</span><span class="material-symbols-outlined">arrow_forward</span></button><p v-if="notice" class="dh-notice" role="status">{{ notice }}</p><small>预计消耗 12 算力 · 约 1–2 分钟完成</small></div>
      </aside>

      <section class="dh-preview-panel" aria-label="数字人视频预览">
        <div class="dh-studio-switcher video-mode-switch" role="group" aria-label="切换工作台">
          <button type="button" @click="switchWorkspace('/video/workbench')"><span class="material-symbols-outlined">auto_awesome</span>视频工作台</button>
          <button type="button" class="active" aria-pressed="true" @click="switchWorkspace('/digital-human/studio')"><span class="material-symbols-outlined">record_voice_over</span>数字人摄影棚</button>
        </div>
        <div class="dh-preview-intro"><h2>上传模特与商品素材，<br><em>智能生成口播带货视频</em></h2><p>快去左侧创建你的灵感吧～</p></div><div class="dh-banner" :class="ratio === '9:16' ? 'banner-portrait' : 'banner-landscape'"><div class="dh-banner-image dh-banner-person"><img :src="avatarFile?.url || digitalHumanAvatars[0].image" alt="数字人示例"><span class="dh-banner-tag">数字人出镜</span></div><div class="dh-banner-image dh-banner-product"><img :src="packageFile?.url || productFile?.url || mockProduct" alt="商品示例"><span class="dh-banner-tag">商品卖点</span></div><div class="dh-banner-glow" /></div><div class="dh-inspiration"><div class="dh-inspiration-heading"><div><h3>没有灵感？试试星枢原创数字人形象</h3></div><button type="button" @click="showAllAvatars = true">查看全部 <span class="material-symbols-outlined">arrow_forward</span></button></div><div class="dh-inspiration-grid"><button v-for="item in digitalHumanAvatars.slice(0, 4)" :key="item.id" class="dh-inspiration-card" type="button" @click="selectLibraryAsset('avatar', item)"><span class="dh-inspiration-thumb"><img :src="item.image" :alt="item.label"></span><span><strong>{{ item.label }}</strong><small>{{ item.name }}</small></span></button></div></div></section>
    </main>
    <Transition name="dh-avatar-modal" appear>
      <div v-if="showAllAvatars" class="dh-avatar-modal" role="dialog" aria-modal="true" aria-label="全部数字人形象" @click.self="showAllAvatars = false">
        <Transition name="dh-avatar-modal-card" appear>
          <div class="dh-avatar-modal-card">
            <div class="dh-avatar-modal-header"><h2>星枢原创数字人形象</h2><button type="button" aria-label="关闭" @click="showAllAvatars = false">×</button></div>
            <div class="dh-avatar-modal-grid"><button v-for="item in digitalHumanAvatars" :key="item.id" type="button" class="dh-avatar-modal-item" @click="selectLibraryAsset('avatar', item); showAllAvatars = false"><span class="dh-avatar-modal-thumb"><img :src="item.image" :alt="item.label"></span><span><strong>{{ item.label }}</strong><small>{{ item.name }}</small></span></button></div>
          </div>
        </Transition>
      </div>
    </Transition>
  </div>
</template>
