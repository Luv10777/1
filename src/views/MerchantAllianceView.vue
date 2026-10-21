<script setup>
import { FilePenLine, Store, Ticket } from 'lucide-vue-next'
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
    <RouterLink :to="{ name: 'alliance-pool' }" class="merchant-next-step alliance-join-strip"><Ticket :size="23" aria-hidden="true" /><div><strong>会员每期可领联盟券</strong><p>用会员额度领取其他联盟商户的商品兑换券，到店出示券码使用。</p></div></RouterLink>
    <RouterLink :to="{ name: 'merchant-join' }" class="merchant-next-step"><Store :size="23" aria-hidden="true" /><div><strong>想把自家商品放进来？</strong><p>加入商户联盟：提交经营资料，审核通过后可以线上卖货、发布商家信息、提供联盟券。</p></div></RouterLink>
    <RouterLink to="/information" class="merchant-next-step"><FilePenLine :size="23" aria-hidden="true" /><div><strong>逛逛商家信息广场</strong><p>了解门店活动、合作机会与供求需求。</p></div></RouterLink>
  </div>
</template>
