<script setup>
import AppSelect from '../components/AppSelect.vue'
import { computed, nextTick, ref, watch } from 'vue'
import ImageWorkspaceSwitch from '../components/ImageWorkspaceSwitch.vue'
import ImageCreationWorkspace from '../components/ImageCreationWorkspace.vue'
import ImageReferenceList from '../components/ImageReferenceList.vue'
import { ChevronRight, Plus } from 'lucide-vue-next'
import { useImageCreation } from '../composables/useImageCreation'
import { IMAGE_QUALITIES, IMAGE_RATIOS, imageDimensions } from '../domain/imageCreation'
import '../image-workflow.css'

const studio = useImageCreation('PRODUCT_SET')
const fileInput = ref(null)
const workspace = ref(null)
const settingsOpen = ref(false)
// 只在有需要商家知道的情况时显示一行状态；服务正常时不占位置。
const serviceNotice = computed(() => studio.requiresLogin ? '登录后开始创作'
  : studio.capabilityState === 'checking' ? '正在检查创作服务…'
  : studio.capabilityState === 'error' ? `创作服务检查失败：${studio.capabilityError}`
  : !studio.configured ? '图片创作服务尚未接通，可先准备商品照片' : '')
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
    { name: '淘宝', value: 'TAOBAO', logo: '/images/platform-logos/taobao.png' },
    { name: '京东', value: 'JD', logo: '/images/platform-logos/jd.png' },
    { name: '拼多多', value: 'PDD', logo: '/images/platform-logos/pinduoduo.png' },
    { name: '小红书', value: 'XIAOHONGSHU', logo: '/images/platform-logos/xiaohongshu.png' },
  ],
  local: [
    { name: '美团', value: 'MEITUAN', logo: '/images/platform-logos/meituan.png' },
    { name: '淘宝闪购', value: 'TAOBAO_FLASH', logo: '/images/platform-logos/taobao-flash.png' },
    { name: '抖音团购', value: 'DOUYIN_GROUP', logo: '/images/platform-logos/douyin.png' },
    { name: '大众点评', value: 'DIANPING', logo: '/images/platform-logos/dianping.png' },
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
  : [{ value: 'PRODUCT_MAIN', label: '商品主图', count: 1 }, { value: 'DETAIL', label: '详情图', count: 3 }, { value: 'SCENE_SET', label: '场景套图', count: 3 }])
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
</script>

