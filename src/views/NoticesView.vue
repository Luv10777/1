<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Bell, CheckCheck, RefreshCw } from 'lucide-vue-next'
import { NOTICES_CHANGED, noticeApi } from '../services/noticeApi'
import '../alliance.css'

const PAGE_SIZE = 20
const router = useRouter()
const feed = ref({ items: [], total: 0, totalPages: 0 })
const page = ref(0)
const loading = ref(false)
const error = ref('')
const busy = ref(false)

const pages = computed(() => Math.max(1, feed.value.totalPages || 1))
const hasUnread = computed(() => feed.value.items.some(item => !item.read))
const date = value => value ? new Date(value).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }) : ''

async function load() {
  loading.value = true
  error.value = ''
  try { feed.value = await noticeApi.list({ page: page.value, size: PAGE_SIZE }) }
  catch (failure) { error.value = failure.message || '通知暂时无法读取。' }
  finally { loading.value = false }
}

function changed() { window.dispatchEvent(new Event(NOTICES_CHANGED)) }

async function open(item) {
  if (!item.read) {
    try { await noticeApi.read(item.id); item.read = true; changed() } catch { /* 标已读失败不影响跳转 */ }
  }
  if (item.link) router.push(item.link)
}

async function readAll() {
  if (busy.value) return
  busy.value = true
  try { await noticeApi.readAll(); await load(); changed() }
  catch (failure) { error.value = failure.message || '操作失败，请稍后再试。' }
  finally { busy.value = false }
}

function go(next) { page.value = next; return load() }

load()
</script>

<template>
  <div class="business-page notices-page">
    <header class="business-heading">
      <div><h1>消息通知</h1><p>联盟券核销、申诉、对账、打款和入驻审核的结果会发到这里。</p></div>
      <div class="business-heading-links">
        <button type="button" class="business-button" :disabled="loading" @click="load"><RefreshCw :size="14" aria-hidden="true" />刷新</button>
        <button type="button" class="business-button" :disabled="busy || !hasUnread" @click="readAll"><CheckCheck :size="14" aria-hidden="true" />全部标为已读</button>
      </div>
    </header>
    <p v-if="error" class="alliance-error" role="alert">{{ error }}</p>
    <p v-if="loading && !feed.items.length" class="alliance-state" role="status">正在读取…</p>
    <div v-else-if="!feed.items.length" class="alliance-state"><Bell :size="30" aria-hidden="true" /><h3>还没有通知</h3></div>
    <ul v-else class="notice-list">
      <li v-for="item in feed.items" :key="item.id">
        <button type="button" class="notice-item" :class="{ unread: !item.read }" @click="open(item)">
          <span class="notice-dot" aria-hidden="true" />
          <span class="notice-main"><strong>{{ item.title }}</strong><span>{{ item.body }}</span><small>{{ date(item.createdAt) }}<template v-if="!item.read"> · 未读</template></small></span>
        </button>
      </li>
    </ul>
    <nav v-if="pages > 1" class="alliance-pager" aria-label="通知分页">
      <button type="button" :disabled="page <= 0 || loading" @click="go(page - 1)">上一页</button>
      <span>{{ page + 1 }} / {{ pages }}</span>
      <button type="button" :disabled="page + 1 >= pages || loading" @click="go(page + 1)">下一页</button>
    </nav>
  </div>
</template>
