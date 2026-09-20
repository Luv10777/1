<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

// Mock 数据：真实项目中可替换为 merchantService / storeService 返回值
const regions = {
  浙江省: { 杭州市: ['西湖区', '拱墅区', '上城区'], 宁波市: ['海曙区', '鄞州区'] },
  上海市: { 上海市: ['黄浦区', '静安区', '徐汇区'] },
  江苏省: { 南京市: ['鼓楼区', '玄武区'], 苏州市: ['姑苏区', '工业园区'] },
}

const statusOptions = [
  { value: 'building', label: '筹建中', tone: 'amber', hint: '暂不对外展示' },
  { value: 'active', label: '正常营业', tone: 'green', hint: '对外正常展示' },
  { value: 'paused', label: '暂停营业', tone: 'orange', hint: '暂时停止接待' },
  { value: 'closed', label: '已闭店', tone: 'slate', hint: '不再提供服务' },
]

const amenityOptions = ['免费停车', 'Wi-Fi', '包间', '宠物友好', '充电宝', '无障碍通道', '可开发票', '支持预约']
const sectionLinks = [
  { id: 'identity', no: '01', label: '门店标识' },
  { id: 'location', no: '02', label: '位置信息' },
  { id: 'operations', no: '03', label: '运营数据' },
  { id: 'special-hours', no: '04', label: '特殊营业安排' },
  { id: 'infrastructure', no: '05', label: '基础设施' },
  { id: 'visual-assets', no: '06', label: '视觉资产' },
]
const form = ref({
  branchName: '杭州城西店',
  storeCode: 'HZ-XC-001',
  status: 'active',
  province: '浙江省',
  city: '杭州市',
  district: '西湖区',
  address: '文三路 90 号东部软件园 1 号楼一层',
  transport: '地铁 10 号线文三路站 B 口出站，沿文三路向东约 50 米。',
  phone: '0571-88888888',
  openingTime: '10:00',
  closingTime: '22:00',
  manager: '林知夏',
  amenities: ['免费停车', 'Wi-Fi', '支持预约'],
})
const specialHours = ref([{ scope: 'weekly', weekday: '周一', date: '', type: '休息', openingTime: '', closingTime: '', note: '每周固定店休' }])

const storefront = ref(null)
const environmentPhotos = ref([])
const fileInput = ref(null)
const environmentInput = ref(null)
const errors = ref({})
const notice = ref('')
const isDragging = ref(false)
const activeSection = ref('identity')
const cardRefs = ref([])
let sectionObserver
let scrollFrame

// 只在本页面激活浅色画布，离开页面后恢复全局工作台背景
onMounted(() => document.querySelector('.page-scroll')?.classList.add('store-info-scroll'))
onMounted(async () => {
  await nextTick()
  const root = document.querySelector('.page-scroll')
  sectionObserver = new IntersectionObserver((entries) => {
    const visible = entries.filter(entry => entry.isIntersecting).sort((a, b) => b.intersectionRatio - a.intersectionRatio)
    if (visible[0]?.target?.id) activeSection.value = visible[0].target.id
  }, { root, rootMargin: '-12% 0px -62% 0px', threshold: [0.1, 0.35, 0.7] })
  cardRefs.value.forEach(card => sectionObserver.observe(card))
})
onBeforeUnmount(() => {
  sectionObserver?.disconnect()
  if (scrollFrame) cancelAnimationFrame(scrollFrame)
  document.querySelector('.page-scroll')?.classList.remove('store-info-scroll')
})

const cities = computed(() => Object.keys(regions[form.value.province] || {}))
const districts = computed(() => regions[form.value.province]?.[form.value.city] || [])
const completion = computed(() => {
  const checks = [form.value.branchName, form.value.status, form.value.province && form.value.city && form.value.district, form.value.address, form.value.phone, form.value.openingTime && form.value.closingTime, storefront.value]
  return Math.round((checks.filter(Boolean).length / checks.length) * 100)
})

const onProvinceChange = () => {
  form.value.city = cities.value[0] || ''
  form.value.district = districts.value[0] || ''
}

const onCityChange = () => { form.value.district = districts.value[0] || '' }

const addSpecialHour = () => specialHours.value.push({ scope: 'specific', weekday: '周一', date: '', type: '临时闭店', openingTime: '10:00', closingTime: '22:00', note: '' })
const removeSpecialHour = (index) => specialHours.value.splice(index, 1)

// 使用自定义 easeInOut 动画，避免浏览器原生锚点跳转过于生硬
const scrollToSection = (id, event) => {
  event?.preventDefault()
  const root = document.querySelector('.page-scroll')
  const target = document.getElementById(id)
  if (!root || !target) return
  activeSection.value = id
  window.history.replaceState(null, '', `#${id}`)
  if (scrollFrame) cancelAnimationFrame(scrollFrame)
  const rootRect = root.getBoundingClientRect()
  const targetRect = target.getBoundingClientRect()
  const start = root.scrollTop
  const destination = Math.max(0, start + targetRect.top - rootRect.top - 20)
  const distance = destination - start
  const duration = Math.min(760, Math.max(420, Math.abs(distance) * 0.55))
  const startedAt = performance.now()
  const easeInOut = (progress) => progress < 0.5
    ? 4 * progress * progress * progress
    : 1 - Math.pow(-2 * progress + 2, 3) / 2
  const frame = (now) => {
    const progress = Math.min(1, (now - startedAt) / duration)
    root.scrollTop = start + distance * easeInOut(progress)
    if (progress < 1) scrollFrame = requestAnimationFrame(frame)
  }
  scrollFrame = requestAnimationFrame(frame)
}

