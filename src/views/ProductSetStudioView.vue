<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import ImageWorkspaceSwitch from '../components/ImageWorkspaceSwitch.vue'
import ImageCreationWorkspace from '../components/ImageCreationWorkspace.vue'
import ImageReferenceList from '../components/ImageReferenceList.vue'
import PosterReferenceLibrary from '../components/PosterReferenceLibrary.vue'
import { useImageCreation } from '../composables/useImageCreation'
import { IMAGE_QUALITIES, IMAGE_RATIOS, imageDimensions } from '../domain/imageCreation'
import { assetApi } from '../services/imageCreation'
import { assets, assetSource } from '../stores/assetLibrary'
import '../image-workflow.css'

const studio = useImageCreation('PRODUCT_SET')
const fileInput = ref(null)
const workspace = ref(null)
const libraryOpen = ref(false)
const libraryAssets = ref([])
const qualityDimensions = quality => {
  try { return imageDimensions(quality, studio.ratio) }
  catch { return { width: 0, height: 0 } }
}
watch(() => studio.current?.id, async id => {
  if (!id || !window.matchMedia('(max-width: 760px)').matches) return
  await nextTick()
  workspace.value?.$el.scrollIntoView({ block: 'start' })
})
const platformGroup = ref('commerce')
const selectedPlatform = ref('淘宝')
const imageType = ref('PRODUCT_MAIN')
const productImages = [
  '/images/product-set-case-1.png',
  '/images/product-set-case-2.png',
  '/images/product-set-case-3.png',
  '/images/product-wall/sink-product.png',
  '/images/product-wall/rug-benefits.jpg',
  '/images/product-wall/leather-look.png',
  '/images/product-wall/fur-coat.png',
  '/images/product-wall/floral-nails.png',
  '/images/product-wall/beef-pizza.png',
  '/images/product-wall/pasta-special.png',
  '/images/product-wall/sofa-texture.png',
]

const platforms = {
  commerce: [
    { name: '淘宝', value: 'TAOBAO', mark: '淘', tone: 'orange', logo: '/images/platform-logos/taobao.png' },
    { name: '京东', value: 'JD', mark: '京', tone: 'red', logo: '/images/platform-logos/jd.png' },
    { name: '拼多多', value: 'PDD', mark: '拼', tone: 'crimson', logo: '/images/platform-logos/pinduoduo.png' },
    { name: '小红书', value: 'XIAOHONGSHU', mark: '红', tone: 'pink', logo: '/images/platform-logos/xiaohongshu.png' },
  ],
  local: [
    { name: '美团', value: 'MEITUAN', mark: '美', tone: 'yellow', logo: '/images/platform-logos/meituan.png' },
    { name: '淘宝闪购', value: 'TAOBAO_FLASH', mark: '闪', tone: 'orange', logo: '/images/platform-logos/taobao-flash.png' },
    { name: '抖音团购', value: 'DOUYIN_GROUP', mark: '团', tone: 'dark', logo: '/images/platform-logos/douyin.png' },
    { name: '大众点评', value: 'DIANPING', mark: '点', tone: 'red', logo: '/images/platform-logos/dianping.png' },
  ],
}


const buildWallColumn = offset => {
  const sequence = Array.from({ length: 6 }, (_, index) => productImages[(offset + index) % productImages.length])
  return [...sequence, ...sequence]
}
const wallColumns = [buildWallColumn(0), buildWallColumn(3), buildWallColumn(6)]
const visiblePlatforms = computed(() => platforms[platformGroup.value])
const imageTypes = computed(() => platformGroup.value === 'local'
  ? [{ value: 'DISH', label: '菜品图', count: 3 }, { value: 'STORE', label: '门店美化图', count: 3 }]
  : [{ value: 'PRODUCT_MAIN', label: '商品主图', count: 1 }, { value: 'WHITE_BG', label: '白底图', count: 1 }, { value: 'DETAIL', label: '详情图', count: 3 }, { value: 'SCENE_SET', label: '场景套图', count: 3 }])
