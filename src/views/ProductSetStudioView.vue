<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

const emit = defineEmits(['generate'])
const mode = ref('single')
const prompt = ref('')
const uploadedFiles = ref([])
const fileInput = ref(null)
const selectedRatio = ref('1:1')
const imageType = ref('single')
const selectedCount = ref(1)
const platformGroup = ref('commerce')
const selectedPlatform = ref('淘宝')
const generated = ref(false)
const router = useRouter()

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

// 固定 3 列 × 12 个轮播位（总计 36 个图片元素）。每列由 6 张素材完整重复一次，动画移动一组素材的距离后无缝衔接。
const buildWallColumn = (offset) => {
  const sequence = Array.from({ length: 6 }, (_, index) => productImages[(offset + index) % productImages.length])
  return [...sequence, ...sequence]
}
const wallColumns = computed(() => [buildWallColumn(0), buildWallColumn(3), buildWallColumn(6)])
const platforms = {
  commerce: [
    { name: '淘宝', mark: '淘', tone: 'orange', logo: '/images/platform-logos/taobao.png' },
    { name: '京东', mark: '京', tone: 'red', logo: '/images/platform-logos/jd.png' },
    { name: '拼多多', mark: '拼', tone: 'crimson', logo: '/images/platform-logos/pinduoduo.png' },
    { name: '小红书', mark: '红', tone: 'pink', logo: '/images/platform-logos/xiaohongshu.png' },
    { name: '抖音商城', mark: '抖', tone: 'dark', logo: '/images/platform-logos/douyin.png' },
  ],
  local: [
    { name: '美团', mark: '美', tone: 'yellow', logo: '/images/platform-logos/meituan.png' },
    { name: '淘宝闪购', mark: '闪', tone: 'orange', logo: '/images/platform-logos/taobao-flash.png' },
    { name: '抖音团购', mark: '团', tone: 'dark', logo: '/images/platform-logos/douyin.png' },
    { name: '大众点评', mark: '点', tone: 'red', logo: '/images/platform-logos/dianping.png' },
  ],
}

const visiblePlatforms = computed(() => platforms[platformGroup.value])
const imageTypes = computed(() => platformGroup.value === 'local'
  ? [{ value: 'dish', label: '菜品图' }, { value: 'store', label: '门店美化图' }]
  : [{ value: 'single', label: '单图' }, { value: 'detail', label: '详情图' }, { value: 'set', label: '套图' }])

const selectPlatformGroup = (group) => {
  platformGroup.value = group
  selectedPlatform.value = platforms[group][0].name
  imageType.value = group === 'local' ? 'dish' : 'single'
  selectedCount.value = 1
}

const onFile = (event) => {
  const files = Array.from(event.target?.files || event.dataTransfer?.files || []).filter(file => file.type.startsWith('image/'))
  if (files.length) uploadedFiles.value = [...uploadedFiles.value, ...files].slice(0, 6)
}

const openFilePicker = () => fileInput.value?.click()

const selectImageType = (type) => {
  imageType.value = type
  selectedCount.value = type === 'set' ? 9 : type === 'detail' ? Math.min(Math.max(selectedCount.value, 1), 10) : 1
}

const clampDetailCount = () => {
  selectedCount.value = Math.min(Math.max(Number(selectedCount.value) || 1, 1), 10)
}

const reset = () => {
  uploadedFiles.value = []
  prompt.value = ''
  selectedRatio.value = '1:1'
  imageType.value = 'single'
  selectedCount.value = 1
  platformGroup.value = 'commerce'
  selectedPlatform.value = '淘宝'
  generated.value = false
  if (fileInput.value) fileInput.value.value = ''
}

const generate = () => {
  generated.value = true
  emit('generate', { prompt: prompt.value, ratio: selectedRatio.value, count: selectedCount.value, imageType: imageType.value, platform: selectedPlatform.value, uploadedFiles: uploadedFiles.value })
}
const switchWorkspace = (path) => {
  if (path === router.currentRoute.value.path) return
  if (typeof document.startViewTransition === 'function') {
    document.startViewTransition(() => router.push(path))
    return
  }
  router.push(path)
}
</script>

