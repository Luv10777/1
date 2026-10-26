<script setup>
import AppSelect from '../components/AppSelect.vue'
import { ChevronLeft, Menu } from 'lucide-vue-next'
// 桌面端的外壳。软件只做直播这一件事，所以这里只有切换门店和退出登录，没有平台的其余导航；
// 商品、知识库、门店这些资料仍在网页版里维护。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth } from '../stores/auth'
import { billing, refreshBilling } from '../stores/billing'
import { selectedStore, selectedStoreId, stores, storeLoading, storeError, loadStores } from '../stores/merchantContext'
import { findDesktopUpdate } from '../services/desktopUpdate'
import { desktopBridge } from '../utils/desktop'

const router = useRouter()
const route = useRoute()
const userName = computed(() => auth.user?.name || '未命名用户')
const needsStore = computed(() => billing.summary?.memberActive !== false && route.name === 'digital-human' && (storeLoading.value || Boolean(storeError.value) || !selectedStoreId.value))
let billingTimer
onMounted(() => { refreshBilling(); billingTimer = setInterval(refreshBilling, 30000) })
onBeforeUnmount(() => clearInterval(billingTimer))
const logout = async () => { await auth.logout(); router.push({ name: 'login' }) }
// 软件不会自动更新，由这里提醒：打开时看一次，软件一直开着的话每几小时再看一次。
const accountMenu = ref(null)
// 手机软件的“设置与验证”（授权、测试声音、运行记录）。入口放进“更多”后，软件就不再在左下角浮一个按钮。
const phoneSettings = desktopBridge?.settings
onMounted(() => phoneSettings?.inPage(true).catch(() => {}))
onBeforeUnmount(() => phoneSettings?.inPage(false).catch(() => {}))
const update = ref(null)
const updateLater = ref(false)
let updateTimer = null
const checkUpdate = async () => { update.value = await findDesktopUpdate() }
// 链接写全，并在新窗口打开：软件会把它交给系统浏览器去下载，那边有下载进度。
const updateUrl = computed(() => (update.value ? new URL(update.value.url, window.location.origin).href : ''))
onMounted(() => { checkUpdate(); updateTimer = setInterval(checkUpdate, 3 * 60 * 60 * 1000) })
onBeforeUnmount(() => clearInterval(updateTimer))
</script>

<template>
  <div class="app-frame paper-app">
    <main class="main-area paper-main">
      <header class="topbar paper-topbar"><div class="topbar-inner">
        <!-- 软件里没有侧边栏：离开直播页（如会员页）后靠这里回去。 -->
        <RouterLink v-if="route.name !== 'digital-human'" to="/digital-human" class="desk-back"><ChevronLeft :size="18" />回到直播</RouterLink>
        <span v-else class="header-brand"><span class="seal-mark"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><strong>一方志</strong><small>实景直播</small></span>
        <div class="topbar-actions">
          <AppSelect v-if="stores.length > 1" v-model="selectedStoreId" class="header-store" aria-label="切换门店" :disabled="storeLoading">
            <option v-for="store in stores" :key="store.id" :value="store.id">{{ store.name }}</option>
          </AppSelect>
          <span v-else-if="stores.length" class="desk-store">{{ selectedStore }}</span>
          <!-- 手机屏幕放不下一排按钮：窄屏时收进“更多”里，宽屏时 summary 隐藏、按钮照旧排开。 -->
          <details ref="accountMenu" class="desk-account" @keydown.esc="accountMenu?.removeAttribute('open')">
            <summary class="desk-menu-button" aria-label="菜单"><Menu :size="20" /></summary>
            <div class="desk-account-items" @click="accountMenu?.removeAttribute('open')">
              <span class="desk-user">{{ userName }}</span>
              <button v-if="phoneSettings" type="button" class="secondary-button compact" @click="phoneSettings.open()">手机设置</button>
              <RouterLink to="/billing" class="secondary-button compact">会员与烟火</RouterLink>
              <a href="/policies/terms" class="secondary-button compact" target="_blank" rel="noopener noreferrer">协议与售后</a>
              <button type="button" class="secondary-button compact" @click="logout">退出登录</button>
            </div>
          </details>
        </div>
      </div></header>
      <div v-if="update && !updateLater" class="desk-update" role="status">
        <p v-if="update.platform === 'android'">一方志 App 有新版本 {{ update.version }}，现在用的是 {{ update.current }}。下载后点安装即可，登录和设置都会保留。</p>
        <p v-else>桌面端有新版本 {{ update.version }}，现在用的是 {{ update.current }}。下载后退出软件再安装，登录和设置都会保留。<template v-if="update.platform === 'mac'">装好后第一次打开，还要到“系统设置 → 隐私与安全性”里点“仍要打开”。</template></p>
        <a class="primary-button compact" :href="updateUrl" target="_blank" rel="noopener">下载新版本</a>
        <button type="button" class="secondary-button compact" @click="updateLater = true">稍后再说</button>
      </div>
      <div class="page-scroll">
        <section v-if="needsStore" class="desk-state" :aria-busy="storeLoading">
          <h2>{{ storeLoading ? '正在加载门店…' : storeError ? '门店暂时无法加载' : auth.isOwner ? '还没有门店' : '还没有分配给你的门店' }}</h2>
          <p>{{ storeError || (storeLoading ? '正在获取你有权限访问的门店。' : auth.isOwner ? '请先在网页版一方志里创建门店、添加商品，再回到这里开播。' : '请联系管理员在网页版的“员工管理”里把门店分配给你，分配后点刷新。') }}</p>
          <button v-if="!storeLoading" type="button" class="secondary-button compact" @click="loadStores">{{ storeError ? '重新加载' : '刷新' }}</button>
        </section>
        <slot v-else />
      </div>
    </main>
  </div>
