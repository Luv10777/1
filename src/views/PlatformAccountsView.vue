<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Dialog, DialogPanel, DialogTitle, Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/vue'
import { ArrowRight, ArrowUpRight, Check, CheckCircle2, CircleHelp, Copy, Link2, MoreVertical, Plus, Search, Send, ShieldCheck, TriangleAlert, Unplug, Users, X } from 'lucide-vue-next'
import PlatformMark from '../components/accounts/PlatformMark.vue'
import AccountConnectModal from '../components/accounts/AccountConnectModal.vue'
import { usePlatformAccounts } from '../stores/platformAccounts'
import { accountPlatforms, authorizationStatus, canPublishAccount, filterAccounts, permissionOptions, remainingDays } from '../domain/platformAccounts'

const router = useRouter()
const { accountList, storageError, completeAuthorization, refreshTime } = usePlatformAccounts()
const selectedPlatformFilter = ref('全部')
const searchQuery = ref('')
const isConnectModalOpen = ref(false)
const connectPlatform = ref('抖音')
const renewingAccount = ref(null)
const inspectedAccount = ref(null)
const disconnectAccount = ref(null)
const notice = ref('')
const now = ref(Date.now())
const clock = setInterval(() => { now.value = Date.now(); refreshTime() }, 30000)
let noticeTimer
const platformTabs = ['全部', ...accountPlatforms.map(p => p.name)]
const filteredAccounts = computed(() => filterAccounts(accountList.value, selectedPlatformFilter.value, searchQuery.value))
const connectedPlatforms = computed(() => new Set(accountList.value.filter(a => authorizationStatus(a, now.value) !== 'expired').map(a => a.platform)).size)
const activeCount = computed(() => accountList.value.filter(a => authorizationStatus(a, now.value) !== 'expired').length)
const attentionCount = computed(() => accountList.value.filter(a => authorizationStatus(a, now.value) !== 'active').length)
const health = computed(() => accountList.value.length ? Math.round(accountList.value.filter(a => canPublishAccount(a, now.value)).length / accountList.value.length * 100) : 0)
const stats = computed(() => [
  { label: '已连接平台', value: connectedPlatforms.value, unit: '个平台', note: '抖音 / 小红书 / 视频号', icon: Link2, tone: 'bg-blue-50 text-blue-600' },
  { label: '已托管账号', value: activeCount.value, unit: '个', note: '活跃主理账号', icon: Users, tone: 'bg-slate-100 text-slate-600' },
  { label: '授权健康度', value: health.value, unit: '%', note: health.value === 100 ? '所有发布链路运行正常' : '部分账号需检查授权与权限', icon: ShieldCheck, tone: health.value === 100 ? 'bg-emerald-50 text-emerald-600' : 'bg-amber-50 text-amber-600', health: true },
  { label: '本月自动分发', value: 128, unit: '条', note: '作品通过授权渠道分发', icon: Send, tone: 'bg-blue-50 text-blue-600' },
])
const panelClass = 'rounded-2xl border border-solid border-slate-200/80 bg-white shadow-[0_2px_12px_-2px_rgba(15,23,42,0.04),0_8px_24px_-4px_rgba(15,23,42,0.06)]'
const quietButton = 'inline-flex items-center justify-center gap-1.5 rounded-lg border border-solid border-slate-200 bg-slate-50 px-3 py-2 text-xs font-medium text-slate-600! transition hover:border-slate-300 hover:bg-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600'
const primaryButton = 'inline-flex items-center justify-center gap-2 rounded-lg border-0 bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white! shadow-md shadow-blue-500/20 transition hover:bg-blue-500 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600'

