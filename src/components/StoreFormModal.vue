<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Dialog, DialogPanel, DialogTitle } from '@headlessui/vue'
import { saveStore } from '../stores/merchantContext.js'
import { brandApi } from '../services/brandApi.js'
import { auth } from '../stores/auth.js'
import { AMENITY_PRESETS, AMENITY_MAX_LENGTH, SPECIAL_HOUR_MAX_COUNT, WEEKDAYS, addAmenity, blankSpecialHour, describeSpecialHour, profileForm, profilePayload } from '../domain/storeProfile.js'

// full：除了四项基本资料，还编辑门店档案（交通指引、配套服务、特殊营业安排）。
// 不带 full 的是"先建一家店"的快捷表单，它不提交档案，已有的档案也不会被它改动。
const props = defineProps({ store: { type: Object, default: null }, full: { type: Boolean, default: false } })
const emit = defineEmits(['close'])
const form = reactive({ name: props.store?.name || '', address: props.store?.address || '', phone: props.store?.phone || '', businessHours: props.store?.businessHours || '' })
const profile = reactive(profileForm(props.store))
const customAmenity = ref('')
const busy = ref(false)
const error = ref('')
// 只有一个品牌（或还没有品牌）时不用选：后端会把门店归到默认品牌。门店归哪个品牌由管理员决定，店员看不到这一项。
const brands = ref([])
const brandId = ref(props.store?.brandId ?? null)
onMounted(async () => {
  if (!auth.isOwner) return
  try {
    brands.value = await brandApi.names()
    brandId.value ??= brands.value.find(brand => brand.defaultBrand)?.id ?? null
  } catch { /* 品牌列表取不到时不显示选择，保存也不会改动门店的品牌。 */ }
})

// 预设之外自己加的配套服务，同样以可取消的标签显示。
const amenityChoices = computed(() => [...AMENITY_PRESETS, ...profile.amenities.filter(item => !AMENITY_PRESETS.includes(item))])
function toggleAmenity(name) {
  profile.amenities = profile.amenities.includes(name) ? profile.amenities.filter(item => item !== name) : addAmenity(profile.amenities, name)
}
function addCustomAmenity() {
  profile.amenities = addAmenity(profile.amenities, customAmenity.value)
  customAmenity.value = ''
}
function addRule() { if (profile.specialHours.length < SPECIAL_HOUR_MAX_COUNT) profile.specialHours.push(blankSpecialHour()) }
function removeRule(index) { profile.specialHours.splice(index, 1) }

