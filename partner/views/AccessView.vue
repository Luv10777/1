<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Store } from 'lucide-vue-next'
import { auth } from '../../src/stores/auth.js'

const route = useRoute()
const router = useRouter()
const busy = ref(false)
const forbidden = computed(() => route.name === 'partner-forbidden')
async function switchAccount() {
  busy.value = true
  try { await auth.logout(); await router.replace({ name: 'partner-login' }) }
  finally { busy.value = false }
}
</script>

<template>
  <main class="admin-access-state management-page">
    <Store :size="32" />
    <p class="admin-login-kicker">服务商后台</p>
    <h1>{{ forbidden ? '这个账号不能进入服务商后台' : '页面不存在' }}</h1>
    <p>{{ forbidden ? (route.query.reason || '只有平台开通的服务商账号可以登录。') + ' 你的用户 ID：' + (auth.user?.uid || '—') + '，开通时请提供给平台。' : '请检查地址，或返回后台首页。' }}</p>
    <div><button class="mg-button" type="button" :disabled="busy" @click="switchAccount"><ArrowLeft :size="15" />{{ auth.isAuthenticated ? '换一个账号登录' : '返回登录' }}</button></div>
  </main>
</template>
