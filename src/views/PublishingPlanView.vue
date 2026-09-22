<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Dialog, DialogPanel, DialogTitle } from '@headlessui/vue'
import { Activity, ArrowRight, CalendarDays, Check, CheckCircle2, ChevronLeft, ChevronRight, Clock3, Coffee, FolderOpen, Info, ListFilter, Moon, Play, Plus, Repeat2, Settings2, ShieldCheck, Sparkles, Sun, Trash2, TrendingUp, X, Zap } from 'lucide-vue-next'
import PlanDrawer from '../components/publishing/PlanDrawer.vue'
import ScheduleTask from '../components/publishing/ScheduleTask.vue'
import PlatformMark from '../components/accounts/PlatformMark.vue'
import { bands, DEMO_NOW, drafts, planAccounts, seedPlans, seedTasks, stores, weekdays } from '../data/publishingPlans'
import { bandFor, dateKey, dueTasks, matchDraft, nextOccurrence, taskTime, weekDates } from '../domain/publishingPlans'
import { auth } from '../stores/auth'

const storageKey = `wuyao-publishing-plans-v1:${auth.tenantId || 'demo'}:${auth.user?.id || 'demo'}`
const storageWarning = ref('')
function readSaved() {
  try {
    const raw = localStorage.getItem(storageKey)
    if (!raw) return null
    const value = JSON.parse(raw)
    if (value.version !== 1 || !Array.isArray(value.plans) || !Array.isArray(value.tasks) || !Number.isFinite(value.now) || !Number.isFinite(value.published)) throw new Error('Invalid data')
    if (!value.plans.every(p => Array.isArray(p.days) && Array.isArray(p.accounts) && typeof p.name === 'string') || !value.tasks.every(t => typeof t.title === 'string' && Number.isFinite(taskTime(t)))) throw new Error('Invalid records')
    return value
  } catch { storageWarning.value = '无法读取本地排期，已载入演示数据。'; return null }
}
const saved = readSaved()
const plans = ref(saved?.plans || seedPlans())
const tasks = ref(saved?.tasks || seedTasks())
const now = ref(saved?.now || new Date(DEMO_NOW).getTime())
const newlyPublished = ref(saved?.published || 0)
const view = ref('calendar')
const weekOffset = ref(0)
const platformFilter = ref('全部平台')
const drawerOpen = ref(false)
const editingPlan = ref(null)
const notice = ref('')
const modal = ref(null)
const taskForm = ref({})
const formError = ref('')
let noticeTimer
let lastTick = Date.now()
const panel = 'rounded-md border border-solid border-border-subtle bg-bg-surface shadow-[0_2px_12px_-2px_rgba(15,23,42,0.04),0_8px_24px_-4px_rgba(15,23,42,0.06)]'
const quiet = 'inline-flex items-center justify-center gap-1.5 rounded-lg border border-solid border-border-subtle bg-bg-surface px-3 py-2 text-xs! font-medium! text-brand-primary! transition hover:border-border-subtle hover:bg-bg-subtle disabled:cursor-not-allowed disabled:opacity-40'
const primary = 'inline-flex items-center justify-center gap-2 rounded-xl border-0 bg-brand-accent px-4 py-2.5 text-sm! font-medium! text-[var(--color-on-accent)]! shadow-paper transition hover:bg-brand-accent-hover'
const input = 'box-border w-full rounded-xl border border-solid border-border-subtle bg-bg-surface px-3 py-2.5 text-sm! text-brand-primary! outline-none focus:border-brand-accent focus:ring-2 focus:ring-blue-100'
const activeCount = computed(() => plans.value.filter(p => p.enabled).length)
const dates = computed(() => weekDates(now.value, weekOffset.value))
const today = computed(() => dateKey(now.value))
const todayTasks = computed(() => tasks.value.filter(t => t.date === today.value && t.status === 'pending'))
const isPaused = task => Boolean(task.planId && !plans.value.find(p => p.id === task.planId)?.enabled)
const nextTask = computed(() => tasks.value.filter(t => t.status === 'pending' && !isPaused(t)).sort((a, b) => taskTime(a) - taskTime(b))[0])
const countdown = computed(() => {
  if (!nextTask.value) return '--:--:--'
  const seconds = Math.max(0, Math.ceil((taskTime(nextTask.value) - now.value) / 1000))
  return [Math.floor(seconds / 3600), Math.floor(seconds % 3600 / 60), seconds % 60].map(v => String(v).padStart(2, '0')).join(':')
})
const visibleTasks = computed(() => tasks.value.filter(t => dates.value.includes(t.date) && (platformFilter.value === '全部平台' || planAccounts.find(a => a.id === t.accountId)?.platform === platformFilter.value)))
const countStatus = status => visibleTasks.value.filter(t => t.status === status).length
const dayTasks = date => visibleTasks.value.filter(t => t.date === date)
const cellTasks = (date, band) => dayTasks(date).filter(t => bandFor(t.time) === band).sort((a, b) => a.time.localeCompare(b.time))
const period = computed(() => `${dates.value[0].replace(/^(\d+)-(\d+)-(\d+)$/, '$1年$2月$3日')} — ${dates.value[6].slice(5).replace('-', '月')}日`)
const stats = computed(() => [
  { label: '运行中计划', value: activeCount.value, unit: '个', icon: Activity, note: `${activeCount.value} 个自动化规则活跃中`, tone: 'text-brand-accent-ink bg-bg-subtle', type: 'active' },
  { label: '今日待发布', value: todayTasks.value.length, unit: '条作品', icon: Clock3, note: '最近一条倒计时', tone: 'text-amber-600 bg-amber-50', type: 'countdown' },
  { label: '本周已自动分发', value: 42 + newlyPublished.value, unit: '篇', icon: TrendingUp, note: '发布成功率', tone: 'text-brand-accent-ink bg-bg-subtle', type: 'rate' },
  { label: '流量错峰保护', value: '已启用', unit: '', icon: ShieldCheck, note: '动态浮动 ±10 分钟，避免同频封控', tone: 'text-state-success bg-bg-subtle', type: 'protect' },
])
function persist() {
  try { localStorage.setItem(storageKey, JSON.stringify({ version: 1, plans: plans.value, tasks: tasks.value, now: now.value, published: newlyPublished.value })); storageWarning.value = '' }
  catch { storageWarning.value = '本地存储不可用，当前修改仅在本次访问有效。' }
}
watch([plans, tasks, newlyPublished], persist, { deep: true })
function announce(message) {
  notice.value = message
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { notice.value = '' }, 5000)
}
function openPlan(plan = null) { editingPlan.value = plan; drawerOpen.value = true }
function enqueue(plan, immediate = false) {
  const occurrence = immediate ? { date: today.value, time: new Date(now.value + 8 * 3600000).toISOString().slice(11, 16) } : nextOccurrence(plan, now.value)
  if (!occurrence) return 0
  let added = 0
  for (const accountId of plan.accounts) {
    if (!immediate && tasks.value.some(t => t.planId === plan.id && t.date === occurrence.date && t.accountId === accountId && t.status !== 'published')) continue
    const account = planAccounts.find(a => a.id === accountId)
    if (!account) continue
    const draft = matchDraft(drafts, tasks.value, account, plan.source)
    const generated = !draft && plan.autoFill
    const baseTime = taskTime(occurrence) + (immediate ? 0 : (5 + Math.floor(Math.random() * 11)) * 60000)
    const shifted = new Date(baseTime + 8 * 3600000).toISOString()
    tasks.value.push({
      id: crypto.randomUUID(), planId: plan.id, accountId, date: shifted.slice(0, 10), time: shifted.slice(11, 16), store: plan.store,
      draftId: draft?.id || crypto.randomUUID(), title: draft?.title || (generated ? `${plan.store} · 今日美味推荐（AI 演示）` : '内容池暂无可用作品'),
      image: draft?.image || '/images/publishing/restaurant.jpg', kind: draft?.kind || (generated ? 'AI 补齐演示' : '等待内容'),
      status: draft || generated ? 'pending' : 'blocked', error: draft || generated ? '' : '草稿池已用尽；请更换作品，或开启 AI 自动补齐。',
    })
    added++
  }
  return added
}
function savePlan(value) {
  const plan = { ...value, id: value.id || crypto.randomUUID(), lastRun: value.lastRun || '尚未执行' }
  const index = plans.value.findIndex(p => p.id === plan.id)
  if (index >= 0) {
    plans.value[index] = plan
    tasks.value = tasks.value.filter(t => t.planId !== plan.id || t.status === 'published')
  } else plans.value.push(plan)
  const count = plan.enabled ? enqueue(plan) : 0
  drawerOpen.value = false
  announce(`「${plan.name}」已保存${count ? `，已匹配 ${count} 条下次排期` : '，当前计划已暂停'}。`)
}
function togglePlan(plan) {
  plan.enabled = !plan.enabled
  if (plan.enabled) {
    tasks.value = tasks.value.filter(t => t.planId !== plan.id || t.status !== 'pending' || taskTime(t) > now.value)
    enqueue(plan)
  }
  announce(`「${plan.name}」已${plan.enabled ? '启用' : '暂停'}。`)
}
function publish(task, silent = false) {
  if (task.status !== 'pending') return
  task.status = 'published'
  newlyPublished.value++
  const plan = plans.value.find(p => p.id === task.planId)
  if (plan) plan.lastRun = `${today.value.slice(5).replace('-', '/')} · 分发成功（演示）`
  if (!silent) announce('作品已完成模拟发布，排期与统计已更新。')
}
function runPlan(plan) {
  const before = new Set(tasks.value.map(t => t.id))
  enqueue(plan, true)
  const added = tasks.value.filter(t => !before.has(t.id))
  added.forEach(t => publish(t, true))
  announce(`模拟执行完成：${added.filter(t => t.status === 'published').length} 条成功，${added.filter(t => t.status === 'blocked').length} 条需补充内容。`)
}
const timer = setInterval(() => {
  const tick = Date.now()
  now.value += tick - lastTick
  lastTick = tick
  const due = dueTasks(tasks.value, plans.value, now.value)
  due.forEach(t => publish(t, true))
  if (due.length) {
    for (const id of new Set(due.map(t => t.planId))) {
      const plan = plans.value.find(p => p.id === id)
      if (plan?.enabled) enqueue(plan)
    }
    announce(`${due.length} 条作品已到点完成模拟分发。`)
  }
}, 1000)
onBeforeUnmount(() => { clearInterval(timer); clearTimeout(noticeTimer); persist() })
function openTask(type, task = null, date = today.value, time = '11:30') {
  formError.value = ''
  modal.value = { type, task }
  taskForm.value = task ? { ...task } : { draftId: drafts[0].id, accountId: 'dy', store: stores[0], date, time, title: drafts[0].title }
}
function selectDraft() { taskForm.value.title = drafts.find(d => d.id === taskForm.value.draftId)?.title || '' }
function saveTask() {
  const value = taskForm.value
  if (!value.title.trim()) { formError.value = '请填写作品标题。'; return }
  if (taskTime(value) <= now.value) { formError.value = '请选择晚于演示时钟的发布时间。'; return }
  const draft = drafts.find(d => d.id === value.draftId)
  const platform = planAccounts.find(a => a.id === value.accountId)?.platform
  if (draft && !draft.platforms.includes(platform)) { formError.value = `这份草稿暂不支持${platform}，请更换内容或账号。`; return }
  const task = { ...value, title: value.title.trim(), id: value.id || crypto.randomUUID(), planId: null, status: 'pending', error: '', image: draft?.image || value.image, kind: draft?.kind || value.kind }
  const index = tasks.value.findIndex(t => t.id === task.id)
  if (index >= 0) tasks.value[index] = task
  else tasks.value.push(task)
  modal.value = null
  announce('作品已保存到排期，转为独立任务。')
}
function removeItem() {
  if (modal.value.type === 'delete') {
    const id = modal.value.task.id
    plans.value = plans.value.filter(p => p.id !== id)
    tasks.value = tasks.value.filter(t => t.planId !== id || t.status === 'published')
    announce('计划及未执行任务已删除，历史发布记录已保留。')
  } else { tasks.value = tasks.value.filter(t => t.id !== modal.value.task.id); announce('作品已移出排期。') }
  modal.value = null
}
function dropTask(event, date, time) {
  const task = tasks.value.find(t => t.id === event.dataTransfer.getData('text/plain'))
  if (!task || task.status !== 'pending') return
  if (taskTime({ date, time }) <= now.value) { announce('该波段已过去，请选择未来时间。'); return }
  Object.assign(task, { date, time, planId: null })
  announce('已移动至新波段，并转为独立排期。')
}
function nextLabel(plan) {
  if (!plan.enabled) return '计划已暂停'
  const next = tasks.value.filter(t => t.planId === plan.id && t.status === 'pending' && taskTime(t) > now.value).sort((a, b) => taskTime(a) - taskTime(b))[0] || nextOccurrence(plan, now.value)
  return next ? `${next.date.slice(5).replace('-', '/')} ${next.time}` : '暂无排期'
}
</script>