const close = () => { if (!busy.value) emit('close') }
async function submit() {
  if (busy.value) return
  if (!form.name.trim()) { error.value = '请填写门店名称'; return }
  busy.value = true
  error.value = ''
  try {
    const values = Object.fromEntries(Object.entries(form).map(([key, value]) => [key, value.trim()]))
    if (brands.value.length > 1 && brandId.value != null) values.brandId = brandId.value
    if (props.full) Object.assign(values, profilePayload(profile))
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
      <DialogPanel class="store-dialog-panel" :class="{ wide: full }">
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

          <template v-if="full">
            <div class="profile-heading">
              <h3>门店档案</h3>
              <small>直播时观众问到在哪、怎么去、哪天休息、能不能停车，会按这里和上面的内容回答。都是选填。</small>
            </div>

            <label>交通指引<textarea v-model="profile.transportGuide" rows="2" maxlength="500" placeholder="例如：地铁 10 号线文三路站 B 口出站，沿文三路向东约 50 米。" :disabled="busy" /></label>

            <fieldset>
              <legend>配套服务</legend>
              <div class="amenity-list">
                <button v-for="amenity in amenityChoices" :key="amenity" type="button" class="amenity-chip" :class="{ selected: profile.amenities.includes(amenity) }" :aria-pressed="profile.amenities.includes(amenity)" :disabled="busy" @click="toggleAmenity(amenity)">{{ amenity }}</button>
              </div>
              <div class="amenity-add">
                <input v-model="customAmenity" :maxlength="AMENITY_MAX_LENGTH" placeholder="其他，例如：儿童座椅" aria-label="添加其他配套服务" :disabled="busy" @keydown.enter.prevent="addCustomAmenity" />
                <button type="button" class="secondary-button compact" :disabled="busy || !customAmenity.trim()" @click="addCustomAmenity">添加</button>
              </div>
            </fieldset>

            <fieldset>
              <legend>特殊营业安排</legend>
              <small class="field-hint">固定店休、节假日休息或某一天调整营业时间。不会改变上面的常规营业时间。</small>
              <div v-for="(rule, index) in profile.specialHours" :key="index" class="rule">
                <div class="rule-controls">
                  <select v-model="rule.scope" aria-label="安排范围" :disabled="busy"><option value="WEEKLY">每周</option><option value="DATE">指定日期</option></select>
                  <select v-if="rule.scope === 'WEEKLY'" v-model.number="rule.weekday" aria-label="星期几" :disabled="busy"><option v-for="(day, at) in WEEKDAYS" :key="day" :value="at + 1">{{ day }}</option></select>
                  <input v-else v-model="rule.date" type="date" aria-label="日期" :disabled="busy" />
                  <select :value="rule.closed ? 'closed' : 'open'" aria-label="安排类型" :disabled="busy" @change="rule.closed = $event.target.value === 'closed'"><option value="closed">休息</option><option value="open">调整营业时间</option></select>
                  <button type="button" class="rule-remove" aria-label="删除这条安排" :disabled="busy" @click="removeRule(index)">×</button>
                </div>
                <div v-if="!rule.closed" class="rule-time"><input v-model="rule.opensAt" type="time" aria-label="开始营业" :disabled="busy" /><span>至</span><input v-model="rule.closesAt" type="time" aria-label="结束营业" :disabled="busy" /></div>
                <input v-model="rule.note" maxlength="60" placeholder="备注（选填），例如：国庆假期" aria-label="备注" :disabled="busy" />
                <small class="rule-preview">{{ describeSpecialHour(rule) }}</small>
              </div>
              <button v-if="profile.specialHours.length < SPECIAL_HOUR_MAX_COUNT" type="button" class="rule-add" :disabled="busy" @click="addRule">＋ 添加一条安排</button>
            </fieldset>
          </template>

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
.store-dialog-panel.wide { width: min(100%, 640px); }
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

.profile-heading { display: grid; gap: 6px; margin-top: 6px; padding-top: 20px; border-top: 1px solid var(--border, #e6e2da); }
.profile-heading h3 { margin: 0; font-size: 15px; }
.profile-heading small, .field-hint { color: var(--muted, #77716a); font-size: 12px; line-height: 1.6; }
textarea { width: 100%; box-sizing: border-box; border: 1px solid var(--border, #ded8ce); border-radius: 8px; padding: 11px 12px; color: inherit; background: var(--surface, #fffdf9); font: inherit; line-height: 1.6; resize: vertical; }
fieldset { display: grid; gap: 10px; min-width: 0; margin: 0; padding: 0; border: 0; }
legend { margin-bottom: 8px; padding: 0; font-size: 13px; }
.amenity-list { display: flex; flex-wrap: wrap; gap: 8px; }
.amenity-chip { padding: 7px 12px; border: 1px solid var(--border, #ded8ce); border-radius: 999px; color: inherit; background: transparent; font-size: 12px; cursor: pointer; }
.amenity-chip.selected { border-color: var(--color-accent, #c84a3d); color: var(--color-accent-text, #b43f33); background: color-mix(in srgb, var(--color-accent, #c84a3d) 7%, transparent); font-weight: 600; }
.amenity-add { display: grid; grid-template-columns: 1fr auto; gap: 8px; }
.rule { display: grid; gap: 8px; padding: 12px; border: 1px solid var(--border, #e6e2da); border-radius: 10px; }
.rule-controls { display: grid; grid-template-columns: 1fr 1fr 1.2fr auto; gap: 8px; align-items: center; }
.rule-time { display: grid; grid-template-columns: 1fr auto 1fr; gap: 8px; align-items: center; font-size: 12px; color: var(--muted, #77716a); }
.rule-remove { width: 34px; height: 34px; border: 0; border-radius: 8px; color: var(--muted, #77716a); background: transparent; font-size: 18px; cursor: pointer; }
.rule-remove:hover { background: color-mix(in srgb, currentColor 8%, transparent); }
.rule-preview { color: var(--muted, #77716a); font-size: 12px; }
.rule-add { justify-self: start; padding: 8px 0; border: 0; color: var(--color-accent-text, #b43f33); background: transparent; font-size: 13px; font-weight: 600; cursor: pointer; }
button:disabled { cursor: default; opacity: .6; }
@media (max-width: 540px) { .store-dialog-row { grid-template-columns: 1fr; } .rule-controls { grid-template-columns: 1fr 1fr; } }
</style>
