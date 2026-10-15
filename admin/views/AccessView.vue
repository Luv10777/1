<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ShieldCheck, RefreshCw } from 'lucide-vue-next'
import { auth } from '../../src/stores/auth.js'
import { adminDestination } from '../router.js'

const route = useRoute()
const router = useRouter()
const busy = ref(false)
const unavailable = computed(() => route.name === 'admin-unavailable')
const forbidden = computed(() => route.name === 'admin-forbidden')
async function retry() {
  busy.value = true
  try { await router.replace(adminDestination(route.query.redirect)) }
  finally { busy.value = false }
}
async function switchAccount() {
  busy.value = true
  try { await auth.logout(); await router.replace({ name: 'admin-login' }) }
  finally { busy.value = false }
}
</script>

<template>
  <main class="admin-access-state management-page">
    <ShieldCheck :size="32" />
    <p class="admin-login-kicker">管理后台</p>
    <h1>{{ unavailable ? '暂时无法连接管理服务' : forbidden ? '此账号未获后台授权' : '页面不存在' }}</h1>
    <p>{{ unavailable ? '暂时无法确认管理员权限。请检查后端连接后重试。' : forbidden ? '只有已授权的平台管理员可以查看管理数据。请使用管理员账号登录，或联系后台负责人开通权限。' : '请检查地址，或返回后台入口。' }}</p>
    <div><button v-if="unavailable" class="mg-button mg-primary" type="button" :disabled="busy" @click="retry"><RefreshCw :size="16" :class="{ 'admin-spinning': busy }" />重新连接</button><button class="mg-button" type="button" :disabled="busy" @click="switchAccount"><ArrowLeft :size="15" />{{ auth.isAuthenticated ? '切换管理员账号' : '返回管理员登录' }}</button></div>
  </main>
</template>