const readImage = (file) => {
  if (!file || !file.type.startsWith('image/')) return null
  return { name: file.name, url: URL.createObjectURL(file) }
}

const handleStorefront = (event) => {
  const image = readImage(event.target.files?.[0])
  if (image) storefront.value = image
  event.target.value = ''
}

const handleDrop = (event) => {
  isDragging.value = false
  const image = readImage(event.dataTransfer.files?.[0])
  if (image) storefront.value = image
}

const handleEnvironment = (event) => {
  const remaining = 5 - environmentPhotos.value.length
  const images = [...(event.target.files || [])].slice(0, remaining).map(readImage).filter(Boolean)
  environmentPhotos.value = [...environmentPhotos.value, ...images]
  event.target.value = ''
}

const removeEnvironment = (index) => {
  const [removed] = environmentPhotos.value.splice(index, 1)
  if (removed?.url) URL.revokeObjectURL(removed.url)
}

const validate = () => {
  const nextErrors = {}
  if (!form.value.branchName.trim()) nextErrors.branchName = '请填写分店名称'
  if (!form.value.province || !form.value.city || !form.value.district) nextErrors.region = '请选择完整的省 / 市 / 区'
  if (!form.value.address.trim()) nextErrors.address = '请填写详细地址'
  if (!/^1\d{10}|0\d{2,3}-?\d{7,8}$/.test(form.value.phone.trim())) nextErrors.phone = '请输入有效的手机号或座机号'
  if (!form.value.openingTime || !form.value.closingTime) nextErrors.hours = '请设置营业时间'
  if (!storefront.value) nextErrors.storefront = '请上传门头照'
  errors.value = nextErrors
  return Object.keys(nextErrors).length === 0
}

const save = () => {
  notice.value = ''
  if (!validate()) {
    notice.value = '还有必填信息未完成，请检查标记项。'
    return
  }
  notice.value = '门店信息已保存，资料将在审核通过后同步至消费者端。'
}

const reset = () => {
  errors.value = {}
  notice.value = ''
  form.value = { ...form.value, branchName: '', storeCode: '', status: 'building', address: '', transport: '', phone: '', openingTime: '09:00', closingTime: '21:00', manager: '', amenities: [] }
  storefront.value = null
  environmentPhotos.value = []
}
</script>

