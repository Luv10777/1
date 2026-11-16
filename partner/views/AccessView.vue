<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, Store } from 'lucide-vue-next'
import { auth } from '../../src/stores/auth.js'

const route = useRoute()
const busy = ref(false)
const forbidden = computed(() => route.name === 'partner-forbidden')
async function switchAccount() {
  busy.value = true
  try { await auth.logout(); window.location.assign('/login?redirect=%2Fpartner%2Foverview') }
  finally { busy.value = false }
}
</script>

<template>
  <main class="admin-access-state management-page">
    <Store :size="32" />
    <p class="admin-login-kicker">服务商后台</p>
    <h1>{{ forbidden ? '这个账号不能进入服务商后台' : '页面不存在' }}</h1>
    <p>{{ forbidden ? (route.query.reason || '只有服务商的成员可以进入。') + ' 你的 UID：' + (auth.user?.uid || '—') + '。成员请让负责人用你的手机号把你加进团队。' : '请检查地址，或返回后台首页。' }}</p>
    <div>
      <a class="mg-button" href="/"><ArrowLeft :size="15" />回到一方志</a>
      <button v-if="forbidden" class="mg-button" type="button" :disabled="busy" @click="switchAccount">换一个账号登录</button>
    </div>
  </main>
</template>