</template>

<style scoped>
.desk-store, .desk-user { color: var(--ink-muted); font-size: 12px; white-space: nowrap; }
.desk-update { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 10px 32px; border-bottom: 1px solid var(--line); background: color-mix(in srgb, var(--color-accent) 6%, transparent); }
.desk-update p { flex: 1 1 240px; min-width: 0; margin: 0; color: var(--ink); font-size: 12px; line-height: 1.6; }
.desk-update a { display: inline-flex; align-items: center; flex-shrink: 0; text-decoration: none; white-space: nowrap; }
.desk-update button { flex-shrink: 0; margin: 0; }
.desk-state { min-height: 55vh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 36px; text-align: center; }
.desk-state h2 { margin: 0; font-size: 18px; }
.desk-state p { margin: 0; max-width: 420px; color: var(--ink-muted); font-size: 13px; line-height: 1.8; }
.desk-account > summary { display: none; }
.desk-back { display: inline-flex; align-items: center; gap: 2px; min-height: 36px; padding-right: 8px; color: var(--color-primary); font-size: 13px; text-decoration: none; white-space: nowrap; }
.desk-account-items { display: flex; align-items: center; gap: 16px; }
@media (max-width: 640px) {
  .topbar-inner { gap: 10px; }
  .header-brand small { display: none; }
  .topbar-actions { min-width: 0; }
  .desk-store { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
  .header-store { min-width: 0; max-width: 140px; }
  .desk-account { position: relative; flex-shrink: 0; }
  .desk-account > summary { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 50%; color: var(--ink); list-style: none; cursor: pointer; }
  .desk-account[open] > summary { background: var(--color-bg-subtle); }
  .desk-account > summary::-webkit-details-marker { display: none; }
  .desk-account:not([open]) .desk-account-items { display: none; }
  .desk-account-items { position: absolute; right: 0; top: calc(100% + 8px); z-index: 40; flex-direction: column; align-items: stretch; gap: 8px; min-width: 160px; padding: 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--night-panel); box-shadow: var(--shadow-paper); }
  .desk-account-items .desk-user { padding: 2px 4px 6px; border-bottom: 1px solid var(--line); overflow: hidden; text-overflow: ellipsis; }
  .desk-account-items { gap: 0; padding: 6px; }
  .desk-account-items .desk-user { padding: 8px 10px; }
  .desk-account-items .secondary-button { justify-content: flex-start; min-height: 44px; margin: 0; padding: 0 10px; border: 0; border-radius: 6px; color: var(--ink); font-size: 14px; font-weight: 400; text-decoration: none; }
  .desk-account-items .secondary-button:active { background: var(--color-bg-subtle); }
  .desk-account-items .secondary-button:last-child { color: var(--red); }
  .desk-update { padding: 10px 16px; }
  .desk-state { padding: 28px 16px; }
}
</style>
