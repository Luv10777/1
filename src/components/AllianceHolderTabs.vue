<script setup>
import { useRoute } from 'vue-router'

// 领券中心的两个标签：券库（领券）和我的联盟券（出示券码、申诉）。两个网址都保留，通知里的链接可以直达。
const route = useRoute()
</script>

<template>
  <nav class="alliance-tabs alliance-holder-tabs" aria-label="领券中心">
    <RouterLink :to="{ name: 'alliance-pool' }" :class="{ active: route.name === 'alliance-pool' }" :aria-current="route.name === 'alliance-pool' ? 'page' : undefined">券库</RouterLink>
    <RouterLink :to="{ name: 'alliance-vouchers' }" :class="{ active: route.name === 'alliance-vouchers' }" :aria-current="route.name === 'alliance-vouchers' ? 'page' : undefined">我的联盟券</RouterLink>
  </nav>
</template>