function announce(message) {
  notice.value = message
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { notice.value = '' }, 4500)
}
async function copyUid(uid) {
  try { await navigator.clipboard.writeText(uid); announce('账号 UID 已复制') }
  catch { announce('复制失败，请选中 UID 手动复制。') }
}
function openConnect(account = null) {
  renewingAccount.value = account
  connectPlatform.value = account?.platform || (selectedPlatformFilter.value === '全部' ? '抖音' : selectedPlatformFilter.value)
  isConnectModalOpen.value = true
}
function connect(payload) {
  const account = completeAuthorization(payload)
  isConnectModalOpen.value = false
  selectedPlatformFilter.value = payload.platform
  searchQuery.value = ''
  now.value = Date.now()
  refreshTime()
  announce(`「${account.name}」${payload.accountId ? '续期' : '绑定'}完成，演示授权有效期 90 天。`)
}
function disconnect() {
  accountList.value = accountList.value.filter(a => a.id !== disconnectAccount.value.id)
  disconnectAccount.value = null
  announce('已解除本地演示绑定，该账号已从内容发布页移除。')
}
function goPublish(account) {
  if (!canPublishAccount(account, now.value)) { announce('请先续期并授予发布权限。'); return }
  router.push({ name: 'publishing', query: { accountId: account.id } })
}
function status(account) { return authorizationStatus(account, now.value) }
onBeforeUnmount(() => { clearInterval(clock); clearTimeout(noticeTimer) })
</script>

