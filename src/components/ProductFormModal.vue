<script setup>
import { nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { Dialog, DialogPanel, DialogTitle, DialogDescription } from '@headlessui/vue'
import { X, Package, Ticket, Upload, Pencil, Plus, Trash2 } from 'lucide-vue-next'
import ProductImage from './ProductImage.vue'
import { productApi } from '../services/productApi'
import { emptyProduct, normalizeProduct, validateProduct, productCategories, productTypes, getProductImages } from '../services/products'
import '../products.css'

const props = defineProps({ product: { type: Object, default: null }, error: { type: String, default: '' }, readOnly: { type: Boolean, default: false }, busy: { type: Boolean, default: false } })
const emit = defineEmits(['save', 'close'])
const isReadOnly = ref(props.readOnly)
const form = reactive({ ...emptyProduct(), ...props.product, images: getProductImages(props.product) })
form.faqs = Array.isArray(props.product?.faqs) ? props.product.faqs.map(pair => ({ ...pair })) : []
// Existing free-text categories remain readable and receive a matching dropdown value when edited.
function prepareCategory() {
  if (form.category && !productCategories.includes(form.category)) {
    form.category = { '食品': '食品生鲜', '厨具': '日用百货', '餐饮': '餐饮美食', '美容服务': '丽人美业' }[form.category] || '其他'
  }
}
if (!isReadOnly.value) prepareCategory()
const errors = ref({})
const nameInput = ref(null)
const closeButton = ref(null)
function closeForm() {
  if (!props.busy) emit('close')
}
async function startEditing() {
  if (props.busy) return
  prepareCategory()
  isReadOnly.value = false
  await nextTick()
  nameInput.value?.focus()
}
const formElement = ref(null)
const imageInput = ref(null)
const imageError = ref('')
const imageLoading = ref(false)
let imageRequest = 0
const maxImages = 9
const faqDraft = ref({ question: '', answer: '' })
const faqError = ref('')
onBeforeUnmount(() => { imageRequest++ })
async function chooseImage(event) {
  if (isReadOnly.value || props.busy) return
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  if (!files.length || imageLoading.value) return
  const remaining = maxImages - form.images.length
  if (files.length > remaining) {
    imageError.value = `最多上传 ${maxImages} 张图片，还可添加 ${remaining} 张，请重新选择。`
    return
  }
  const request = ++imageRequest
  imageLoading.value = true
  imageError.value = ''
  try {
    const failures = []
    for (const file of files) {
      if (request !== imageRequest) return
      try {
        const image = await productApi.uploadImage(file)
        if (request === imageRequest) form.images.push(image)
      } catch (error) {
        failures.push(`${file.name}：${error.message}`)
      }
    }
    if (request === imageRequest) imageError.value = failures.join('；')
  } finally {
    if (request === imageRequest) imageLoading.value = false
  }
}
function removeImage(index) {
  if (isReadOnly.value || props.busy || imageLoading.value) return
  form.images.splice(index, 1)
  imageError.value = ''
}
function setCover(index) {
  if (isReadOnly.value || props.busy || imageLoading.value || index === 0) return
  form.images.unshift(...form.images.splice(index, 1))
}
function addProductFaq() {
  if (props.busy || isReadOnly.value) return
  const question = faqDraft.value.question.trim()
  const answer = faqDraft.value.answer.trim()
  if (!question || !answer) {
    faqError.value = '请填写问题和回答后再添加。'
    return
  }
  form.faqs.push({ id: `product-faq-${Date.now()}-${form.faqs.length}`, question, answer, status: 'active' })
  faqDraft.value = { question: '', answer: '' }
  faqError.value = ''
}
function removeProductFaq(index) {
  if (!isReadOnly.value && !props.busy) form.faqs.splice(index, 1)
}
async function submit() {
  if (isReadOnly.value || props.busy || imageLoading.value) return
  errors.value = validateProduct(form)
  if (!Object.keys(errors.value).length) emit('save', normalizeProduct(form))
  else {
    await nextTick()
    formElement.value?.querySelector('[aria-invalid="true"]')?.focus()
  }
}
</script>

<template>
  <Dialog :open="true" :initial-focus="isReadOnly ? closeButton : nameInput" class="product-dialog" @close="closeForm">
    <div class="product-overlay" aria-hidden="true" />
    <div class="product-dialog-position">
      <DialogPanel class="product-modal" :class="{ 'product-modal-readonly': isReadOnly }">
        <header class="product-modal-header">
          <div>
            <DialogTitle>{{ isReadOnly ? '商品详情' : product ? '编辑商品' : '新建商品' }}</DialogTitle>
            <DialogDescription>{{ isReadOnly ? '查看商品资料，点击右上角「编辑」修改。' : '填写真实商品信息，带 * 的项目为必填。' }}</DialogDescription>
          </div>
          <div class="product-modal-header-actions">
            <button v-if="isReadOnly" type="button" class="product-button product-button-primary" @click="startEditing"><Pencil :size="14" />编辑</button>
            <button ref="closeButton" type="button" class="product-icon-button" aria-label="关闭商品表单" :disabled="busy" @click="closeForm"><X :size="18" /></button>
          </div>
        </header>
        <form ref="formElement" novalidate :aria-busy="busy" @submit.prevent="submit">
          <fieldset :disabled="busy" style="display: contents">
            <div class="product-form-body">
              <p v-if="error" class="product-error" role="alert">{{ error }}</p>
              <div class="product-upload" role="group" aria-labelledby="product-image-label">
                <ProductImage :src="form.images[0]" :name="form.name" />
                <div class="product-upload-copy">
                  <p id="product-image-label">商品图片 <span>{{ isReadOnly ? `（${form.images.length} 张）` : `（选填 · ${form.images.length}/${maxImages}）` }}</span></p>
                  <p v-if="!isReadOnly" id="product-image-hint" class="product-upload-hint">支持一次选择多张，最多 {{ maxImages }} 张。建议 1:1 正方形，JPG/PNG，单张不超过 5MB。第一张为主图，未上传将显示首字印章。</p>
                  <p v-else-if="!form.images.length" class="product-upload-hint">暂未上传商品图片</p>
                  <input v-if="!isReadOnly" ref="imageInput" type="file" accept="image/jpeg,image/png" multiple hidden aria-label="选择商品图片" @change="chooseImage">
                  <div v-if="!isReadOnly" class="product-upload-actions">
                    <button type="button" class="product-button" :disabled="imageLoading || form.images.length >= maxImages" aria-describedby="product-image-hint" @click="imageInput?.click()"><Upload :size="14" />{{ imageLoading ? '上传中…' : form.images.length ? '继续添加' : '上传图片' }}</button>
                  </div>
                  <p v-if="imageError" class="product-error" role="alert">{{ imageError }}</p>
                </div>
              </div>
              <ul v-if="form.images.length" class="product-image-gallery" aria-label="已上传商品图片" :aria-busy="imageLoading">
                <li v-for="(image, index) in form.images" :key="index" class="product-image-tile">
                  <ProductImage :src="image" :name="`${form.name || '商品'} · 第 ${index + 1} 张`" />
                  <button v-if="!isReadOnly" type="button" class="product-image-remove" :disabled="imageLoading" :aria-label="`移除第 ${index + 1} 张图片`" @click="removeImage(index)"><X :size="13" /></button>
                  <span v-if="index === 0" class="product-cover-label">主图</span>
                  <button v-else-if="!isReadOnly" type="button" class="product-set-cover" :disabled="imageLoading" :aria-label="`将第 ${index + 1} 张设为主图`" @click="setCover(index)">设为主图</button>
                </li>
              </ul>
              <div v-if="isReadOnly" class="product-detail-fields" aria-label="商品信息">
                <div class="product-detail-hero">
                  <div><span class="product-detail-kicker">{{ form.category }} · {{ productTypes.find(type => type.value === form.type)?.label }}</span><h2>{{ form.name }}</h2></div>
                  <div class="product-detail-price"><span>¥</span><strong>{{ form.price }}</strong><small>/ {{ form.unit }}</small></div>
                </div>
                <dl class="product-detail-grid">
                  <div><dt>商品类型</dt><dd>{{ productTypes.find(type => type.value === form.type)?.label || '—' }}</dd></div>
                  <div><dt>商品分类</dt><dd>{{ form.category || '—' }}</dd></div>
                  <div><dt>商品规格</dt><dd>{{ form.specifications || '—' }}</dd></div>
                  <div><dt>促销与使用规则</dt><dd>{{ form.promotion || '—' }}</dd></div>
                </dl>
                <section class="product-detail-section"><h3>核心卖点</h3><p>{{ form.points || '—' }}</p></section>
              </div>
              <fieldset v-else class="product-form-fields" :disabled="isReadOnly" aria-label="商品信息">
                <label class="product-field">
                  <span>商品名称 <b>*</b></span>
                  <input ref="nameInput" v-model="form.name" maxlength="80" placeholder="例如：手工现磨芝麻丸" :aria-invalid="!!errors.name" aria-describedby="product-name-error">
                  <small v-if="errors.name" id="product-name-error" class="product-error">{{ errors.name }}</small>
                </label>
                <fieldset class="product-type-field">
                  <legend>商品类型 <b>*</b></legend>
                  <div class="product-type-options">
                    <label v-for="type in productTypes" :key="type.value" :class="{ selected: form.type === type.value }"><input v-model="form.type" type="radio" :value="type.value"><component :is="type.value === 'physical' ? Package : Ticket" :size="18" /><span>{{ type.label }}</span></label>
                  </div>
                </fieldset>
                <label class="product-field">
                  <span>分类 <b>*</b></span>
                  <select v-model="form.category" :aria-invalid="!!errors.category" aria-describedby="product-category-error">
                    <option disabled value="">请选择分类</option>
                    <option v-if="isReadOnly && form.category && !productCategories.includes(form.category)" :value="form.category">{{ form.category }}</option>
                    <option v-for="category in productCategories" :key="category" :value="category">{{ category }}</option>
                  </select>
                  <small v-if="errors.category" id="product-category-error" class="product-error">{{ errors.category }}</small>
                </label>
                <div class="product-form-columns">
                  <label class="product-field">
                    <span>售价（元） <b>*</b></span>
                    <input v-model="form.price" type="number" min="0" step="0.01" placeholder="例如：69" :aria-invalid="!!errors.price" aria-describedby="product-price-error">
                    <small v-if="errors.price" id="product-price-error" class="product-error">{{ errors.price }}</small>
                  </label>
                  <label class="product-field">
                    <span>售卖单位 <b>*</b></span>
                    <input v-model="form.unit" maxlength="10" placeholder="例如：罐、张、套、次" :aria-invalid="!!errors.unit" aria-describedby="product-unit-error">
                    <small v-if="errors.unit" id="product-unit-error" class="product-error">{{ errors.unit }}</small>
                  </label>
                </div>
                <label class="product-field"><span>商品规格</span><input v-model="form.specifications" maxlength="300" :placeholder="form.type === 'voucher' ? '例如：双人套餐，含两杯饮品和一份甜点' : '例如：120g / 罐，独立小包装'"></label>
                <label class="product-field"><span>促销与使用规则</span><textarea v-model="form.promotion" rows="2" maxlength="1000" :placeholder="form.type === 'voucher' ? '有效期、预约要求、可用门店、核销方式与优惠限制…' : '包邮条件、优惠组合、限购数量与活动有效期…'" /></label>
                <label class="product-field">
                  <span>核心卖点 <b>*</b></span>
                  <textarea v-model="form.points" rows="3" maxlength="2000" placeholder="写下商品特色、适合人群或服务内容，每行一个卖点。" :aria-invalid="!!errors.points" aria-describedby="product-points-error" />
                  <small v-if="errors.points" id="product-points-error" class="product-error">{{ errors.points }}</small>
                </label>
              </fieldset>
              <section class="product-faq-section" aria-labelledby="product-faq-title">
                <div class="product-faq-heading"><div><h3 id="product-faq-title">商品专属常见问答 <span>（选填）</span></h3><p>配置后，AI 直播及客服会优先使用这些回答。</p></div><span class="product-faq-count">{{ form.faqs.length }} 条</span></div>
                <div v-if="form.faqs.length" class="product-faq-list">
                  <article v-for="(faq, index) in form.faqs" :key="faq.id || index" class="product-faq-card">
                    <div><strong>Q · {{ faq.question }}</strong><p>A · {{ faq.answer }}</p></div>
                    <button v-if="!isReadOnly" type="button" class="product-faq-remove" :aria-label="`删除 ${faq.question}`" @click="removeProductFaq(index)"><Trash2 :size="14" /></button>
                  </article>
                </div>
                <div v-if="isReadOnly && !form.faqs.length" class="product-faq-empty">暂未配置商品专属问答</div>
                <div v-if="!isReadOnly" class="product-faq-add">
                  <input v-model="faqDraft.question" type="text" aria-label="商品专属问题" maxlength="1000" placeholder="例如：保质期多久？">
                  <input v-model="faqDraft.answer" type="text" aria-label="商品专属回答" maxlength="4000" placeholder="例如：常温密封保质期 90 天。" @keyup.enter="addProductFaq">
                  <button type="button" class="product-button" @click="addProductFaq"><Plus :size="14" />手动添加</button>
                </div>
                <p v-if="faqError" class="product-error">{{ faqError }}</p>
              </section>
            </div>
            <footer class="product-modal-footer">
              <button type="button" class="product-button" :disabled="busy" @click="closeForm">{{ isReadOnly ? '关闭' : '取消' }}</button>
              <button v-if="!isReadOnly" type="submit" class="product-button product-button-primary" :disabled="imageLoading || busy">{{ busy ? '保存中…' : product ? '保存修改' : '创建商品' }}</button>
            </footer>
          </fieldset>
        </form>
      </DialogPanel>
    </div>
  </Dialog>
</template>