function selectPlatformGroup(group) {
  platformGroup.value = group
  selectedPlatform.value = platforms[group][0].name
  imageType.value = group === 'local' ? 'DISH' : 'PRODUCT_MAIN'
  studio.platform = platforms[group][0].value
  studio.imageType = imageType.value
}
function selectImageType(type) {
  imageType.value = type
  studio.imageType = type
  studio.count = imageTypes.value.find(item => item.value === type)?.count || 1
}
function reset() {
  studio.resetDraft()
  studio.count = 1
  selectPlatformGroup('commerce')
}
watch([selectedPlatform, imageType], () => {
  const platform = visiblePlatforms.value.find(item => item.name === selectedPlatform.value)
  studio.platform = platform?.value || 'LOCAL'
  studio.imageType = imageType.value
  studio.purpose = selectedPlatform.value + ' / ' + imageTypes.value.find(type => type.value === imageType.value)?.label
}, { immediate: true })
onMounted(async () => {
  try {
    const result = await assetApi.images()
    libraryAssets.value = (result?.items || []).filter(asset => asset.type === 'IMAGE' && asset.status === 'READY' && asset.previewUrl)
      .map(asset => ({ ...asset, kind: 'image', preview: asset.previewUrl, folder: '我的素材' }))
  } catch {
    libraryAssets.value = assets.value.filter(asset => asset.kind === 'image' && assetSource(asset))
  }
})
</script>