<template>
  <section class="account-center-page min-h-screen bg-slate-50/70 p-8 font-sans text-slate-800 antialiased [font-family:Inter,-apple-system,BlinkMacSystemFont,'PingFang_SC','Segoe_UI',sans-serif]! [color-scheme:light] max-sm:p-4">
    <div class="mx-auto max-w-[1500px]">
      <header class="mb-7 flex flex-wrap items-start justify-between gap-5">
        <div>
          <div class="mb-3 flex items-center gap-2 text-xs font-medium text-slate-400"><span>全网发布</span><span>/</span><span class="text-blue-600">账号授权中心</span></div>
          <h1 class="m-0 text-[28px] font-semibold tracking-tight text-slate-900">关联平台管理</h1>
          <p class="mb-0 mt-2 text-sm leading-6 text-slate-500">统一管理多端社交媒体与本地生活账号授权，保障 AI 自动分发链路畅通</p>
        </div>
        <button :class="[primaryButton, 'mt-6 max-sm:mt-0']" @click="openConnect()"><Plus :size="17" />绑定新账号</button>
      </header>

      <section aria-label="授权健康度概览" class="mb-7 grid grid-cols-2 gap-4 lg:grid-cols-4 max-sm:gap-2">
        <article v-for="metric in stats" :key="metric.label" :class="[panelClass, 'p-5 max-sm:p-3']">
          <div class="flex items-center justify-between gap-2"><span class="text-[13px] font-medium text-slate-500">{{ metric.label }}</span><span :class="['grid size-8 shrink-0 place-items-center rounded-lg', metric.tone]"><component :is="metric.icon" :size="17" :stroke-width="1.8" /></span></div>
          <div class="mt-3 flex items-baseline gap-1.5"><strong class="text-[32px] font-semibold leading-tight tracking-tight text-slate-900 tabular-nums">{{ metric.value }}</strong><span class="text-xs text-slate-500">{{ metric.unit }}</span><span v-if="metric.health" :class="['ml-1 size-2 self-center rounded-full', health === 100 ? 'bg-emerald-500 shadow-[0_0_0_4px_rgba(16,185,129,0.10)]' : 'bg-amber-500']" /></div>
          <p class="mb-0 mt-2 text-[11px] leading-5" :class="metric.health && health === 100 ? 'text-emerald-600' : 'text-slate-400'">{{ metric.note }}</p>
        </article>
      </section>

      <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div class="flex items-center gap-3"><h2 class="m-0 text-base font-semibold text-slate-900">我的授权账号</h2><span class="rounded-full bg-slate-200/60 px-2 py-0.5 font-mono text-xs text-slate-600">{{ accountList.length }}</span><span v-if="attentionCount" class="hidden items-center gap-1.5 text-xs text-amber-600 sm:inline-flex"><span class="size-1.5 rounded-full bg-amber-500" />{{ attentionCount }} 个账号需关注</span></div>
        <span class="inline-flex items-center gap-1.5 text-xs text-slate-400"><ShieldCheck :size="14" />官方授权 · 安全连接</span>
      </div>
      <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
        <nav aria-label="平台筛选" class="flex max-w-full gap-1 overflow-x-auto rounded-xl border border-solid border-slate-200/70 bg-white p-1">
          <button v-for="tab in platformTabs" :key="tab" :aria-pressed="selectedPlatformFilter === tab" class="flex shrink-0 items-center gap-1.5 rounded-lg border-0 px-3.5 py-2 text-xs font-medium transition focus-visible:outline-2 focus-visible:outline-blue-600" :class="selectedPlatformFilter === tab ? 'bg-blue-600 text-white! shadow-sm' : 'bg-transparent text-slate-500! hover:bg-slate-50 hover:text-slate-800!'" @click="selectedPlatformFilter = tab">{{ tab }}<span class="font-mono text-[10px] opacity-70">{{ tab === '全部' ? accountList.length : accountList.filter(a => a.platform === tab).length }}</span></button>
        </nav>
        <div class="relative w-full sm:w-64"><Search class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" :size="16" /><input v-model="searchQuery" type="text" aria-label="搜索账号名称或 UID" placeholder="搜索账号名称、UID" class="h-10 w-full rounded-lg border border-solid border-slate-200 bg-white pl-9 pr-10 text-xs text-slate-800 placeholder:text-slate-400 focus:border-blue-500 focus:outline-2 focus:outline-blue-100" /><button v-if="searchQuery" aria-label="Clear：清除账号搜索" title="清除搜索" class="absolute right-1.5 top-1/2 grid size-7 -translate-y-1/2 place-items-center rounded border-0 bg-transparent text-slate-500! hover:bg-slate-100" @click="searchQuery = ''"><X :size="14" /></button></div>
      </div>

      <p v-if="storageError" role="alert" class="rounded-lg bg-amber-50 p-3 text-sm text-amber-700">{{ storageError }}</p>
      <p class="sr-only" role="status">当前筛选显示 {{ filteredAccounts.length }} 个账号</p>
      <div class="grid grid-cols-1 items-stretch gap-4 md:grid-cols-2 xl:grid-cols-3">
        <article v-for="account in filteredAccounts" :key="account.id" :class="[panelClass, 'flex min-w-0 flex-col transition duration-200 hover:border-slate-300']">
          <div class="flex items-center justify-between gap-2 px-5 pt-5">
            <div class="flex min-w-0 items-center gap-2.5"><PlatformMark :platform="account.platform" /><span class="text-sm font-semibold text-slate-800">{{ account.platform }}</span><span class="rounded border border-solid border-slate-200/80 bg-slate-50 px-1.5 py-0.5 text-[10px] text-slate-500">{{ account.type }}</span></div>
            <Menu as="div" class="relative">
              <MenuButton class="grid size-8 place-items-center rounded-lg border-0 bg-transparent text-slate-400! hover:bg-slate-100 focus-visible:outline-2 focus-visible:outline-blue-600" :aria-label="`${account.name}：更多操作`"><MoreVertical :size="17" /></MenuButton>
              <MenuItems class="absolute right-0 top-full z-20 mt-1 w-36 rounded-lg border border-solid border-slate-200 bg-white p-1 shadow-lg focus:outline-none">
                <MenuItem v-slot="{ active }"><button :class="['w-full rounded-md border-0 bg-white px-3 py-2 text-left text-xs text-slate-700!', { 'bg-slate-100!': active }]" @click="openConnect(account)">重新授权 / 续期</button></MenuItem>
                <MenuItem v-slot="{ active }"><button :class="['w-full rounded-md border-0 bg-white px-3 py-2 text-left text-xs text-rose-600!', { 'bg-rose-50!': active }]" @click="disconnectAccount = account">解除绑定</button></MenuItem>
              </MenuItems>
            </Menu>
          </div>
          <div class="flex items-center gap-3 px-5 pb-5 pt-6">
            <div :class="['grid size-14 shrink-0 place-items-center overflow-hidden rounded-full text-xl font-medium ring-2 ring-slate-100 shadow-sm', account.avatarClass]"><img v-if="account.image" :src="account.image" alt="" class="size-full object-cover" /><span v-else>{{ account.avatar }}</span></div>
            <div class="min-w-0 flex-1"><h3 class="m-0 truncate text-base font-semibold text-slate-900" :title="account.name">{{ account.name }}</h3><div class="mt-1 flex items-center gap-1 font-mono text-xs text-slate-400"><span class="truncate">UID: {{ account.uid }}</span><button :aria-label="`复制 ${account.name} 的 UID`" class="grid size-6 shrink-0 place-items-center rounded border-0 bg-transparent text-slate-400! hover:bg-blue-50 hover:text-blue-600!" @click="copyUid(account.uid)"><Copy :size="12" /></button></div><span class="mt-1 inline-flex items-center rounded bg-slate-100 px-2 py-0.5 font-mono text-[11px] text-slate-600">{{ account.fans }} 粉丝</span></div>
          </div>
          <div class="flex flex-1 flex-wrap content-start gap-1.5 px-5 pb-5"><span v-for="permission in account.permissions" :key="permission" class="inline-flex items-center gap-1 rounded-md border border-solid border-slate-100 bg-slate-50/70 px-2 py-1 text-[10px] text-slate-600"><Check :size="11" class="text-emerald-500" />{{ permission }}</span><span v-if="!account.permissions.length" class="text-xs text-rose-500">暂未授予任何权限</span></div>
          <div class="mx-5 flex min-h-10 items-center justify-between gap-2 rounded-lg border-0 border-t border-solid px-2.5 py-2 text-[11px]" :class="status(account) === 'expired' ? 'border-rose-100 bg-rose-50 text-rose-600' : status(account) === 'expiring' ? 'border-amber-100 bg-amber-50/80 text-amber-700' : 'border-slate-100 bg-slate-50/80 text-slate-500'">
            <span class="flex min-w-0 items-center gap-1.5"><TriangleAlert v-if="status(account) !== 'active'" :size="13" class="shrink-0" /><span v-else class="size-1.5 shrink-0 rounded-full bg-emerald-500" /><span v-if="status(account) === 'expired'">授权已失效，请重新授权</span><span v-else-if="status(account) === 'expiring'">授权即将到期，请及时续签 <span class="whitespace-nowrap">· {{ remainingDays(account, now) }} 天</span></span><span v-else>授权生效中 <span class="mx-1 text-slate-300">·</span> 剩余 {{ remainingDays(account, now) }} 天</span></span>
            <button v-if="status(account) !== 'active'" class="shrink-0 rounded border-0 bg-transparent p-1 text-xs font-semibold underline underline-offset-2" :class="status(account) === 'expired' ? 'text-rose-600!' : 'text-amber-700!'" @click="openConnect(account)">续签</button>
          </div>
          <footer class="flex flex-wrap items-center justify-between gap-2 px-5 py-4"><button :class="quietButton" @click="inspectedAccount = account"><ShieldCheck :size="13" />检查权限</button><div class="flex items-center gap-3"><button class="border-0 bg-transparent p-0 text-xs text-slate-500! hover:text-blue-600!" @click="openConnect(account)">重新授权</button><button class="inline-flex items-center gap-1 border-0 bg-transparent p-0 text-xs font-semibold text-blue-600! hover:text-blue-500! disabled:cursor-not-allowed disabled:text-slate-400!" :disabled="!canPublishAccount(account, now)" :title="canPublishAccount(account, now) ? '携带账号进入内容发布' : '请先续期并授予发布权限'" @click="goPublish(account)">去发布作品<ArrowUpRight :size="13" /></button></div></footer>
        </article>
        <div v-if="!filteredAccounts.length" class="flex min-h-64 flex-col items-center justify-center rounded-2xl border border-solid border-slate-200 bg-white p-6 text-center md:col-span-2 xl:col-span-2"><Search :size="30" class="mb-3 text-slate-300" /><h3 class="m-0 text-base font-medium text-slate-700">{{ searchQuery ? '没有找到匹配的账号' : '这个平台还没有绑定账号' }}</h3><p class="text-xs text-slate-500">{{ searchQuery ? '试试其他账号昵称或 UID，或清除当前筛选。' : '完成一次授权，让内容触达更多顾客。' }}</p><button :class="quietButton" @click="searchQuery = ''; selectedPlatformFilter = '全部'">查看全部账号</button></div>
        <button class="group flex min-h-52 flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-slate-200 bg-transparent px-5 py-7 text-center transition hover:border-blue-500 hover:bg-blue-50/10 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600" @click="openConnect()"><span class="mb-1 grid size-11 place-items-center rounded-full border border-solid border-slate-200 bg-white text-slate-400 transition group-hover:border-blue-200 group-hover:text-blue-600"><Plus :size="21" :stroke-width="1.5" /></span><strong class="text-sm font-medium text-slate-700">添加新的{{ selectedPlatformFilter === '全部' ? '平台' : selectedPlatformFilter }}账号</strong><span class="text-[11px] leading-5 text-slate-400">支持主账号、员工子账号一键扫码授权</span><span class="mt-2 inline-flex items-center gap-1 text-xs text-blue-600">连接更多可能 <ArrowRight :size="12" /></span></button>
      </div>
      <footer class="mt-6 flex flex-wrap items-center justify-between gap-3 border-0 border-t border-solid border-slate-200/70 pt-5 text-[11px] leading-5 text-slate-400"><span class="inline-flex items-center gap-1.5"><ShieldCheck :size="14" />授权由官方平台确认，您可随时管理或撤回。</span><span class="inline-flex items-center gap-1.5"><CircleHelp :size="13" />当前为本地演示，账号与分发数据均为 Mock</span></footer>
    </div>

    <AccountConnectModal :open="isConnectModalOpen" :initial-platform="connectPlatform" :account="renewingAccount" @close="isConnectModalOpen = false" @complete="connect" />
    <div class="fixed bottom-8 left-1/2 z-[100] max-w-[90vw] -translate-x-1/2" role="status" aria-live="polite"><div v-if="notice" class="flex items-center gap-2 rounded-xl bg-slate-900 px-5 py-3 text-sm text-white shadow-xl"><CheckCircle2 :size="17" class="shrink-0 text-emerald-400" />{{ notice }}</div></div>

    <Dialog :open="!!inspectedAccount || !!disconnectAccount" class="relative z-[80] font-sans text-slate-800 [font-family:Inter,-apple-system,BlinkMacSystemFont,'PingFang_SC','Segoe_UI',sans-serif] [color-scheme:light]" @close="inspectedAccount = null; disconnectAccount = null">
      <div class="fixed inset-0 bg-slate-900/40 backdrop-blur-sm" /><div class="fixed inset-0 flex items-center justify-center overflow-y-auto p-4">
        <DialogPanel class="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl">
          <template v-if="inspectedAccount">
            <div class="flex items-center justify-between"><DialogTitle class="m-0 text-lg font-semibold">账号权限明细</DialogTitle><button :class="quietButton" aria-label="关闭权限明细" @click="inspectedAccount = null"><X :size="16" /></button></div>
            <p class="text-sm text-slate-500">{{ inspectedAccount.name }} · {{ inspectedAccount.platform }}</p>
            <p class="rounded-lg px-3 py-2 text-xs" :class="status(inspectedAccount) === 'expired' ? 'bg-rose-50 text-rose-600' : 'bg-emerald-50 text-emerald-700'">{{ status(inspectedAccount) === 'expired' ? '授权已失效，权限暂不可用' : `本地授权记录有效 · 剩余 ${remainingDays(inspectedAccount, now)} 天` }}</p>
            <ul class="my-4 list-none space-y-3 p-0"><li v-for="permission in permissionOptions(inspectedAccount.platform)" :key="permission" class="flex items-center justify-between text-sm"><span>{{ permission }}</span><span class="flex items-center gap-1 text-xs" :class="inspectedAccount.permissions.includes(permission) ? 'text-emerald-600' : 'text-slate-400'"><Check v-if="inspectedAccount.permissions.includes(permission)" :size="14" />{{ inspectedAccount.permissions.includes(permission) ? '已授予' : '未授予' }}</span></li></ul>
            <p class="text-xs leading-5 text-slate-400">检查结果来自本地演示记录，尚未连接官方权限查询服务。</p><button :class="primaryButton" @click="openConnect(inspectedAccount); inspectedAccount = null">重新授权 / 调整权限</button>
          </template>
          <template v-else-if="disconnectAccount">
            <Unplug :size="26" class="mb-4 text-rose-500" /><DialogTitle class="m-0 text-lg font-semibold">解除账号绑定？</DialogTitle><p class="text-sm leading-6 text-slate-500">「{{ disconnectAccount.name }}」将从授权中心和内容发布的可选账号中移除。此操作仅影响本地演示数据。</p><div class="mt-6 flex justify-end gap-3"><button :class="quietButton" @click="disconnectAccount = null">保留账号</button><button class="rounded-lg border-0 bg-rose-500 px-4 py-2 text-sm font-medium text-white! hover:bg-rose-600" @click="disconnect">确认解绑</button></div>
          </template>
        </DialogPanel>
      </div>
    </Dialog>
  </section>
</template>
