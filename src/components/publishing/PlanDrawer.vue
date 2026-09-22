<script setup>
import { ref, watch } from 'vue'
import { Dialog, DialogPanel, DialogTitle, TransitionChild, TransitionRoot } from '@headlessui/vue'
import { Check, Clock3, MapPin, ShieldCheck, Sparkles, X } from 'lucide-vue-next'
import PlatformMark from '../accounts/PlatformMark.vue'
import { bands, planAccounts, stores, weekdays } from '../../data/publishingPlans'

const props = defineProps({ open: Boolean, plan: { type: Object, default: null } })
const emit = defineEmits(['close', 'save'])
const form = ref({})
const error = ref('')
const customTime = ref(false)
const inputClass = 'mt-2 box-border w-full rounded-xl border border-solid border-slate-200 bg-white px-3 py-2.5 text-sm! text-slate-800! outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-100'
watch(() => props.open, open => {
  if (!open) return
  form.value = props.plan ? { ...props.plan, accounts: [...props.plan.accounts], days: [...props.plan.days] } : {
    name: '', store: stores[0], accounts: ['dy'], days: [0, 1, 2, 3, 4], time: '11:30', source: '顺序消耗', autoFill: true, enabled: true,
  }
  customTime.value = !bands.some(b => b.time === form.value.time)
  error.value = ''
})
function toggle(field, value) {
  form.value[field] = form.value[field].includes(value) ? form.value[field].filter(v => v !== value) : [...form.value[field], value]
}
function save() {
  if (!form.value.name.trim()) { error.value = '请填写计划名称。'; return }
  if (!form.value.accounts.length) { error.value = '请至少选择一个分发账号。'; return }
  if (!form.value.days.length) { error.value = '请至少选择一个重复日期。'; return }
  emit('save', { ...form.value, name: form.value.name.trim(), days: [...form.value.days].sort() })
}
</script>