<template>
  <div class="store-info-page">
    <header class="store-info-header">
      <div>
        <h1>门店信息管理</h1>
        <p class="store-info-intro">维护可被消费者、运营和 AI 准确引用的门店基础事实。</p>
      </div>
    </header>

    <div class="store-info-layout">
      <form class="store-form" @submit.prevent="save">
        <section id="identity" :ref="el => { cardRefs[0] = el }" class="form-card" style="--index:0">
          <div class="card-heading"><div><div><p class="eyebrow">STORE IDENTITY</p><h2>门店标识</h2></div></div></div>
          <div class="field-grid two-col">
            <label class="field"><span class="field-label">分店名称 <b>*</b></span><input v-model="form.branchName" :class="{ invalid: errors.branchName }" placeholder="例如：杭州城西店" /><small v-if="errors.branchName" class="field-error">{{ errors.branchName }}</small></label>
            <label class="field"><span class="field-label">门店编号</span><input v-model="form.storeCode" placeholder="例如：SH-001" /></label>
          </div>
          <fieldset class="field status-field"><legend class="field-label">营业状态 <b>*</b></legend><div class="status-options"><label v-for="option in statusOptions" :key="option.value" class="status-option" :class="[`tone-${option.tone}`, { selected: form.status === option.value }]" :title="option.hint"><input v-model="form.status" type="radio" name="status" :value="option.value" /><span class="status-check" /><span>{{ option.label }}</span></label></div></fieldset>
        </section>

        <section id="location" :ref="el => { cardRefs[1] = el }" class="form-card" style="--index:1">
          <div class="card-heading"><div><div><p class="eyebrow">LOCATION INFO</p><h2>位置信息</h2></div></div></div>
          <div class="field"><span class="field-label">所在城市 / 区域 <b>*</b></span><div class="region-grid"><select v-model="form.province" @change="onProvinceChange"><option v-for="province in Object.keys(regions)" :key="province">{{ province }}</option></select><select v-model="form.city" @change="onCityChange"><option v-for="city in cities" :key="city">{{ city }}</option></select><select v-model="form.district"><option v-for="district in districts" :key="district">{{ district }}</option></select></div><small v-if="errors.region" class="field-error">{{ errors.region }}</small></div>
          <label class="field"><span class="field-label">详细地址 <b>*</b></span><input v-model="form.address" :class="{ invalid: errors.address }" placeholder="请输入街道、门牌号、楼层等详细信息" /><small v-if="errors.address" class="field-error">{{ errors.address }}</small></label>
          <label class="field"><span class="field-label">交通指引</span><textarea v-model="form.transport" rows="3" placeholder="例如：地铁10号线B口出右转50米，商场东门旁。" /></label>
        </section>

        <section id="operations" :ref="el => { cardRefs[2] = el }" class="form-card" style="--index:2">
          <div class="card-heading"><div><div><p class="eyebrow">OPERATIONS DATA</p><h2>运营数据</h2></div></div></div>
          <div class="field-grid two-col"><label class="field"><span class="field-label">联系电话 <b>*</b></span><input v-model="form.phone" :class="{ invalid: errors.phone }" type="tel" placeholder="请输入门店电话" /><small v-if="errors.phone" class="field-error">{{ errors.phone }}</small></label><label class="field"><span class="field-label">门店负责人</span><input v-model="form.manager" placeholder="例如：张三" /></label></div>
          <div class="field"><span class="field-label">常规营业时间 <b>*</b></span><div class="hours-row"><input v-model="form.openingTime" type="time" /><span>至</span><input v-model="form.closingTime" type="time" /></div><small v-if="errors.hours" class="field-error">{{ errors.hours }}</small></div>
        </section>

        <section id="special-hours" :ref="el => { cardRefs[3] = el }" class="form-card" style="--index:3">
          <div class="card-heading"><div><div><p class="eyebrow">SPECIAL HOURS</p><h2>特殊营业安排</h2></div></div></div>
          <p class="section-helper">用于记录固定店休、节假日营业和临时闭店，不会改变常规营业时间。</p>
          <div class="special-hours-list">
            <div v-for="(rule, index) in specialHours" :key="index" class="special-hour-row">
              <div class="special-hour-main">
                <select v-model="rule.scope" aria-label="安排范围"><option value="weekly">每周</option><option value="specific">指定日期</option></select>
                <select v-if="rule.scope === 'weekly'" v-model="rule.weekday" aria-label="每周日期"><option v-for="day in ['周一','周二','周三','周四','周五','周六','周日']" :key="day">{{ day }}</option></select>
                <input v-else v-model="rule.date" type="date" aria-label="指定日期" />
                <select v-model="rule.type" aria-label="安排类型"><option>休息</option><option>临时闭店</option><option>调整营业时间</option></select>
              </div>
              <div v-if="rule.type === '调整营业时间'" class="special-hour-time"><input v-model="rule.openingTime" type="time" /><span>至</span><input v-model="rule.closingTime" type="time" /></div>
              <input v-model="rule.note" class="special-hour-note" placeholder="备注，例如：节假日安排" />
              <button v-if="specialHours.length > 1" class="remove-rule" type="button" aria-label="删除安排" @click="removeSpecialHour(index)">×</button>
            </div>
          </div>
          <button class="add-rule" type="button" @click="addSpecialHour"><span>＋</span> 添加特殊安排</button>
        </section>

        <section id="infrastructure" :ref="el => { cardRefs[4] = el }" class="form-card" style="--index:4">
          <div class="card-heading"><div><div><p class="eyebrow">INFRASTRUCTURE</p><h2>基础设施</h2></div></div></div>
          <div class="field"><span class="field-label">配套服务 <em>可多选</em></span><div class="amenity-list"><label v-for="amenity in amenityOptions" :key="amenity" class="amenity-chip" :class="{ selected: form.amenities.includes(amenity) }"><input v-model="form.amenities" type="checkbox" :value="amenity" /><span class="chip-check">✓</span>{{ amenity }}</label></div></div>
        </section>

        <section id="visual-assets" :ref="el => { cardRefs[5] = el }" class="form-card" style="--index:5">
          <div class="card-heading"><div><div><p class="eyebrow">VISUAL ASSETS</p><h2>视觉资产</h2></div></div></div>
          <div class="field"><span class="field-label">门头照 <b>*</b></span><div class="upload-drop storefront-drop" :class="{ dragging: isDragging, filled: storefront, invalid: errors.storefront }" @click="fileInput?.click()" @dragover.prevent="isDragging = true" @dragleave="isDragging = false" @drop.prevent="handleDrop"><input ref="fileInput" type="file" accept="image/*" hidden @change="handleStorefront" /><img v-if="storefront" :src="storefront.url" alt="门头照预览" /><template v-else><span class="upload-icon material-symbols-outlined">add_a_photo</span><strong>拖拽图片至此，或点击上传</strong><small>建议尺寸比例 16:9，用于平台封面展示</small><small class="upload-limit">PNG / JPG · 最大 10 MB</small></template></div><small v-if="errors.storefront" class="field-error">{{ errors.storefront }}</small></div>
          <div class="field environment-field"><div class="field-label-row"><span class="field-label">门店环境图 <em>最多 5 张</em></span></div><input ref="environmentInput" type="file" accept="image/*" multiple hidden @change="handleEnvironment" /><div class="environment-grid"><button v-for="(photo, index) in environmentPhotos" :key="photo.url" class="environment-photo" type="button" @click="removeEnvironment(index)"><img :src="photo.url" :alt="`环境图 ${index + 1}`" /><span>移除</span></button><button v-if="environmentPhotos.length < 5" class="environment-add" type="button" @click="environmentInput?.click()"><span>＋</span><small>上传环境图</small></button></div></div>
        </section>

        <div class="form-footer"><p v-if="notice" class="form-notice" :class="{ success: !Object.keys(errors).length }"><span class="material-symbols-outlined">{{ Object.keys(errors).length ? 'info' : 'check_circle' }}</span>{{ notice }}</p><div class="form-actions"><button class="ghost-button" type="button" @click="reset">重置</button><button class="primary-button" type="submit">保存门店信息 <span>→</span></button></div></div>
      </form>

      <aside class="store-info-aside">
        <div class="completion-card">
          <div class="completion-top"><div><strong>资料完成度</strong></div><b>{{ completion }}%</b></div>
          <div class="completion-track"><span :style="{ width: `${completion}%` }" /></div>
          <div class="completion-foot"><span>{{ completion === 100 ? '已满足发布条件' : '完成必填项后即可提交' }}</span><span class="completion-state"><i />{{ completion === 100 ? '可提交' : '完善中' }}</span></div>
        </div>
        <nav class="anchor-nav" aria-label="表单分区">
          <a v-for="item in sectionLinks" :key="item.id" :href="`#${item.id}`" :class="{ active: activeSection === item.id }" @click="scrollToSection(item.id, $event)"><span>{{ item.no }}</span><strong>{{ item.label }}</strong><i /></a>
        </nav>
        <div class="aside-note"><span class="material-symbols-outlined">verified_user</span><div><strong>真实事实优先</strong><p>这里的信息会成为 AI 生成内容和门店问答的可信来源。</p></div></div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
