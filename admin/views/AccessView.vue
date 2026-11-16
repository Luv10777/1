<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ShieldCheck, RefreshCw } from 'lucide-vue-next'
import { auth } from '../../src/stores/auth.js'
import { platform } from '../../src/stores/platform.js'
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
    <h1>{{ unavailable ? '暂时无法连接管理服务' : forbidden ? platform.operator ? '当前岗位无权访问此页面' : '此账号未获后台授权' : '页面不存在' }}</h1>
    <p>{{ unavailable ? '暂时无法确认管理员权限。请检查后端连接后重试。' : forbidden ? platform.operator ? '请返回工作台查看当前岗位可用的功能。如需调整权限，请联系后台负责人。' : '请使用已授权的账号登录，或联系后台负责人开通权限。' : '请检查地址，或返回后台入口。' }}</p>
    <div><RouterLink v-if="platform.operator" class="mg-button mg-primary" :to="{ name: 'admin-home' }">返回工作台</RouterLink><button v-if="unavailable" class="mg-button mg-primary" type="button" :disabled="busy" @click="retry"><RefreshCw :size="16" :class="{ 'admin-spinning': busy }" />重新连接</button><button class="mg-button" type="button" :disabled="busy" @click="switchAccount"><ArrowLeft :size="15" />{{ auth.isAuthenticated ? '切换管理员账号' : '返回管理员登录' }}</button></div>
  </main>
</template>
