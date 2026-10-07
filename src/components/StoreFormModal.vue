<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Dialog, DialogPanel, DialogTitle } from '@headlessui/vue'
import { saveStore } from '../stores/merchantContext.js'
import { brandApi } from '../services/brandApi.js'
import { auth } from '../stores/auth.js'

const props = defineProps({ store: { type: Object, default: null } })
const emit = defineEmits(['close'])
const form = reactive({ name: props.store?.name || '', address: props.store?.address || '', phone: props.store?.phone || '', businessHours: props.store?.businessHours || '' })
const busy = ref(false)
const error = ref('')
// 只有一个品牌（或还没有品牌）时不用选：后端会把门店归到默认品牌。门店归哪个品牌由老板决定，店员看不到这一项。
const brands = ref([])
const brandId = ref(props.store?.brandId ?? null)
onMounted(async () => {
  if (!auth.isOwner) return
  try {
    brands.value = await brandApi.names()
    brandId.value ??= brands.value.find(brand => brand.defaultBrand)?.id ?? null
  } catch { /* 品牌列表取不到时不显示选择，保存也不会改动门店的品牌。 */ }
})
const close = () => { if (!busy.value) emit('close') }
async function submit() {
  if (busy.value) return
  if (!form.name.trim()) { error.value = '请填写门店名称'; return }
  busy.value = true
  error.value = ''
  try {
    const values = Object.fromEntries(Object.entries(form).map(([key, value]) => [key, value.trim()]))
    if (brands.value.length > 1 && brandId.value != null) values.brandId = brandId.value
    await saveStore(values, props.store)
    emit('close')
  } catch (failure) {
    error.value = failure.message || '保存失败，请重试'
  } finally { busy.value = false }
}
</script>

<template>
  <Dialog :open="true" class="store-dialog" @close="close">
    <div class="store-dialog-backdrop" aria-hidden="true" />
    <div class="store-dialog-position">
      <DialogPanel class="store-dialog-panel">
        <header><DialogTitle as="h2">{{ store ? '编辑门店' : '新建门店' }}</DialogTitle><button class="icon-button" aria-label="关闭" :disabled="busy" @click="close">×</button></header>
        <p>商品、知识库与直播配置将归属于对应门店。</p>
        <form @submit.prevent="submit">
          <label>门店名称 <span>*</span><input v-model="form.name" required maxlength="120" placeholder="例如：青岚茶事 · 城西店" :disabled="busy" /></label>
          <label v-if="brands.length > 1">所属品牌<select v-model="brandId" :disabled="busy"><option v-for="brand in brands" :key="brand.id" :value="brand.id">{{ brand.name }}{{ brand.defaultBrand ? '（默认）' : '' }}</option></select></label>
          <label>门店地址<input v-model="form.address" maxlength="300" placeholder="填写详细地址（选填）" :disabled="busy" /></label>
          <div class="store-dialog-row">
            <label>联系电话<input v-model="form.phone" type="tel" maxlength="40" placeholder="选填" :disabled="busy" /></label>
            <label>营业时间<input v-model="form.businessHours" maxlength="120" placeholder="例如：每日 09:00–21:00" :disabled="busy" /></label>
          </div>
          <p v-if="error" role="alert" class="store-dialog-error">{{ error }}</p>
          <footer><button type="button" class="secondary-button compact" :disabled="busy" @click="close">取消</button><button class="primary-button compact" :disabled="busy">{{ busy ? '保存中…' : '保存门店' }}</button></footer>
        </form>
      </DialogPanel>
    </div>
  </Dialog>
</template>

<style scoped>
.store-dialog { position: relative; z-index: 150; }
.store-dialog-backdrop { position: fixed; inset: 0; background: rgb(30 27 23 / 38%); backdrop-filter: blur(3px); }
.store-dialog-position { position: fixed; inset: 0; overflow-y: auto; display: grid; place-items: center; padding: 24px; }
.store-dialog-panel { width: min(100%, 520px); padding: 26px; border: 1px solid var(--border, #e6e2da); border-radius: 18px; background: var(--surface, #fffdf9); color: var(--text, #292622); box-shadow: 0 24px 80px #0002; }
header { display: flex; align-items: center; justify-content: space-between; }
h2 { font-size: 20px; margin: 0; }
.store-dialog-panel > p { color: var(--muted, #77716a); font-size: 13px; margin: 8px 0 24px; }
form { display: grid; gap: 18px; }
label { display: grid; gap: 8px; font-size: 13px; min-width: 0; }
label span { display: contents; color: #b34636; }
input, select { width: 100%; box-sizing: border-box; border: 1px solid var(--border, #ded8ce); border-radius: 8px; padding: 11px 12px; color: inherit; background: var(--surface, #fffdf9); }
select { font: inherit; }
.store-dialog-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
footer { display: flex; justify-content: flex-end; gap: 10px; margin-top: 6px; }
.store-dialog-error { color: #b34636; font-size: 13px; margin: 0; }
@media (max-width: 540px) { .store-dialog-row { grid-template-columns: 1fr; } }
</style>
