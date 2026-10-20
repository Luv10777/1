<script setup>
import { ArrowRight, FilePenLine, Store } from 'lucide-vue-next'
import MerchantProductWall from '../components/MerchantProductWall.vue'
</script>

<template>
  <div class="business-page merchant-alliance-page">
    <header class="business-heading">
      <div><h1>一方好物，一起经营。</h1><p>发现联盟商家的实物商品，也让你的好生意被更多人看见。</p></div>
      <div class="business-heading-links">
        <RouterLink to="/information" class="business-link">浏览商家信息<ArrowRight :size="15" aria-hidden="true" /></RouterLink>
        <RouterLink :to="{ name: 'merchant-join' }" class="business-button business-button-primary">成为一方商家<ArrowRight :size="15" aria-hidden="true" /></RouterLink>
      </div>
    </header>
    <MerchantProductWall />
    <RouterLink :to="{ name: 'merchant-join' }" class="merchant-next-step alliance-join-strip"><Store :size="23" aria-hidden="true" /><div><strong>想把自家商品放进来？</strong><p>提交经营资料，审核通过后管理商品、接收订单并发布商家信息。</p></div><ArrowRight :size="17" aria-hidden="true" /></RouterLink>
    <RouterLink to="/information" class="merchant-next-step"><FilePenLine :size="23" aria-hidden="true" /><div><strong>逛逛商家信息广场</strong><p>了解门店活动、合作机会与供求需求。</p></div><ArrowRight :size="17" aria-hidden="true" /></RouterLink>
  </div>
</template>