<template>
  <TransitionRoot appear :show="open" as="template">
    <Dialog class="relative z-[100] font-sans text-slate-800 [color-scheme:light] [font-family:Inter,-apple-system,BlinkMacSystemFont,'PingFang_SC','Segoe_UI',sans-serif]" @close="emit('close')">
      <TransitionChild as="template" enter="transition-opacity duration-200 motion-reduce:transition-none" enter-from="opacity-0" enter-to="opacity-100" leave="transition-opacity duration-150" leave-from="opacity-100" leave-to="opacity-0">
        <div class="fixed inset-0 bg-slate-900/30 backdrop-blur-[2px]"></div>
      </TransitionChild>
      <div class="fixed inset-0 overflow-hidden">
        <div class="absolute inset-y-0 right-0 flex max-w-full">
          <TransitionChild as="template" enter="transform transition duration-300 ease-out motion-reduce:transition-none" enter-from="translate-x-full" enter-to="translate-x-0" leave="transform transition duration-200 ease-in" leave-from="translate-x-0" leave-to="translate-x-full">
            <DialogPanel class="flex h-full w-screen max-w-xl flex-col bg-white shadow-2xl [&_button]:focus-visible:outline-2 [&_button]:focus-visible:outline-offset-2 [&_button]:focus-visible:outline-blue-600">
              <header class="flex items-start justify-between border-0 border-b border-solid border-slate-200 px-6 py-5">
                <div><p class="m-0 mb-2 text-[11px] font-semibold tracking-widest text-blue-600">自动分发引擎</p><DialogTitle class="m-0 text-xl font-semibold">{{ plan ? '编辑发布计划' : '新增发布计划' }}</DialogTitle><p class="m-0 mt-2 text-xs text-slate-500">设定一次节奏，让好内容准时遇见附近的人。</p></div>
                <button type="button" class="rounded-lg border-0 bg-slate-50 p-2 text-slate-500! hover:bg-slate-100" aria-label="关闭计划抽屉" @click="emit('close')"><X :size="19" /></button>
              </header>
              <form id="plan-form" class="min-h-0 flex-1 space-y-7 overflow-y-auto p-6" @submit.prevent="save">
                <section>
                  <h3 class="m-0 mb-4 flex items-center gap-2 text-sm font-semibold"><span class="flex size-6 items-center justify-center rounded-full bg-blue-50 text-xs text-blue-600">1</span>基础信息</h3>
                  <label class="block text-xs font-medium">计划名称 <span class="text-rose-500">*</span><input v-model="form.name" required maxlength="40" placeholder="例如：周末晚市探店冲榜计划" :class="inputClass" /></label>
                  <label class="mt-4 block text-xs font-medium"><span class="inline-flex items-center gap-1"><MapPin :size="13" />关联门店 POI</span><select v-model="form.store" :class="inputClass"><option v-for="store in stores" :key="store">{{ store }}</option></select></label>
                </section>
                <fieldset class="m-0 border-0 p-0">
                  <legend class="mb-4 flex items-center gap-2 text-sm font-semibold"><span class="flex size-6 items-center justify-center rounded-full bg-blue-50 text-xs text-blue-600">2</span>选择分发账号 <span class="text-xs font-normal text-slate-400">可多选 · 演示账号</span></legend>
                  <div class="space-y-2">
                    <button v-for="account in planAccounts" :key="account.id" type="button" :aria-pressed="form.accounts?.includes(account.id)" class="flex w-full items-center gap-3 rounded-xl border border-solid p-3 text-left! transition" :class="form.accounts?.includes(account.id) ? 'border-blue-500 bg-blue-50/50' : 'border-slate-200 bg-white hover:border-slate-300'" @click="toggle('accounts', account.id)">
                      <PlatformMark :platform="account.platform" /><span class="flex-1"><strong class="block text-sm font-medium">{{ account.name }}</strong><span class="mt-1 block text-[11px] text-slate-500">{{ account.platform }} · {{ account.detail }}</span></span><span class="flex size-5 items-center justify-center rounded-md border border-solid" :class="form.accounts?.includes(account.id) ? 'border-blue-600 bg-blue-600 text-white' : 'border-slate-300'"><Check v-if="form.accounts?.includes(account.id)" :size="13" /></span>
                    </button>
                  </div>
                </fieldset>
                <fieldset class="m-0 border-0 p-0">
                  <legend class="mb-4 flex items-center gap-2 text-sm font-semibold"><span class="flex size-6 items-center justify-center rounded-full bg-blue-50 text-xs text-blue-600">3</span>设定发布节奏</legend>
                  <div class="grid grid-cols-2 gap-2">
                    <button v-for="band in bands" :key="band.id" type="button" :aria-pressed="!customTime && form.time === band.time" class="flex items-center justify-between gap-2 rounded-xl border border-solid px-3 py-3 text-xs!" :class="!customTime && form.time === band.time ? 'border-blue-500 bg-blue-50 text-blue-600!' : 'border-slate-200 bg-white text-slate-600! hover:bg-slate-50'" @click="form.time = band.time; customTime = false">{{ band.label }}<span class="font-mono">{{ band.time }}</span></button>
                    <button type="button" :aria-pressed="customTime" class="flex items-center justify-center gap-2 rounded-xl border border-solid px-3 py-3 text-xs!" :class="customTime ? 'border-blue-500 bg-blue-50 text-blue-600!' : 'border-slate-200 bg-white text-slate-600! hover:bg-slate-50'" @click="customTime = true"><Clock3 :size="14" />自定义时间</button>
                  </div>
                  <label v-if="customTime" class="mt-3 block text-xs">发布时间（北京时间）<input v-model="form.time" type="time" required :class="inputClass" /></label>
                  <p class="mb-2 mt-5 text-xs font-medium">重复周期</p>
                  <div class="grid grid-cols-7 gap-1.5"><button v-for="(day, index) in weekdays" :key="day" type="button" :aria-pressed="form.days?.includes(index)" class="rounded-lg border-0 py-2.5 text-xs! transition" :class="form.days?.includes(index) ? 'bg-blue-600 text-white!' : 'bg-slate-100 text-slate-500! hover:bg-slate-200'" @click="toggle('days', index)">{{ day }}</button></div>
                  <p class="mb-0 mt-3 flex items-center gap-1 text-[11px] text-slate-500"><ShieldCheck :size="13" class="text-emerald-500" />智能错峰：在预设时间后随机延迟 5–15 分钟</p>
                </fieldset>
                <fieldset class="m-0 border-0 p-0">
                  <legend class="mb-4 flex items-center gap-2 text-sm font-semibold"><span class="flex size-6 items-center justify-center rounded-full bg-blue-50 text-xs text-blue-600">4</span>内容抓取策略</legend>
                  <p class="m-0 mb-3 rounded-lg bg-slate-50 px-3 py-2.5 text-xs text-slate-500">内容源：AI 探店视频草稿箱 · 未发布分类</p>
                  <div class="flex gap-5"><label v-for="source in ['顺序消耗', '随机消耗']" :key="source" class="flex items-center gap-1.5 text-sm"><input v-model="form.source" type="radio" :value="source" name="source" class="accent-blue-600" />{{ source }}</label></div>
                  <label class="mt-4 flex items-start gap-2 rounded-xl bg-blue-50/70 p-3"><input v-model="form.autoFill" type="checkbox" class="mt-0.5 accent-blue-600" /><span><span class="flex items-center gap-1 text-xs font-medium text-blue-700"><Sparkles :size="13" />无草稿时，触发 AI 自动补齐生产</span><span class="mt-1 block text-[11px] leading-5 text-slate-500">演示模式生成本地占位作品，供排期交互预览。</span></span></label>
                </fieldset>
                <p v-if="error" role="alert" class="text-sm text-rose-600">{{ error }}</p>
              </form>
              <footer class="flex items-center justify-between gap-4 border-0 border-t border-solid border-slate-200 bg-white p-6"><span class="text-[11px] text-slate-400">保存后自动匹配下次排期内容</span><div class="flex gap-2"><button type="button" class="rounded-xl border border-solid border-slate-200 bg-white px-4 py-2.5 text-sm! hover:bg-slate-50" @click="emit('close')">取消</button><button form="plan-form" type="submit" class="rounded-xl border-0 bg-blue-600 px-5 py-2.5 text-sm! font-medium! text-white! shadow-md shadow-blue-500/20 hover:bg-blue-500">保存计划</button></div></footer>
            </DialogPanel>
          </TransitionChild>
        </div>
      </div>
    </Dialog>
  </TransitionRoot>
</template>