<template>
  <section class="product-set-studio" aria-label="产品套图工作区">
    <aside class="product-control-panel">
      <div class="product-mode-tabs" role="tablist" aria-label="生成模式">
        <button type="button" role="tab" :aria-selected="mode === 'single'" :class="{ active: mode === 'single' }" @click="mode = 'single'">单次生成</button>
        <button type="button" role="tab" :aria-selected="mode === 'batch'" :class="{ active: mode === 'batch' }" @click="mode = 'batch'">批量生成</button>
      </div>

      <div class="product-form-scroll">
        <section class="product-form-section">
          <div class="product-section-label"><strong>上传商品图</strong><small>{{ uploadedFiles.length ? `已选择 ${uploadedFiles.length} 张` : '最多 6 张' }}</small></div>
          <label class="product-upload-zone" @dragover.prevent @drop.prevent="onFile">
            <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" multiple @change="onFile" />
            <span class="product-upload-icon">＋</span>
            <strong>{{ uploadedFiles.length ? `已上传 ${uploadedFiles.length} 张商品图` : '上传清晰的商品图' }}</strong>
            <small>支持 JPG、PNG、WEBP，建议大于 1000px</small>
            <span class="product-upload-action" @click.prevent="openFilePicker">从本地选择</span>
          </label>
        </section>

        <section class="product-form-section product-type-section">
          <div class="product-section-label"><strong>图片类型</strong><small>选择生成用途</small></div>
          <div class="product-type-tabs" :class="{ local: platformGroup === 'local' }" role="tablist" aria-label="图片类型">
            <button v-for="type in imageTypes" :key="type.value" type="button" role="tab" :aria-selected="imageType === type.value" :class="{ active: imageType === type.value }" @click="selectImageType(type.value)">{{ type.label }}</button>
          </div>
        </section>

        <section class="product-form-section platform-section">
          <div class="product-section-label"><strong>选择平台</strong><small>{{ selectedPlatform }}</small></div>
          <div class="platform-group-tabs" role="tablist" aria-label="平台类型">
            <button type="button" role="tab" :aria-selected="platformGroup === 'commerce'" :class="{ active: platformGroup === 'commerce' }" @click="selectPlatformGroup('commerce')">电商平台</button>
            <button type="button" role="tab" :aria-selected="platformGroup === 'local'" :class="{ active: platformGroup === 'local' }" @click="selectPlatformGroup('local')">本地商家</button>
          </div>
          <div class="platform-grid">
            <button v-for="platform in visiblePlatforms" :key="platform.name" type="button" class="platform-card" :class="[`platform-${platform.tone}`, { active: selectedPlatform === platform.name }]" @click="selectedPlatform = platform.name"><span class="platform-mark"><img :src="platform.logo" :alt="`${platform.name} 品牌标识`" /><em>{{ platform.mark }}</em></span><strong>{{ platform.name }}</strong></button>
          </div>
        </section>

        <section class="product-form-section">
          <div class="product-section-label"><strong>描述你的画面</strong><small>可选</small></div>
          <textarea v-model="prompt" placeholder="例如：置于夏日茶席，光线清透，突出瓶身与茶汤颜色..." maxlength="240" />
          <div class="product-counter">{{ prompt.length }} / 240</div>
        </section>

        <section class="product-form-section product-options">
          <div class="product-option-row product-ratio-select"><span>图片比例</span><select v-model="selectedRatio" aria-label="选择图片比例"><option v-for="ratio in ['1:1', '3:2', '2:3', '4:3', '3:4', '9:16']" :key="ratio" :value="ratio">{{ ratio }}</option></select></div>
          <div v-if="imageType === 'single'" class="product-fixed-count"><span>生成数量</span><strong>1 张</strong><small>单图模式固定生成 1 张</small></div>
          <label v-else-if="imageType === 'detail'" class="product-detail-count"><span>生成数量</span><span class="product-count-input"><input v-model.number="selectedCount" type="number" min="1" max="10" step="1" aria-label="详情图生成张数" @input="clampDetailCount" /><b>张</b></span><small>详情图最多生成 10 张</small></label>
        </section>
      </div>

      <footer class="product-control-footer">
        <div class="product-save-line"><span>保存到</span><strong>作品库</strong><button type="button" aria-label="修改保存位置">修改</button></div>
        <div class="product-footer-actions"><button type="button" class="product-reset" @click="reset">重置</button><button type="button" class="product-generate" :class="{ ready: uploadedFiles.length }" @click="generate">立即生成 <span>→</span></button></div>
        <p v-if="generated" class="product-generated-note"><i /> 已加入生成队列，可在作品库查看</p>
      </footer>
    </aside>

    <div class="product-showcase">
      <div class="image-workspace-switch video-mode-switch" role="group" aria-label="切换图片工作区"><button type="button" @click="switchWorkspace('/image/create/poster')"><span class="material-symbols-outlined">campaign</span>营销海报</button><button type="button" class="active" aria-pressed="true" @click="switchWorkspace('/image/create/product-set')"><span class="material-symbols-outlined">grid_view</span>产品套图</button></div>
      <div class="product-showcase-glow" />
      <div class="product-showcase-copy"><h2>上传商品，轻松生成商品图</h2></div>
      <div class="product-wall" aria-hidden="true"><div v-for="(column, columnIndex) in wallColumns" :key="columnIndex" class="product-wall-column" :class="`product-wall-column-${columnIndex + 1}`"><img v-for="(image, index) in column" :key="`${columnIndex}-${index}`" :src="image" alt="" /></div></div>
    </div>
  </section>
</template>