:global(.page-scroll.store-info-scroll){background:var(--color-bg-canvas);color:var(--color-primary)}
.store-info-page{max-width:1180px;margin:0 auto;padding:42px 42px 80px;font-family:var(--font-sans)}.store-info-header{display:flex;align-items:flex-end;justify-content:space-between;gap:32px;margin-bottom:30px}.store-info-header h1{margin:9px 0 8px;color:var(--color-primary);font:800 32px/1.15 var(--font-serif);letter-spacing:.01em}.store-info-intro{margin:0;color:var(--color-text-muted);font-size:13px}.header-meta{display:flex;align-items:center;gap:8px;padding-bottom:5px;color:var(--color-text-muted);font-size:11px;white-space:nowrap}.sync-dot{width:7px;height:7px;border-radius:50%;background:var(--color-bg-surface);box-shadow:var(--shadow-paper)}.meta-divider{width:1px;height:14px;background:var(--color-bg-subtle)}.mono{font-family:var(--font-mono);font-size:10px;letter-spacing:.04em}.store-info-layout{display:grid;grid-template-columns:215px minmax(0,1fr);gap:28px;align-items:start}.store-info-aside{position:sticky;top:25px}.completion-card{padding:16px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface);box-shadow:var(--shadow-paper)}.completion-top{display:flex;justify-content:space-between;align-items:center}.completion-top strong{color:var(--color-text-muted);font:800 18px var(--font-sans)}.completion-track{height:6px;margin:12px 0 9px;border-radius:8px;background:var(--color-bg-subtle);overflow:hidden}.completion-track span{display:block;height:100%;border-radius:inherit;background:var(--color-accent);transition:width .25s ease}.completion-card p{margin:0;color:var(--color-text-muted);font-size:11px;line-height:1.55}.anchor-nav{display:grid;gap:3px;margin:20px 0}.anchor-nav a{display:flex;align-items:center;gap:11px;padding:10px 11px;border-radius:8px;color:var(--color-text-muted);font-size:12px;transition:.15s}.anchor-nav a:hover{color:var(--color-text-muted);background:var(--color-bg-subtle)}.anchor-nav a span{color:var(--color-text-muted);font:500 10px var(--font-mono)}.aside-note{display:flex;gap:9px;padding:13px 11px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface);color:var(--color-text-muted)}.aside-note .material-symbols-outlined{font-size:18px}.aside-note strong{font-size:11px}.aside-note p{margin:4px 0 0;color:var(--color-text-muted);font-size:10px;line-height:1.5}.store-form{display:grid;gap:16px}.form-card{padding:25px 28px 28px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface);box-shadow:var(--shadow-paper)}.card-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:24px}.card-heading>div{display:flex;align-items:center;gap:12px}.step-number{display:grid;place-items:center;width:29px;height:29px;border-radius:8px;background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface));color:var(--color-accent-text);font:700 11px var(--font-mono)}.card-heading h2{margin:4px 0 0;color:var(--color-primary);font:700 18px/1.2 var(--font-serif)}.card-caption{color:var(--color-text-muted);font-size:11px}.field-grid{display:grid;gap:18px}.two-col{grid-template-columns:1fr 1fr}.field{display:grid;gap:8px;margin-bottom:18px;border:0;padding:0}.field:last-child{margin-bottom:0}.field-label{display:flex;align-items:center;gap:6px;color:var(--color-primary);font-size:12px;font-weight:600}.field-label b{color:var(--color-text-muted);font-weight:700}.field-label em{color:var(--color-text-muted);font-size:10px;font-style:normal;font-weight:400}.field input,.field select,.field textarea{width:100%;padding:11px 12px;border:1px solid var(--color-border-subtle);border-radius:4px;outline:none;background:var(--color-bg-surface);color:var(--color-primary);font-size:12px;transition:border-color .15s,box-shadow .15s}.field input::placeholder,.field textarea::placeholder{color:var(--color-text-muted)}.field textarea{resize:vertical;line-height:1.6}.field input:focus,.field select:focus,.field textarea:focus{border-color:var(--color-accent);box-shadow:var(--shadow-paper)}.field input.invalid,.field select.invalid{border-color:var(--color-error)}.field-error{color:var(--color-error);font-size:10px}.status-field{margin-top:22px}.status-options{display:grid;grid-template-columns:repeat(4,1fr);gap:9px}.status-option{display:flex;align-items:center;gap:7px;padding:10px 11px;border:1px solid var(--color-border-subtle);border-radius:4px;color:var(--color-text-muted);font-size:11px;cursor:pointer;transition:.15s}.status-option input{position:absolute;opacity:0}.status-check{width:8px;height:8px;border-radius:50%;background:var(--color-bg-surface)}.status-option.selected{border-color:currentColor;background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}.status-option.selected .status-check{box-shadow:var(--shadow-paper)}.tone-green{color:var(--color-success)}.tone-amber{color:var(--color-warning)}.tone-orange{color:var(--color-warning)}.tone-slate{color:var(--color-text-muted)}.region-grid{display:grid;grid-template-columns:1fr 1fr 1fr;gap:9px}.region-grid select{appearance:auto}.hours-row{display:flex;align-items:center;gap:10px}.hours-row input{max-width:150px}.hours-row>span{color:var(--color-text-muted);font-size:12px}.hours-hint{margin-left:4px;color:var(--color-text-muted);font-size:10px!important}.amenity-list{display:flex;flex-wrap:wrap;gap:8px}.amenity-chip{display:inline-flex;align-items:center;gap:6px;padding:9px 11px;border:1px solid var(--color-border-subtle);border-radius:4px;color:var(--color-text-muted);font-size:11px;cursor:pointer;transition:.15s}.amenity-chip input{position:absolute;opacity:0;width:1px}.amenity-chip.selected{border-color:var(--color-accent);background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface));color:var(--color-accent-text)}.chip-check{display:grid;place-items:center;width:14px;height:14px;border:1px solid var(--color-accent);border-radius:4px;color:transparent;font-size:9px}.selected .chip-check{border-color:var(--color-accent);background:var(--color-accent);color:var(--color-on-accent)}.field-label-row{display:flex;align-items:center;justify-content:space-between}.text-button{border:0;background:none;color:var(--color-accent-text);font-size:11px;cursor:pointer}.upload-drop{position:relative;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:184px;border:1px dashed var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface);color:var(--color-text-muted);text-align:center;cursor:pointer;transition:.15s;overflow:hidden}.upload-drop:hover,.upload-drop.dragging{border-color:var(--color-border-subtle);background:var(--color-bg-subtle)}.upload-drop.filled{border-style:solid;border-color:var(--color-border-subtle);padding:0}.upload-drop img{width:100%;height:100%;min-height:184px;object-fit:cover}.upload-icon{margin-bottom:8px;color:var(--color-accent-text);font-size:27px}.upload-drop strong{color:var(--color-primary);font-size:12px}.upload-drop small{margin-top:6px;color:var(--color-text-muted);font-size:10px}.upload-drop .upload-limit{margin-top:12px;color:var(--color-text-muted);font-family:var(--font-mono);font-size:9px}.environment-field{margin-top:22px}.environment-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:9px}.environment-photo,.environment-add{position:relative;aspect-ratio:1;border-radius:8px;overflow:hidden}.environment-photo{padding:0;border:1px solid var(--color-border-subtle);background:var(--color-bg-surface);cursor:pointer}.environment-photo img{width:100%;height:100%;object-fit:cover}.environment-photo span{position:absolute;inset:auto 0 0;padding:5px;background:var(--color-bg-surface);color:var(--color-text-muted);font-size:10px;opacity:0;transition:.15s}.environment-photo:hover span{opacity:1}.environment-add{display:grid;place-items:center;align-content:center;gap:4px;border:1px dashed var(--color-border-subtle);background:var(--color-bg-surface);color:var(--color-text-muted);cursor:pointer}.environment-add span{color:var(--color-text-muted);font-size:21px}.environment-add small{font-size:10px}.form-footer{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:8px 2px}.form-notice{display:flex;align-items:center;gap:7px;margin:0;color:var(--color-error);font-size:11px}.form-notice.success{color:var(--color-success)}.form-notice .material-symbols-outlined{font-size:17px}.form-actions{display:flex;align-items:center;gap:10px}.ghost-button,.primary-button{padding:11px 17px;border-radius:4px;font-size:12px;cursor:pointer}.ghost-button{border:1px solid var(--color-border-subtle);background:var(--color-bg-surface);color:var(--color-text-muted)}.ghost-button:hover{background:var(--color-bg-subtle)}.primary-button{border:1px solid var(--color-accent);background:var(--color-accent);color:var(--color-on-accent);box-shadow:none}.primary-button:hover{background:var(--color-accent-hover)}.primary-button span{margin-left:8px;font-size:15px}.eyebrow{margin:0;color:var(--color-text-muted);font:500 9px/1.3 var(--font-mono);letter-spacing:.14em}.eyebrow.accent{color:var(--color-text-muted)}
@media (max-width:900px){.store-info-page{padding:28px 22px 60px}.store-info-layout{grid-template-columns:1fr}.store-info-aside{position:static;display:grid;grid-template-columns:1fr 1fr;gap:14px}.anchor-nav{display:none}.aside-note{align-self:stretch}.header-meta{display:none}}
@media (max-width:620px){.store-info-page{padding:22px 14px 48px}.store-info-header{margin-bottom:22px}.store-info-header h1{font-size:26px}.form-card{padding:21px 18px}.two-col,.status-options{grid-template-columns:1fr 1fr}.region-grid{grid-template-columns:1fr}.hours-row{flex-wrap:wrap}.hours-row input{max-width:none;flex:1}.hours-hint{width:100%;margin-left:0}.environment-grid{grid-template-columns:repeat(3,1fr)}.store-info-aside{grid-template-columns:1fr}.form-footer{align-items:stretch;flex-direction:column-reverse}.form-actions{justify-content:flex-end}.primary-button{flex:1}}
/* Shared paper surfaces and semantic controls. */
:global(.page-scroll.store-info-scroll){background:var(--color-bg-canvas);color:var(--color-primary)}
.store-info-header h1{color:var(--color-primary)}
.store-info-intro{color:var(--color-text-muted)}
.completion-top>b{color:var(--color-text-muted)}
.completion-track span{background:var(--color-accent)}
.completion-state i{background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}
.anchor-nav a:hover{color:var(--color-text-muted);background:var(--color-bg-subtle)}
.anchor-nav a.active{color:var(--color-accent-text);background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}
.anchor-nav a i{background:var(--color-bg-surface)}
.anchor-nav a.active span{color:var(--color-accent-text)}
.aside-note{border-color:var(--color-border-subtle);background:var(--color-bg-surface);color:var(--color-text-muted)}
.aside-note p{color:var(--color-text-muted)}
.step-number{background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface));color:var(--color-accent-text)}
.field input:focus,.field select:focus,.field textarea:focus{border-color:var(--color-accent);box-shadow:var(--shadow-paper)}
.status-option.selected{background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}
.amenity-chip.selected{border-color:var(--color-accent);background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface));color:var(--color-accent-text)}
.selected .chip-check{border-color:var(--color-accent);background:var(--color-accent)}
.text-button{color:var(--color-accent-text)}
.upload-drop:hover,.upload-drop.dragging{border-color:var(--color-border-subtle);background:var(--color-bg-subtle)}
.upload-icon,.environment-add span{color:var(--color-accent-text)}
.primary-button{border-color:var(--color-accent);background:var(--color-accent);box-shadow:none}
.primary-button:hover{background:var(--color-accent-hover)}
.form-notice.success{color:var(--color-success)}
/* Refined desktop split layout + restrained entrance motion */
.store-info-page{max-width:1400px;padding:18px 32px 80px}
.store-info-header{margin-bottom:22px}
.store-info-layout{display:flex;align-items:flex-start;gap:32px}
.store-form{display:grid;flex:1;max-width:900px;gap:16px}
.store-info-aside{width:320px;flex:0 0 320px;position:sticky;top:32px}
.form-card{padding:24px;border-radius:8px;box-shadow:var(--shadow-paper);opacity:0;transform:translateY(20px);animation:store-card-in .55s cubic-bezier(.22,1,.36,1) forwards;animation-delay:calc(var(--index) * 75ms + 90ms)}
.completion-card{padding:20px;border-radius:8px}
.completion-top{align-items:flex-start}.completion-top strong{display:block;margin-top:5px;color:var(--color-primary);font-size:13px}.completion-top>b{color:var(--color-text-muted);font:800 22px var(--font-sans)}.completion-track{height:7px;margin:16px 0 11px}.completion-foot{display:flex;justify-content:space-between;align-items:center;color:var(--color-text-muted);font-size:10px}.completion-state{display:flex;align-items:center;gap:5px;color:var(--color-accent-text);font-family:var(--font-mono);font-size:9px}.completion-state i{width:6px;height:6px;border-radius:50%;background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}
.anchor-nav{gap:2px;margin:26px 0}.anchor-nav>.eyebrow{padding:0 12px;margin-bottom:7px}.anchor-nav a{position:relative;gap:13px;padding:11px 12px;border-radius:8px}.anchor-nav a i{position:absolute;left:-1px;width:3px;height:18px;border-radius:0 3px 3px 0;background:var(--color-bg-surface);opacity:0;transform:scaleY(.5);transition:.18s}.anchor-nav a.active{color:var(--color-accent-text);background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}.anchor-nav a.active strong{font-weight:700}.anchor-nav a.active i{opacity:1;transform:scaleY(1)}.anchor-nav a.active span{color:var(--color-accent-text)}
@keyframes store-card-in{to{opacity:1;transform:translateY(0)}}
@media (prefers-reduced-motion:reduce){.form-card{opacity:1;transform:none;animation:none}}
@media (max-width:1100px){.store-info-page{padding:32px 24px 64px}.store-info-layout{gap:24px}.store-info-aside{width:270px;flex-basis:270px}}
@media (max-width:900px){.store-info-page{padding:28px 22px 60px}.store-info-layout{display:grid;grid-template-columns:1fr}.store-form{max-width:none;grid-row:1}.store-info-aside{position:static;width:auto;display:grid;grid-template-columns:1fr 1fr;gap:14px;grid-row:2}}
/* Cinnabar highlights follow the global brand tokens. */
.completion-top>b{color:var(--color-text-muted)}
.completion-track span{background:var(--color-accent)}
.completion-state i{background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}
.anchor-nav a i{background:var(--color-bg-surface)}
.anchor-nav a.active{color:var(--color-accent-text);background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}
.anchor-nav a.active span{color:var(--color-accent-text)}
.anchor-nav a:hover{color:var(--color-text-muted);background:var(--color-bg-subtle)}
.step-number{background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface));color:var(--color-accent-text)}
.amenity-chip.selected{border-color:var(--color-accent);background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface));color:var(--color-accent-text)}
.selected .chip-check{border-color:var(--color-accent);background:var(--color-accent)}
.text-button{color:var(--color-accent-text)}
.upload-drop:hover,.upload-drop.dragging{border-color:var(--color-border-subtle);background:var(--color-bg-subtle)}
.upload-icon,.environment-add span{color:var(--color-accent-text)}
.primary-button{border-color:var(--color-accent);background:var(--color-accent);box-shadow:none}
.primary-button:hover{background:var(--color-accent-hover)}
/* Semantic status dots: each state keeps its own color even when idle */
.status-option .status-check{position:relative;flex:0 0 8px;width:8px;height:8px;background:currentColor;opacity:.58;transition:transform .24s cubic-bezier(.22,1,.36,1),opacity .24s ease}
.status-option.selected .status-check{opacity:1;transform:scale(1.08);box-shadow:var(--shadow-paper);animation:status-dot-pop .68s cubic-bezier(.22,1,.36,1)}
.status-option.selected .status-check::after{content:'';position:absolute;inset:-3px;border:1px solid currentColor;border-radius:50%;opacity:0;animation:status-ring-pulse .68s cubic-bezier(.22,1,.36,1)}
.tone-green{color:var(--color-success)}.tone-amber{color:var(--color-warning)}.tone-orange{color:var(--color-warning)}.tone-slate{color:var(--color-text-muted)}
@keyframes status-dot-pop{0%{transform:scale(.82)}42%{transform:scale(1.16)}100%{transform:scale(1.08)}}
@keyframes status-ring-pulse{0%{opacity:.5;transform:scale(.72)}65%{opacity:.16;transform:scale(1.12)}100%{opacity:0;transform:scale(1.28)}}
@media (prefers-reduced-motion:reduce){.status-option.selected .status-check,.status-option.selected .status-check::after{animation:none}.status-option.selected .status-check{transform:scale(1.08)}}
/* Alignment guardrails: prevent flex overflow and keep two-column rows level */
.store-info-page,.store-info-layout,.store-form,.form-card{min-width:0}
.store-info-page{width:100%}
.form-card{width:100%}
.field-grid.two-col{align-items:start}
.field-grid.two-col>.field{min-width:0;align-self:start}
.field-label{height:18px;line-height:18px}
.field input,.field select{height:40px;padding-top:0;padding-bottom:0}
.store-info-page{overflow-x:clip}
.form-card{scroll-margin-top:20px}
/* Bundled brand typography for forms and section titles. */
.store-info-page{-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility;font-family:var(--font-sans)}
.store-info-header h1,.card-heading h2,.completion-top>b{font-family:var(--font-serif);letter-spacing:.01em}
.store-info-header h1{font-size:34px;font-weight:800;line-height:1.2}
.store-info-intro{font-size:14px;line-height:1.7}
.eyebrow{font-family:var(--font-mono);font-size:10px;font-weight:600;letter-spacing:.13em;line-height:1.45}
.card-heading h2{font-size:19px;font-weight:700;line-height:1.35}
.card-caption{font-size:12px;line-height:1.5}
.field-label{font-size:13px;font-weight:600;line-height:18px}
.field-label em{font-size:11px;font-weight:500}
.field input,.field select,.field textarea{font-family:var(--font-sans);font-size:13px;line-height:1.5}
.field input::placeholder,.field textarea::placeholder{color:var(--color-text-muted)}
.status-option,.amenity-chip{font-size:12px;font-weight:500}
.completion-top strong{font-size:14px;font-weight:700}
.completion-foot,.completion-card p{font-size:11px;line-height:1.6}
.anchor-nav a{font-size:13px}.anchor-nav a span{font-size:11px}.aside-note strong{font-size:12px}.aside-note p{font-size:11px;line-height:1.6}
.primary-button,.ghost-button{font-size:13px;font-weight:600}
.completion-top strong{margin-top:0}
.anchor-nav{margin-top:16px}
/* Upload copy hierarchy: clearer at a glance, quieter for metadata */
.storefront-drop strong{font-family:var(--font-sans);font-size:14px;font-weight:700;letter-spacing:.01em;line-height:1.45;color:var(--color-primary)}
.storefront-drop small{font-family:var(--font-sans);font-size:12px;font-weight:500;line-height:1.6;color:var(--color-text-muted)}
.storefront-drop .upload-limit{margin-top:13px;font-family:var(--font-sans);font-size:10px;font-weight:600;letter-spacing:.04em;color:var(--color-text-muted)}
/* High-legibility type pass: reduce mono noise and strengthen Chinese hierarchy */
.store-info-page{font-family:var(--font-sans);color:var(--color-primary)}
.store-info-header h1{font-size:36px;font-weight:800;letter-spacing:.01em;color:var(--color-primary)}
.store-info-intro{font-size:14px;font-weight:500;color:var(--color-text-muted);letter-spacing:.01em}
.card-heading h2{font-size:20px;font-weight:700;letter-spacing:.01em;color:var(--color-primary)}
.card-caption{font-size:12px;font-weight:500;color:var(--color-text-muted)}
.eyebrow{font-family:var(--font-sans);font-size:10px;font-weight:700;letter-spacing:.12em;color:var(--color-text-muted)}
.field-label{font-size:14px;font-weight:600;color:var(--color-primary)}
.field-label em{font-size:11px;font-weight:500;color:var(--color-text-muted)}
.field input,.field select,.field textarea{font-size:14px;font-weight:500;color:var(--color-primary);letter-spacing:.005em}
.field input::placeholder,.field textarea::placeholder{font-size:13px;color:var(--color-text-muted);opacity:1}
.status-option,.amenity-chip{font-size:13px;font-weight:600}
.completion-top strong{font-size:15px;font-weight:700;color:var(--color-primary)}
.completion-top>b{font-size:24px;font-weight:800;letter-spacing:.01em}
.completion-foot,.completion-card p{font-size:12px;font-weight:500;color:var(--color-text-muted)}
.completion-state{font-family:var(--font-sans);font-size:10px;font-weight:700;letter-spacing:.03em;color:var(--color-accent-text)}
.anchor-nav a{font-size:14px;font-weight:500;color:var(--color-text-muted)}
.anchor-nav a strong{font-weight:600}.anchor-nav a span{font-family:var(--font-sans);font-size:11px;font-weight:700;color:var(--color-text-muted)}
.aside-note strong{font-size:13px;font-weight:700}.aside-note p{font-size:12px;font-weight:500}
.primary-button,.ghost-button{font-size:14px;font-weight:700}
.section-helper{margin:-10px 0 18px;color:var(--color-text-muted);font-size:12px;line-height:1.65}
.special-hours-list{display:grid;gap:10px}.special-hour-row{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(180px,1fr) minmax(130px,1fr) auto;align-items:center;gap:9px;padding:11px;border:1px solid var(--color-border-subtle);border-radius:8px;background:var(--color-bg-surface)}.special-hour-main,.special-hour-time{display:flex;align-items:center;gap:8px;min-width:0}.special-hour-row select,.special-hour-row input{height:38px;min-width:0;padding:0 10px;border:1px solid var(--color-border-subtle);border-radius:7px;background:var(--color-bg-surface);color:var(--color-primary);font-family:var(--font-sans);font-size:12px}.special-hour-main select{flex:1}.special-hour-time input{width:88px}.special-hour-time span{color:var(--color-text-muted);font-size:11px}.special-hour-note{width:100%}.remove-rule{display:grid;place-items:center;width:28px;height:28px;border:0;border-radius:7px;background:transparent;color:var(--color-text-muted);font-size:20px;line-height:1}.remove-rule:hover{background:color-mix(in srgb, var(--color-error) 7%, var(--color-bg-surface));color:var(--color-error)}.add-rule{display:inline-flex;align-items:center;gap:4px;margin-top:13px;padding:7px 10px;border:1px dashed var(--color-border-subtle);border-radius:7px;background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface));color:var(--color-accent-text);font-size:12px;font-weight:600;cursor:pointer}.add-rule:hover{background:color-mix(in srgb, var(--color-accent-text) 7%, var(--color-bg-surface))}.add-rule span{font-size:16px;line-height:1}
@media (max-width:700px){.special-hour-row{grid-template-columns:1fr auto}.special-hour-main,.special-hour-note,.special-hour-time{grid-column:1 / -1}.special-hour-time input{flex:1;width:auto}}
.environment-add small{font-family:var(--font-sans);font-size:12px;font-weight:600;letter-spacing:.01em;color:var(--color-text-muted)}
</style>
