<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import StoreFormModal from '../components/StoreFormModal.vue'
import { brandApi } from '../services/brandApi'
import { describeSpecialHour } from '../domain/storeProfile'
import { auth } from '../stores/auth'
import { stores, selectedStoreId, storeLoading, storeError, loadStores, archiveStore } from '../stores/merchantContext'

const brands = ref([])
const notice = ref('')
const busyId = ref(null)
const modalOpen = ref(false)
const editing = ref(null)

const brandNames = computed(() => new Map(brands.value.map(brand => [brand.id, brand.name])))
const isCurrent = store => String(store.id) === String(selectedStoreId.value)
// 商户还没建过品牌时不提品牌这回事；建过之后，没挂上品牌的门店才值得提醒。
const brandOf = store => brandNames.value.get(store.brandId) || (brands.value.length ? '尚未归属品牌' : '')

onMounted(async () => {
  document.querySelector('.page-scroll')?.classList.add('stores-scroll')
  // 门店列表登录时已经取过；这里再取一次，别人刚改过的资料也能看到。
  await Promise.all([loadStores(), loadBrands()])
})
onBeforeUnmount(() => document.querySelector('.page-scroll')?.classList.remove('stores-scroll'))

// 品牌名只用来显示，取不到不影响管理门店。
async function loadBrands() {
  try { brands.value = await brandApi.names() } catch { brands.value = [] }
}

function openCreate() { editing.value = null; notice.value = ''; modalOpen.value = true }
function openEdit(store) { editing.value = store; notice.value = ''; modalOpen.value = true }

async function closeStore(store) {
  if (busyId.value) return
  if (!confirm(`关闭“${store.name}”？\n\n关店后，这家店的商品、知识库和直播记录都无法再访问，目前不能恢复。`)) return
  busyId.value = store.id
  notice.value = ''
  try { await archiveStore(store) }
  catch (error) { notice.value = error.message || '关店失败，请重试' }
  finally { busyId.value = null }
}
</script>

<template>
  <div class="stores-page">
    <header class="stores-header">
      <div>
        <p class="eyebrow">ASSET CENTER / STORES</p>
        <h1>门店库</h1>
        <p class="header-desc">{{ auth.isOwner ? '本商户的全部门店。商品、知识库和直播都归属于具体的门店。' : '分配给你的门店。需要进入其他门店，请联系管理员。' }}</p>
      </div>
      <button v-if="auth.isOwner" class="primary-button" @click="openCreate"><span>＋</span> 新建门店</button>
    </header>

    <p v-if="notice" class="page-notice" role="alert">{{ notice }}</p>

    <div v-if="storeLoading && !stores.length" class="loading-state">正在加载门店…</div>
    <div v-else-if="storeError" class="empty-state">
      <h2>门店暂时无法加载</h2>
      <p>{{ storeError }}</p>
      <button class="ghost-button" @click="loadStores">重新加载</button>
    </div>
    <div v-else-if="stores.length" class="store-list">
      <article v-for="store in stores" :key="store.id" class="store-card" :class="{ current: isCurrent(store) }">
        <div class="store-card-top">
          <span class="store-mark" aria-hidden="true">{{ (store.name || '店').slice(0, 1) }}</span>
          <div class="store-card-title">
            <h2>{{ store.name }}</h2>
            <p v-if="brandOf(store)" :class="{ muted: !store.brandId }">{{ brandOf(store) }}</p>
          </div>
          <span v-if="isCurrent(store)" class="store-badge">当前门店</span>
        </div>
        <dl class="store-facts">
          <div><dt>地址</dt><dd :class="{ empty: !store.address }">{{ store.address || '未填写' }}</dd></div>
          <div><dt>电话</dt><dd :class="{ empty: !store.phone }">{{ store.phone || '未填写' }}</dd></div>
          <div><dt>营业时间</dt><dd :class="{ empty: !store.businessHours }">{{ store.businessHours || '未填写' }}</dd></div>
          <!-- 档案三项填了才显示，没填的不占地方；想补就点"编辑"。 -->
          <div v-if="store.specialHours?.length"><dt>特殊安排</dt><dd><span v-for="(rule, index) in store.specialHours" :key="index" class="fact-line">{{ describeSpecialHour(rule) }}</span></dd></div>
          <div v-if="store.transportGuide"><dt>交通指引</dt><dd>{{ store.transportGuide }}</dd></div>
          <div v-if="store.amenities?.length"><dt>配套服务</dt><dd class="amenities"><span v-for="amenity in store.amenities" :key="amenity">{{ amenity }}</span></dd></div>
        </dl>
        <div class="store-card-footer">
          <button v-if="!isCurrent(store)" class="link-button" @click="selectedStoreId = store.id">切换到这家店</button>
          <span v-else class="footer-note">正在管理这家店</span>
          <div>
            <button class="link-button" :disabled="busyId === store.id" @click="openEdit(store)">编辑</button>
            <button v-if="auth.isOwner" class="link-button danger" :disabled="busyId === store.id" @click="closeStore(store)">{{ busyId === store.id ? '关店中…' : '关店' }}</button>
          </div>
        </div>
      </article>
    </div>
    <div v-else class="empty-state">
      <div class="empty-mark">⌂</div>
      <h2>{{ auth.isOwner ? '先建第一家门店' : '还没有分配给你的门店' }}</h2>
      <p>{{ auth.isOwner ? '有了门店，才能录入商品、维护知识库并配置 AI 实景直播。' : '请联系管理员在“员工管理”里把门店分配给你。' }}</p>
      <button v-if="auth.isOwner" class="primary-button" @click="openCreate">新建门店</button>
      <button v-else class="ghost-button" @click="loadStores">刷新</button>
    </div>

    <StoreFormModal v-if="modalOpen" :store="editing" full @close="modalOpen = false" />
  </div>