<template>
  <section class="product-set-studio creation-product" aria-label="产品套图工作区">
    <form class="product-control-panel" @submit.prevent="studio.generate">
      <header class="product-panel-head"><h1>产品套图</h1><p>从一件好物，写起门店的故事。</p></header>
      <fieldset class="product-form-scroll" :disabled="studio.locked">
        <section class="product-form-section">
          <div class="product-section-label"><strong>上传商品图</strong><small>{{ studio.files.length ? `已选择 ${studio.files.length} 张` : '最多 6 张' }}</small></div>
          <label class="product-upload-zone" tabindex="0" @keydown.enter.prevent="fileInput.click()" @keydown.space.prevent="fileInput.click()" @dragover.prevent @drop.prevent="studio.addFiles">
            <input ref="fileInput" type="file" accept="image/jpeg,image/png" multiple @change="studio.addFiles" />
            <span class="product-upload-icon material-symbols-outlined" aria-hidden="true">add_photo_alternate</span><strong>上传清晰的商品图</strong><small>JPG、PNG · 每张不超过 20 MB</small><span class="product-upload-action">从本地选择</span>
          </label>
          <button type="button" class="product-library-action" :disabled="studio.locked" @click="libraryOpen = !libraryOpen"><span class="material-symbols-outlined" aria-hidden="true">photo_library</span>{{ libraryOpen ? '收起素材库' : '从素材库选择' }}</button>
          <PosterReferenceLibrary v-if="libraryOpen" :files="studio.files" :disabled="studio.locked" :source-assets="libraryAssets" @choose="studio.addLibraryAsset" @close="libraryOpen = false" />
          <ImageReferenceList :files="studio.files" :disabled="studio.locked" @remove="studio.removeFile" @role="(index, role) => studio.files[index].role = role" />
          <p class="creation-field-hint">上传同一商品的不同角度，第一张主体照为主参考。</p>
        </section>
        <section class="product-form-section product-type-section">
          <div class="product-section-label"><strong>图片类型</strong><small>选择生成用途</small></div>
          <div class="product-type-tabs" role="group" aria-label="图片类型"><button v-for="type in imageTypes" :key="type.value" type="button" :aria-pressed="imageType === type.value" :class="{ active: imageType === type.value }" @click="selectImageType(type.value)">{{ type.label }}</button></div>
        </section>
        <section class="product-form-section platform-section">
          <div class="product-section-label"><strong>选择平台</strong><small>{{ selectedPlatform }}</small></div>
          <div class="platform-group-tabs" role="group" aria-label="平台类型"><button type="button" :aria-pressed="platformGroup === 'commerce'" :class="{ active: platformGroup === 'commerce' }" @click="selectPlatformGroup('commerce')">电商平台</button><button type="button" :aria-pressed="platformGroup === 'local'" :class="{ active: platformGroup === 'local' }" @click="selectPlatformGroup('local')">本地商家</button></div>
          <div class="platform-grid"><button v-for="platform in visiblePlatforms" :key="platform.name" type="button" class="platform-card" :class="[`platform-${platform.tone}`, { active: selectedPlatform === platform.name }]" :aria-pressed="selectedPlatform === platform.name" @click="selectedPlatform = platform.name"><span class="platform-mark"><img :src="platform.logo" alt="" /><em>{{ platform.mark }}</em></span><strong>{{ platform.name }}</strong></button></div>
        </section>
        <section class="product-form-section">
          <div class="product-section-label"><label for="product-brief"><strong>描述你的画面</strong></label><small>可选</small></div>
          <textarea id="product-brief" v-model="studio.brief" placeholder="例如：这杯杨枝甘露，做一组清爽的夏日商品图" maxlength="2000" />
          <div class="product-counter">{{ studio.brief.length }} / 2000</div>
        </section>
        <section class="product-form-section product-options">
          <label class="product-option-row product-ratio-select"><span>图片比例</span><select v-model="studio.ratio" aria-label="选择图片比例"><option v-for="ratio in IMAGE_RATIOS" :key="ratio" :disabled="!studio.supportsRatio(ratio)">{{ ratio }}</option></select></label>
          <div class="product-quality-option"><span>输出画质</span><div class="product-quality-grid" role="group" aria-label="输出画质"><button v-for="quality in IMAGE_QUALITIES" :key="quality" type="button" :disabled="!studio.supportsQuality(quality)" :aria-pressed="studio.quality === quality" :class="{ active: studio.quality === quality }" @click="studio.quality = quality"><strong>{{ quality }}</strong><small>{{ qualityDimensions(quality).width ? `${qualityDimensions(quality).width} × ${qualityDimensions(quality).height}` : '当前比例不可用' }}</small></button></div></div>
          <label class="product-option-row product-ratio-select"><span>生成数量</span><select v-model.number="studio.count" aria-label="生成数量"><option v-for="n in 6" :key="n" :value="n">{{ n }} 张</option></select></label>
          <label class="product-option-row product-ratio-select"><span>画面风格</span><select v-model="studio.style" aria-label="产品图风格"><option>帮我搭配</option><option>真实自然</option><option>简约高级</option><option>东方雅致</option><option>清爽明亮</option></select></label>
        </section>
      </fieldset>
      <footer class="product-control-footer">
        <p v-if="studio.error" class="creation-item-error" role="alert">{{ studio.error }}</p>
        <p class="product-demo-note" role="status">{{ studio.capabilityState === 'checking' ? '正在检查创作服务…' : studio.capabilityState === 'error' ? `创作服务检查失败：${studio.capabilityError}` : !studio.configured ? '图片创作服务尚未接通，可先准备商品照片' : 'AI 自动安排主图、细节与场景' }} <button v-if="studio.capabilityState === 'error'" type="button" class="creation-service-retry" @click="studio.loadCapabilities">重试</button></p>
        <div class="product-save-line"><span>保存到</span><strong>作品库</strong><span class="creation-result-destination">生成后在画图区查看</span></div>
        <div class="product-footer-actions"><button type="button" class="product-reset" :disabled="studio.locked" @click="reset">重置</button><button type="submit" class="product-generate" :disabled="studio.busy || !studio.configured || studio.active || !!studio.pending">{{ studio.busy ? '正在提交…' : studio.active ? '创作中…' : '立即生成' }} <span v-if="!studio.active">→</span></button></div>
      </footer>
    </form>
    <div class="product-showcase">
      <div class="product-showcase-head">
        <div class="product-showcase-copy"><h2>让一件好物，有自己的故事。</h2><p>上传商品照片，将主图、细节与场景，编成一组有温度的画面。</p></div>
        <ImageWorkspaceSwitch />
      </div>
      <ImageCreationWorkspace ref="workspace" :studio="studio"><div class="product-wall" aria-hidden="true"><div v-for="(column, columnIndex) in wallColumns" :key="columnIndex" class="product-wall-column" :class="`product-wall-column-${columnIndex + 1}`"><img v-for="(image, index) in column" :key="`${columnIndex}-${index}`" :src="image" alt="" /></div></div></ImageCreationWorkspace>
      <footer class="product-showcase-footer"><img src="/images/brand/yifangzhi-mark.png" alt="" /><span>为每一方商家立传</span><small>一物 · 一景 · 一方志</small></footer>
    </div>
  </section>
</template>
