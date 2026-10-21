<script setup>
import AllianceVoucherCenter from '../components/AllianceVoucherCenter.vue'
import AllianceHolderTabs from '../components/AllianceHolderTabs.vue'
</script>

<template>
  <div class="business-page alliance-pool-page">
    <header class="business-heading">
      <div><h1>领券中心</h1><p>会员期内用额度领取其他联盟商户的商品兑换券，到店出示券码使用。</p></div>
    </header>
    <AllianceHolderTabs />
    <AllianceVoucherCenter />
  </div>
</template>
