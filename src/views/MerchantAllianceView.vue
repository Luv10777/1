<script setup>
import MerchantProductWall from '../components/MerchantProductWall.vue'
</script>

<template>
  <div class="business-page merchant-alliance-page">
    <header class="business-heading">
      <div><h1>一方好物，一起经营。</h1><p>发现联盟商家的实物商品，也让你的好生意被更多人看见。</p></div>
      <div class="business-heading-links">
        <RouterLink to="/information" class="business-link">浏览商家信息</RouterLink>
        <RouterLink :to="{ name: 'merchant-join' }" class="business-button business-button-primary">加入商户联盟</RouterLink>
      </div>
    </header>
    <MerchantProductWall />
  </div>
</template>
