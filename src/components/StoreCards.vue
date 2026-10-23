<script setup>
import { ref } from 'vue'
import StoreFormModal from './StoreFormModal.vue'
import { describeSpecialHour } from '../domain/storeProfile'
import { auth } from '../stores/auth'
import { selectedStoreId, archiveStore } from '../stores/merchantContext'

// 一组门店卡片：切换、编辑、关店都在卡片上完成。品牌与门店页按品牌分组使用。
defineProps({ stores: { type: Array, required: true } })

const notice = ref('')
const busyId = ref(null)
const editing = ref(null)

const isCurrent = store => String(store.id) === String(selectedStoreId.value)

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
  <p v-if="notice" class="page-notice" role="alert">{{ notice }}</p>
  <div class="store-list">
    <article v-for="store in stores" :key="store.id" class="store-card" :class="{ current: isCurrent(store) }">
      <div class="store-card-top">
        <span class="store-mark" aria-hidden="true">{{ (store.name || '店').slice(0, 1) }}</span>
        <div class="store-card-title"><h3>{{ store.name }}</h3></div>
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
          <button class="link-button" :disabled="busyId === store.id" @click="editing = store">编辑</button>
          <button v-if="auth.isOwner" class="link-button danger" :disabled="busyId === store.id" @click="closeStore(store)">{{ busyId === store.id ? '关店中…' : '关店' }}</button>
        </div>
      </div>
    </article>
  </div>
  <StoreFormModal v-if="editing" :store="editing" full @close="editing = null" />
</template>

<style scoped>
.page-notice { margin: 0 0 12px; padding: 10px 14px; border: 1px solid var(--color-border-subtle); border-radius: 6px; color: var(--color-error); background: var(--color-bg-surface); font-size: 13px; line-height: 1.7; }
.link-button { padding: 5px 8px; border: 0; background: transparent; color: var(--color-accent-text); font-family: var(--font-sans); font-size: 13px; font-weight: 700; cursor: pointer; }
.link-button.danger { color: var(--color-error); }
.link-button:disabled { opacity: .55; cursor: default; }
.store-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
.store-card { display: flex; flex-direction: column; padding: 18px 20px 14px; border: 1px solid var(--color-border-subtle); border-radius: 8px; background: var(--color-bg-surface); box-shadow: var(--shadow-paper); }
.store-card.current { border-color: color-mix(in srgb, var(--color-accent) 45%, var(--color-border-subtle)); }
.store-card-top { display: flex; align-items: center; gap: 12px; }
.store-mark { display: grid; place-items: center; flex: none; width: 40px; height: 40px; border-radius: 8px; color: var(--color-primary); background: var(--color-bg-subtle); font-family: var(--font-serif); font-size: 18px; font-weight: 700; }
.store-card-title { min-width: 0; flex: 1; }
.store-card-title h3 { margin: 0; overflow: hidden; font-family: var(--font-serif); font-size: 17px; font-weight: 750; text-overflow: ellipsis; white-space: nowrap; }
.store-badge { flex: none; padding: 4px 8px; border-radius: 4px; color: var(--color-accent-text); background: color-mix(in srgb, var(--color-accent-text) 8%, var(--color-bg-surface)); font-size: 12px; font-weight: 700; }
.store-facts { display: grid; gap: 8px; margin: 16px 0 12px; padding-top: 14px; border-top: 1px solid var(--color-border-subtle); }
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
@media (max-width: 620px) { .store-list { grid-template-columns: 1fr; } }
</style>