<template>
  <section class="product-set-studio creation-product" aria-label="产品套图工作区">
    <form class="product-control-panel" @submit.prevent="studio.generate">
      <header class="product-panel-head"><h1>产品套图</h1><button type="button" class="product-reset" :disabled="studio.locked" @click="reset">重置</button></header>
      <fieldset class="product-form-scroll" :disabled="studio.locked">
        <section class="product-form-section">
          <span class="product-section-label">商品图</span>
          <div class="product-uploads" @dragover.prevent @drop.prevent="studio.addFiles">
            <ImageReferenceList :files="studio.files" :disabled="studio.locked" @remove="studio.removeFile" @role="(index, role) => studio.files[index].role = role" />
            <label class="product-upload-tile" :class="{ 'is-empty': !studio.files.length }" tabindex="0" :aria-label="studio.files.length ? '添加商品图' : undefined" @keydown.enter.prevent="fileInput.click()" @keydown.space.prevent="fileInput.click()">
              <input ref="fileInput" type="file" accept="image/jpeg,image/png" multiple @change="studio.addFiles" />
              <Plus :size="22" :stroke-width="1.6" aria-hidden="true" /><span v-if="!studio.files.length">上传商品照片</span>
            </label>
          </div>
        </section>
        <section class="product-form-section">
          <span class="product-section-label">平台</span>
          <div class="platform-group-tabs" role="group" aria-label="平台类型"><button type="button" :aria-pressed="platformGroup === 'commerce'" :class="{ active: platformGroup === 'commerce' }" @click="selectPlatformGroup('commerce')">电商平台</button><button type="button" :aria-pressed="platformGroup === 'local'" :class="{ active: platformGroup === 'local' }" @click="selectPlatformGroup('local')">本地商家</button></div>
          <div class="platform-grid"><button v-for="platform in visiblePlatforms" :key="platform.name" type="button" class="platform-card" :class="{ active: selectedPlatform === platform.name }" :aria-pressed="selectedPlatform === platform.name" @click="selectedPlatform = platform.name"><span class="platform-mark"><img :src="platform.logo" alt="" /></span>{{ platform.name }}</button></div>
        </section>
        <section class="product-form-section">
          <span class="product-section-label">图片类型</span>
          <div class="product-type-tabs" role="group" aria-label="图片类型"><button v-for="type in imageTypes" :key="type.value" type="button" :aria-pressed="imageType === type.value" :class="{ active: imageType === type.value }" @click="selectImageType(type.value)">{{ type.label }}</button></div>
        </section>
        <section class="product-form-section">
          <label for="product-brief" class="product-section-label">描述 <small>可选</small></label>
          <textarea id="product-brief" v-model="studio.brief" rows="3" placeholder="例如：这杯杨枝甘露，做一组清爽的夏日商品图" maxlength="2000" />
        </section>
        <section class="product-settings">
          <button type="button" class="product-settings-toggle" :aria-expanded="settingsOpen" aria-controls="product-settings" @click="settingsOpen = !settingsOpen"><span>输出设置</span><small>{{ studio.ratio }} · {{ studio.quality }} · {{ studio.count }} 张</small><ChevronRight :size="15" class="product-settings-chevron" :class="{ open: settingsOpen }" aria-hidden="true" /></button>
          <div v-show="settingsOpen" id="product-settings" class="product-options">
            <label class="product-option-row"><span>比例</span><AppSelect v-model="studio.ratio" aria-label="选择图片比例"><option v-for="ratio in IMAGE_RATIOS" :key="ratio" :disabled="!studio.supportsRatio(ratio)">{{ ratio }}</option></AppSelect></label>
            <div class="product-option-row"><span>画质<small v-if="qualityDimensions(studio.quality).width">{{ qualityDimensions(studio.quality).width }} × {{ qualityDimensions(studio.quality).height }}</small></span><div class="product-quality-grid" role="group" aria-label="输出画质"><button v-for="quality in IMAGE_QUALITIES" :key="quality" type="button" :disabled="!studio.supportsQuality(quality)" :title="qualityDimensions(quality).width ? `${qualityDimensions(quality).width} × ${qualityDimensions(quality).height}` : '当前比例不可用'" :aria-pressed="studio.quality === quality" :class="{ active: studio.quality === quality }" @click="studio.quality = quality">{{ quality }}</button></div></div>
            <label class="product-option-row"><span>数量</span><AppSelect v-model.number="studio.count" aria-label="生成数量"><option v-for="n in 6" :key="n" :value="n">{{ n }} 张</option></AppSelect></label>
            <label class="product-option-row"><span>风格</span><AppSelect v-model="studio.style" aria-label="产品图风格"><option>帮我搭配</option><option>真实自然</option><option>简约高级</option><option>东方雅致</option><option>清爽明亮</option></AppSelect></label>
          </div>
        </section>
      </fieldset>
      <footer class="product-control-footer">
        <p v-if="studio.error" class="creation-item-error" role="alert">{{ studio.error }}</p>
        <p v-if="serviceNotice" class="product-demo-note" role="status">{{ serviceNotice }} <button v-if="studio.capabilityState === 'error'" type="button" class="creation-service-retry" @click="studio.loadCapabilities">重试</button></p>
        <RouterLink v-if="studio.billing.summary && !studio.billing.summary.memberActive" to="/billing" class="generation-billing-link">请先开通会员</RouterLink>
        <button type="submit" class="product-generate" :disabled="studio.busy || (!studio.configured && !studio.requiresLogin) || studio.active || !!studio.pending">{{ studio.busy ? '正在提交…' : studio.active ? '创作中…' : '立即生成' }}</button>
        <p v-if="studio.imageCredits != null" class="generation-billing-note">预占 {{ studio.estimatedCredits }} 烟火，未交付部分退回</p>
      </footer>
    </form>
    <div class="product-showcase">
      <ImageCreationWorkspace ref="workspace" :studio="studio"><template #toolbar-end><ImageWorkspaceSwitch /></template><div class="product-wall" aria-hidden="true"><div v-for="(column, columnIndex) in wallColumns" :key="columnIndex" class="product-wall-column" :class="`product-wall-column-${columnIndex + 1}`"><img v-for="(image, index) in column" :key="`${columnIndex}-${index}`" :src="image" alt="" /></div></div></ImageCreationWorkspace>
    </div>
  </section>
</template>