<template>
  <div class="publishing-plan-page min-h-full bg-bg-canvas font-sans text-brand-primary antialiased   [&_button]:focus-visible:outline-2 [&_button]:focus-visible:outline-offset-2 [&_button]:focus-visible:outline-brand-primary">
    <header class="mb-7 flex flex-wrap items-center justify-between gap-5">
      <div><h1 class="m-0 font-serif text-[26px] font-semibold tracking-normal text-brand-primary max-sm:text-xl">发布计划 <span class="mx-1 font-normal text-brand-muted">/</span> 自动分发引擎</h1><p class="mb-0 mt-3 text-xs leading-6 text-brand-muted">基于本地生活商圈流量波段，自动化执行作品分发任务，支持智能错峰防重</p></div>
      <button :class="primary" @click="openPlan()"><Plus :size="17" />新增发布计划</button>
    </header>
    <div v-if="storageWarning" role="alert" class="mb-4 rounded-lg bg-amber-50 p-3 text-xs text-amber-700">{{ storageWarning }}</div>
    <section aria-label="节奏监控看板" class="mb-6 grid grid-cols-4 gap-4 max-xl:grid-cols-2 max-sm:grid-cols-1">
      <article v-for="stat in stats" :key="stat.type" :class="panel" class="p-5">
        <div class="flex items-center justify-between"><span class="text-xs font-medium text-brand-muted">{{ stat.label }}</span><span class="flex size-8 items-center justify-center rounded-lg" :class="stat.tone"><component :is="stat.icon" :size="17" :stroke-width="1.8" /></span></div>
        <div class="mb-4 mt-1 flex items-baseline gap-2"><strong :class="stat.type === 'protect' ? 'text-2xl' : 'text-[32px]'" class="font-semibold tracking-tight text-brand-primary">{{ stat.value }}</strong><span class="text-xs text-brand-muted">{{ stat.unit }}</span><span v-if="stat.type === 'protect'" class="ml-auto flex items-center gap-1 rounded-full bg-bg-subtle px-2 py-1 text-[10px] text-state-success"><Check :size="10" />运行正常</span></div>
        <div class="flex flex-wrap items-center gap-1.5 text-[10px] text-brand-muted"><span v-if="stat.type === 'active'" class="size-1.5 animate-pulse rounded-full bg-brand-accent motion-reduce:animate-none"></span>{{ stat.note }}<span v-if="stat.type === 'countdown'" class="font-mono text-xs font-medium text-amber-600">{{ countdown }}</span><span v-if="stat.type === 'rate'" class="font-semibold text-state-success">99.2% <span class="font-normal">↗</span></span></div>
      </article>
    </section>
    <section class="mb-6 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-solid border-brand-accent/80 bg-bg-subtle px-4 py-3">
      <div class="flex items-center gap-3"><span class="flex size-8 shrink-0 items-center justify-center rounded-lg bg-bg-surface text-brand-accent-ink"><Sparkles :size="17" /></span><p class="m-0 text-xs leading-6 text-brand-primary"><strong class="mr-2 font-semibold text-brand-accent-ink">把握每一段同城好流量</strong>午市套餐提前种草，晚市探店精准触达，让内容按你的生意节奏出发。</p></div><span class="flex items-center gap-1.5 whitespace-nowrap text-[10px] text-brand-accent-ink"><ShieldCheck :size="13" />智能防重保护中</span>
    </section>
    <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
      <div class="inline-flex gap-1 rounded-xl border border-solid border-border-subtle bg-bg-subtle p-1" aria-label="排期视图"><button :aria-pressed="view === 'calendar'" class="flex items-center gap-2 rounded-lg border-0 px-4 py-2.5 text-xs! font-medium! transition" :class="view === 'calendar' ? 'bg-bg-surface text-brand-accent-ink! shadow-sm' : 'bg-transparent text-brand-muted! hover:text-brand-primary!'" @click="view = 'calendar'"><CalendarDays :size="15" />日历排期看板</button><button :aria-pressed="view === 'rules'" class="flex items-center gap-2 rounded-lg border-0 px-4 py-2.5 text-xs! font-medium! transition" :class="view === 'rules' ? 'bg-bg-surface text-brand-accent-ink! shadow-sm' : 'bg-transparent text-brand-muted! hover:text-brand-primary!'" @click="view = 'rules'"><Settings2 :size="15" />发布规则管理<span class="rounded bg-bg-subtle px-1.5 py-0.5 text-[10px] text-brand-muted">{{ plans.length }}</span></button></div>
      <div class="flex items-center gap-4 text-[10px] text-brand-muted"><span class="flex items-center gap-1.5"><span class="size-1.5 rounded-full bg-brand-accent"></span>已发布</span><span class="flex items-center gap-1.5"><span class="size-1.5 rounded-full bg-amber-500"></span>待发布</span><span class="flex items-center gap-1.5"><span class="size-1.5 rounded-full bg-rose-500"></span>需处理</span></div>
    </div>

    <section v-if="view === 'calendar'" :class="panel" class="overflow-hidden" aria-label="七日流量时间轴">
      <header class="flex flex-wrap items-center justify-between gap-4 border-0 border-b border-solid border-border-subtle px-5 py-4">
        <div class="flex flex-wrap items-center gap-4"><div class="flex items-center gap-1"><button :class="quiet" class="p-1.5!" aria-label="上周" @click="weekOffset--"><ChevronLeft :size="16" /></button><button :class="quiet" class="px-3! py-1.5!" @click="weekOffset = 0">本周</button><button :class="quiet" class="p-1.5!" aria-label="下周" @click="weekOffset++"><ChevronRight :size="16" /></button></div><h2 class="m-0 text-sm font-semibold text-brand-primary"><span class="mr-1 text-xs font-normal text-brand-muted">当前周期：</span>{{ period }}</h2></div>
        <label class="flex items-center gap-2 text-xs text-brand-muted"><ListFilter :size="14" /><select v-model="platformFilter" aria-label="筛选平台" class="rounded-lg border border-solid border-border-subtle bg-bg-surface px-2 py-1.5 text-xs! text-brand-primary! text-brand-accent-ink"><option>全部平台</option><option v-for="account in planAccounts" :key="account.id">{{ account.platform }}</option></select></label>
      </header>
      <div class="overflow-x-auto" tabindex="0" aria-label="排期日历，可横向滚动">
        <div class="min-w-[1120px]">
          <div class="grid grid-cols-[64px_repeat(7,minmax(0,1fr))] border-0 border-b border-solid border-border-subtle">
            <div class="flex items-center justify-center bg-bg-subtle text-brand-muted"><Clock3 :size="17" /></div>
            <div v-for="(date, index) in dates" :key="date" class="border-0 border-l border-solid border-border-subtle px-3 py-4" :class="date === today ? 'bg-bg-subtle' : 'bg-bg-surface'">
              <div class="flex items-center justify-between"><span class="text-xs" :class="date === today ? 'font-semibold text-brand-accent-ink' : 'text-brand-muted'">{{ weekdays[index] }}</span><span class="flex size-7 items-center justify-center rounded-full font-mono text-sm font-medium" :class="date === today ? 'bg-brand-accent text-[var(--color-on-accent)] shadow-sm shadow-blue-500/20' : 'text-brand-primary'">{{ date.slice(8) }}</span></div>
              <p class="mb-2 mt-2 text-[10px] text-brand-muted">{{ dayTasks(date).length }} 条排期<span v-if="date === today" class="ml-2 text-brand-accent-ink">今天</span><span v-else-if="index > 4" class="ml-2 text-amber-600">周末高流量</span></p>
              <span class="inline-flex items-center gap-1 rounded-md px-1.5 py-1 text-[9px]" :class="index > 3 ? 'bg-amber-50 text-amber-700' : 'bg-bg-subtle text-brand-accent-ink'"><TrendingUp :size="10" />{{ index > 3 ? '晚市爆款 18:00' : '午市高峰 11:30' }}</span>
            </div>
          </div>
          <div v-for="(band, bandIndex) in bands" :key="band.id" class="grid grid-cols-[64px_repeat(7,minmax(0,1fr))] border-0 border-b border-solid border-border-subtle last:border-b-0">
            <div class="flex flex-col items-center gap-2 bg-bg-subtle pt-6"><component :is="[Sun, Coffee, Moon][bandIndex]" :size="16" class="text-brand-muted" /><span class="text-[10px] font-medium text-brand-muted">{{ ['午市', '晚市', '夜宵'][bandIndex] }}</span><time class="font-mono text-[9px] text-brand-muted">{{ band.time }}</time><div class="mt-3 h-12 w-px bg-bg-subtle"></div></div>
            <div v-for="date in dates" :key="date" class="min-h-48 space-y-2 border-0 border-l border-solid border-border-subtle p-2.5 transition-colors" :class="date === today ? 'bg-bg-subtle' : 'bg-bg-subtle'" @dragover.prevent @drop.prevent="dropTask($event, date, band.time)">
              <ScheduleTask v-for="task in cellTasks(date, band.id)" :key="task.id" :task="task" :paused="isPaused(task)" @publish="publish($event)" @remove="openTask('remove', $event)" @edit="openTask('edit', $event)" />
              <button v-if="taskTime({ date, time: band.time }) > now" class="flex w-full items-center justify-center gap-1 rounded-lg border border-dashed border-border-subtle bg-transparent px-1 py-2.5 text-[10px] text-brand-muted transition hover:border-brand-accent hover:bg-bg-subtle hover:text-brand-accent-ink" :aria-label="`${date} ${band.label} 添加到此波段`" @click="openTask('add', null, date, band.time)"><Plus :size="12" />添加到此波段</button>
              <p v-else-if="!cellTasks(date, band.id).length" class="mt-8 text-center text-[10px] text-brand-muted">暂无排期</p>
            </div>
          </div>
        </div>
      </div>
      <footer class="flex flex-wrap items-center justify-between gap-2 border-0 border-t border-solid border-border-subtle bg-bg-surface px-5 py-3 text-[10px] text-brand-muted"><span>本周期 {{ visibleTasks.length }} 条作品 <span class="mx-2">·</span>{{ countStatus('published') }} 条已发布 <span class="mx-2">·</span>{{ countStatus('pending') }} 条待发布 <span class="mx-2">·</span>{{ countStatus('blocked') }} 条需处理</span><span class="flex items-center gap-1"><Info :size="12" />可拖拽调整波段，或通过微调作品修改时间</span></footer>
    </section>

    <section v-else class="space-y-4" aria-label="发布策略列表">
      <div class="mb-4 flex items-center justify-between"><p class="m-0 text-xs text-brand-muted">让内容找到合适的时间，持续触达附近的顾客。</p><span class="text-xs text-brand-muted">{{ activeCount }} 个运行中 / {{ plans.length }} 个计划</span></div>
      <article v-for="plan in plans" :key="plan.id" :class="panel" class="overflow-hidden transition">
        <header class="flex flex-wrap items-center justify-between gap-3 px-6 py-5"><div class="flex items-center gap-3"><span class="flex size-10 items-center justify-center rounded-xl" :class="plan.enabled ? 'bg-bg-subtle text-brand-accent-ink' : 'bg-bg-subtle text-brand-muted'"><Repeat2 :size="20" /></span><div><h3 class="m-0 text-sm font-semibold">{{ plan.name }}</h3><p class="mb-0 mt-1.5 text-[11px] text-brand-muted">{{ plan.store }} <span class="mx-1">·</span> {{ plan.enabled ? '自动化规则运行中' : '已暂停，保留现有排期' }}</p></div></div><div class="flex items-center gap-3"><span class="text-xs" :class="plan.enabled ? 'text-state-success' : 'text-brand-muted'">{{ plan.enabled ? '运行中' : '已暂停' }}</span><button role="switch" :aria-checked="plan.enabled" :aria-label="`${plan.enabled ? '暂停' : '启用'} ${plan.name}`" class="relative h-6 w-11 rounded-full border-0 p-0.5 transition-colors duration-200" :class="plan.enabled ? 'bg-brand-accent' : 'bg-slate-300'" @click="togglePlan(plan)"><span class="block size-5 rounded-full bg-bg-surface shadow-sm transition-transform duration-200 motion-reduce:transition-none" :class="plan.enabled ? 'translate-x-5' : 'translate-x-0'"></span></button></div></header>
        <div class="mb-5 flex flex-wrap gap-2 px-6"><span v-for="id in plan.accounts" :key="id" class="inline-flex items-center gap-1.5 rounded-full border border-solid border-border-subtle px-2 py-1 text-[10px] text-brand-muted"><PlatformMark :platform="planAccounts.find(a => a.id === id)?.platform || '抖音'" class="size-4! rounded-full! [&_img]:size-2.5! [&_svg]:size-2.5!" />{{ planAccounts.find(a => a.id === id)?.name }}</span></div>
        <div class="mx-6 mb-5 grid grid-cols-3 gap-5 rounded-xl bg-bg-subtle p-4 max-lg:grid-cols-1"><div><p class="m-0 mb-2 flex items-center gap-1.5 text-[10px] text-brand-muted"><Clock3 :size="12" />触发模式 · 周期循环</p><p class="m-0 text-xs leading-6 text-brand-primary">{{ plan.days.map(d => weekdays[d]).join('、') }}<br><span class="font-mono">{{ plan.time }}</span> <span class="text-[10px] text-brand-muted">北京时间</span></p></div><div><p class="m-0 mb-2 flex items-center gap-1.5 text-[10px] text-brand-muted"><FolderOpen :size="12" />内容源池</p><p class="m-0 text-xs leading-6 text-brand-primary">AI 探店视频草稿箱 · 未发布分类<br><span class="text-[10px] text-brand-muted">{{ plan.source }} {{ plan.autoFill ? '· AI 自动补齐' : '· 草稿不足时阻断' }}</span></p></div><div><p class="m-0 mb-2 flex items-center gap-1.5 text-[10px] text-brand-muted"><ShieldCheck :size="12" />智能防重策略</p><p class="m-0 flex items-center gap-1 text-xs leading-6 text-brand-primary"><Check :size="12" class="text-emerald-500" />同账号内容去重</p><p class="m-0 flex items-center gap-1 text-xs leading-6 text-brand-primary"><Check :size="12" class="text-emerald-500" />随机错峰 5–15 分钟</p><p class="m-0 text-[10px] leading-5 text-brand-muted">标题文案同义替换 · 预设已开启（待接入）</p></div></div>
        <footer class="flex flex-wrap items-center justify-between gap-3 border-0 border-t border-solid border-border-subtle px-6 py-3.5"><div class="flex flex-wrap gap-4 text-[10px] text-brand-muted"><span>最近执行：{{ plan.lastRun }}</span><span class="text-brand-accent-ink">下次执行：{{ nextLabel(plan) }}</span></div><div class="flex items-center gap-2"><button :class="quiet" @click="openPlan(plan)"><Settings2 :size="12" />编辑策略</button><button :class="quiet" :disabled="!plan.enabled" @click="runPlan(plan)"><Play :size="12" />手动跑一次</button><button class="flex items-center gap-1 rounded-lg border-0 bg-transparent px-2 py-2 text-xs! text-brand-muted! hover:bg-rose-50 hover:text-rose-500!" @click="openTask('delete', plan)"><Trash2 :size="13" />删除</button></div></footer>
      </article>
      <div v-if="!plans.length" :class="panel" class="p-12 text-center"><CalendarDays :size="32" class="mx-auto text-brand-muted" /><h3 class="text-base">还没有发布计划</h3><p class="text-sm text-brand-muted">从门店午市或晚市开始，设定你的第一个自动化规则。</p><button :class="primary" @click="openPlan()"><Plus :size="15" />新增发布计划</button></div>
    </section>
    <footer class="mt-5 flex flex-wrap items-center justify-between gap-3 text-[10px] leading-5 text-brand-muted"><div class="flex items-center gap-2"><Zap :size="12" class="text-brand-accent-ink" /><span>设置规则</span><ArrowRight :size="10" /><span>内容池匹配</span><ArrowRight :size="10" /><span>到点自动分发</span></div><span>本地演示 · {{ today }} {{ new Date(now + 8 * 3600000).toISOString().slice(11, 19) }} CST · 页面打开时模拟执行，不调用真实平台</span></footer>
    <PlanDrawer :open="drawerOpen" :plan="editingPlan" @close="drawerOpen = false" @save="savePlan" />
    <Dialog :open="Boolean(modal)" class="relative z-[100] font-sans text-brand-primary " @close="modal = null">
      <div class="fixed inset-0 bg-slate-900/30 backdrop-blur-[2px]"></div><div class="fixed inset-0 flex items-center justify-center overflow-y-auto p-4">
        <DialogPanel class="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-md bg-bg-surface p-6 shadow-paper-elevated">
          <div class="mb-5 flex items-center justify-between"><DialogTitle class="m-0 text-lg font-semibold">{{ { add: '添加到此波段', edit: '微调作品', remove: '移出排期', delete: '删除发布策略' }[modal?.type] }}</DialogTitle><button aria-label="关闭" class="rounded-lg border-0 bg-bg-subtle p-2 text-brand-muted! hover:bg-bg-subtle" @click="modal = null"><X :size="16" /></button></div>
          <template v-if="modal?.type === 'remove' || modal?.type === 'delete'"><p class="text-sm leading-7 text-brand-muted">{{ modal.type === 'delete' ? `确定删除「${modal.task.name}」？未执行排期将一并移除，已发布记录保留。` : `确定将「${modal.task.title}」移出排期？作品仍可从内容池重新选择。` }}</p><div class="mt-6 flex justify-end gap-2"><button :class="quiet" @click="modal = null">取消</button><button class="rounded-lg border-0 bg-rose-500 px-4 py-2 text-xs! font-medium! text-[var(--color-on-accent)]! hover:bg-rose-600" @click="removeItem">确认{{ modal.type === 'delete' ? '删除' : '移出' }}</button></div></template>
          <form v-else class="space-y-4" @submit.prevent="saveTask">
            <label class="block text-xs font-medium">选择内容池作品<select v-model="taskForm.draftId" :class="input" class="mt-2" @change="selectDraft"><option v-for="draft in drafts" :key="draft.id" :value="draft.id">{{ draft.kind }} · {{ draft.title }}</option><option v-if="taskForm.draftId && !drafts.some(d => d.id === taskForm.draftId)" :value="taskForm.draftId">当前 AI 演示作品</option></select></label>
            <label class="block text-xs font-medium">作品标题<input v-model="taskForm.title" required maxlength="80" :class="input" class="mt-2" /></label>
            <label class="block text-xs font-medium">分发账号<select v-model="taskForm.accountId" :class="input" class="mt-2"><option v-for="account in planAccounts" :key="account.id" :value="account.id">{{ account.platform }} · {{ account.name }}</option></select></label>
            <label class="block text-xs font-medium">关联门店 POI<select v-model="taskForm.store" :class="input" class="mt-2"><option v-for="store in stores" :key="store">{{ store }}</option></select></label>
            <div class="grid grid-cols-2 gap-3"><label class="block text-xs font-medium">发布日期<input v-model="taskForm.date" type="date" required :min="today" :class="input" class="mt-2" /></label><label class="block text-xs font-medium">时间（北京时间）<input v-model="taskForm.time" type="time" required :class="input" class="mt-2" /></label></div>
            <p class="m-0 rounded-lg bg-bg-subtle p-3 text-[11px] leading-5 text-brand-accent-ink">手动保存后按指定时间独立执行，不随原规则的启停或编辑变更。</p><p v-if="formError" role="alert" class="text-xs text-rose-500">{{ formError }}</p><div class="flex justify-end gap-2 pt-2"><button type="button" :class="quiet" @click="modal = null">取消</button><button type="submit" :class="primary">保存排期</button></div>
          </form>
        </DialogPanel>
      </div>
    </Dialog>
    <div v-if="notice" role="status" class="fixed bottom-8 left-1/2 z-[120] flex max-w-[90vw] -translate-x-1/2 items-center gap-2 rounded-xl border border-solid border-border-subtle bg-bg-surface px-5 py-3 text-xs text-brand-primary shadow-xl"><CheckCircle2 :size="17" class="shrink-0 text-brand-accent-ink" />{{ notice }}</div>
  </div>
</template>