</template>

<style scoped>
:global(.page-scroll.stores-scroll) { background: var(--color-bg-canvas); color: var(--color-primary); }
.stores-page { max-width: 1400px; margin: 0 auto; padding: 24px 32px 72px; font-family: var(--font-sans); color: var(--color-primary); }
.stores-header { display: flex; justify-content: space-between; align-items: flex-end; gap: 20px; margin-bottom: 30px; }
.eyebrow { margin: 0; color: var(--color-text-muted); font-size: 10px; font-weight: 700; letter-spacing: .14em; }
.stores-header h1 { margin: 8px 0 6px; font-family: var(--font-serif); font-size: 36px; font-weight: 750; line-height: 1.18; }
.header-desc { margin: 0; color: var(--color-text-muted); font-size: 13px; line-height: 1.75; }
.primary-button, .ghost-button, .link-button { font-family: var(--font-sans); font-size: 13px; font-weight: 700; cursor: pointer; }
.primary-button { flex: none; border: 1px solid var(--color-accent); border-radius: 4px; padding: 11px 17px; color: var(--color-on-accent); background: var(--color-accent); }
.primary-button:hover { background: var(--color-accent-hover); }
.primary-button span { margin-right: 3px; font-size: 16px; }
.ghost-button { border: 1px solid var(--color-border-subtle); border-radius: 4px; padding: 11px 17px; color: var(--color-primary); background: transparent; }
.link-button { padding: 5px 8px; border: 0; background: transparent; color: var(--color-accent-text); }
.link-button.danger { color: var(--color-error); }
.link-button:disabled { opacity: .55; cursor: default; }
.page-notice { margin: 0 0 16px; padding: 10px 14px; border: 1px solid var(--color-border-subtle); border-radius: 6px; color: var(--color-error); background: var(--color-bg-surface); font-size: 13px; line-height: 1.7; }
.loading-state { padding: 60px 0; color: var(--color-text-muted); text-align: center; font-size: 13px; }

.store-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 18px; }
.store-card { display: flex; flex-direction: column; padding: 20px 22px 16px; border: 1px solid var(--color-border-subtle); border-radius: 8px; background: var(--color-bg-surface); box-shadow: var(--shadow-paper); }
.store-card.current { border-color: color-mix(in srgb, var(--color-accent) 45%, var(--color-border-subtle)); }
.store-card-top { display: flex; align-items: center; gap: 14px; }
.store-mark { display: grid; place-items: center; flex: none; width: 44px; height: 44px; border-radius: 8px; color: var(--color-primary); background: var(--color-bg-subtle); font-family: var(--font-serif); font-size: 20px; font-weight: 700; }
.store-card-title { min-width: 0; flex: 1; }
.store-card-title h2 { margin: 0; overflow: hidden; font-family: var(--font-serif); font-size: 19px; font-weight: 750; text-overflow: ellipsis; white-space: nowrap; }
.store-card-title p { margin: 4px 0 0; color: var(--color-accent-text); font-size: 12px; font-weight: 600; }
.store-card-title p.muted { color: var(--color-text-muted); font-weight: 500; }
.store-badge { flex: none; padding: 4px 8px; border-radius: 4px; color: var(--color-accent-text); background: color-mix(in srgb, var(--color-accent-text) 8%, var(--color-bg-surface)); font-size: 10px; font-weight: 700; }
.store-facts { display: grid; gap: 9px; margin: 18px 0 14px; padding-top: 16px; border-top: 1px solid var(--color-border-subtle); }
.store-facts > div { display: grid; grid-template-columns: 64px 1fr; gap: 10px; font-size: 13px; line-height: 1.6; }
.store-facts dt { color: var(--color-text-muted); font-size: 12px; }
.store-facts dd { margin: 0; overflow-wrap: anywhere; }
.store-facts dd.empty { color: var(--color-text-muted); }
.fact-line { display: block; }
.amenities { display: flex; flex-wrap: wrap; gap: 6px; }
.amenities span { padding: 2px 8px; border-radius: 999px; color: var(--color-text-muted); background: var(--color-bg-subtle); font-size: 12px; }
.store-card-footer { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin: auto -8px 0; padding-top: 4px; }
.store-card-footer > div { display: flex; }
.footer-note { padding: 5px 8px; color: var(--color-text-muted); font-size: 12px; }

.empty-state { padding: 64px 24px; border: 1px dashed var(--color-border-subtle); border-radius: 8px; background: var(--color-bg-surface); text-align: center; }
.empty-mark { color: var(--color-accent-text); font-size: 30px; }
.empty-state h2 { margin: 14px 0 8px; font-family: var(--font-serif); font-size: 22px; font-weight: 750; }
.empty-state p { margin: 0 0 22px; color: var(--color-text-muted); font-size: 13px; line-height: 1.75; }

@media (max-width: 620px) {
  .stores-page { padding: 18px 14px 48px; }
  .stores-header { display: block; }
  .stores-header .primary-button { margin-top: 16px; }
  .store-list { grid-template-columns: 1fr; }
}
</style>
