<script setup>
// 桌面端的外壳。软件只做直播这一件事，所以这里只有切换门店和退出登录，没有平台的其余导航；
// 商品、知识库、门店这些资料仍在网页版里维护。
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { auth } from '../stores/auth'
import { selectedStore, selectedStoreId, stores, storeLoading, storeError, loadStores } from '../stores/merchantContext'

const router = useRouter()
const userName = computed(() => auth.user?.name || '未命名用户')
const needsStore = computed(() => storeLoading.value || Boolean(storeError.value) || !selectedStoreId.value)
const logout = async () => { await auth.logout(); router.push({ name: 'login' }) }
</script>

<template>
  <div class="app-frame paper-app">
    <main class="main-area paper-main">
      <header class="topbar paper-topbar"><div class="topbar-inner">
        <span class="header-brand"><span class="seal-mark"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><strong>一方志</strong><small>AI 实景直播</small></span>
        <div class="topbar-actions">
          <select v-if="stores.length > 1" v-model="selectedStoreId" class="header-store" aria-label="切换门店" :disabled="storeLoading">
            <option v-for="store in stores" :key="store.id" :value="store.id">{{ store.name }}</option>
          </select>
          <span v-else-if="stores.length" class="desk-store">{{ selectedStore }}</span>
          <span class="desk-user">{{ userName }}</span>
          <button type="button" class="secondary-button compact" @click="logout">退出登录</button>
        </div>
      </div></header>
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
.desk-state { min-height: 55vh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 36px; text-align: center; }
.desk-state h2 { margin: 0; font-size: 18px; }
.desk-state p { margin: 0; max-width: 420px; color: var(--ink-muted); font-size: 13px; line-height: 1.8; }
</style>
