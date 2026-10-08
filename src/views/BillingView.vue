<script setup>
import { computed, ref } from 'vue'
import { billing } from '../stores/billing.js'

const formatMoney = (minor) => minor === 0 ? '免费' : `¥${(minor / 100).toLocaleString('zh-CN')}/月`
const planCards = computed(() => billing.plans.map((plan) => ({ ...plan, current: plan.id === billing.currentPlan.id })))
const livePlans = [
  { id: 'hourly', name: '按小时充值', price: '¥12', unit: '/ 小时', hours: '10 小时起充', desc: '偶尔开播、先试水的商家', perks: ['随充随用，永久有效', '标准话术引擎', '弹幕自动回复'] },
  { id: 'month', name: '月卡', price: '¥399', unit: '/ 月', hours: '含 60 小时', desc: '每天固定时段开播', perks: ['单价低至 ¥6.6/小时', '多音色轮换', '知识库 500 条', '优先算力队列'] },
  { id: 'quarter', name: '季卡', price: '¥1,059', unit: '/ 季', hours: '含 210 小时', desc: '多门店、长时段无人播', perks: ['单价低至 ¥5.0/小时', '全部月卡权益', '知识库不限条数', '专属运营顾问'] },
]
const comparedLivePlan = ref(null)
const plannedDailyHours = ref(6)
const estimatedMonthlyHours = computed(() => plannedDailyHours.value * 30)
const meters = [
  { code: 'AI_IMAGE', label: '图片生成', unit: '张', color: 'cinnabar' },
  { code: 'AI_VIDEO', label: '视频任务', unit: '条', color: 'cyan' },
  { code: 'STORES', label: '门店数', unit: '家', color: 'amber' },
  { code: 'AUTOMATION', label: '自动化流程', unit: '条', color: 'green' },
]
</script>

<template>
  <div class="billing-page">
    <div class="page-heading billing-heading"><div><p class="eyebrow">SAAS COMMERCIAL / ENTITLEMENTS</p><h1>套餐与权益，先把边界看清楚。</h1><p class="page-intro">这里展示的是商家 SaaS 账务域：订阅版本、权益快照、点数水位和下一步动作。消费者订单、支付和退款不在此处混用。</p></div><span class="billing-boundary"><span class="status-pulse" /> SaaS BILLING / MOCK</span></div>

    <section class="billing-hero panel-dark"><div><p class="eyebrow accent">CURRENT SUBSCRIPTION</p><h2>{{ billing.currentPlan.displayName }} <span>· {{ billing.currentPlan.version }}</span></h2><p>本周期至 2026 年 9 月 25 日。升级将创建待确认咨询，不会在浏览器端直接扣款。</p><div class="billing-hero-actions"><button class="primary-button compact" type="button" @click="billing.message = '续费提醒草稿已创建，等待服务端 SaaS 订单能力接入。'">查看续费选项 <span>→</span></button><span class="billing-state">ACTIVE / <i>按月</i></span></div></div><div class="credit-orbit"><div><strong>{{ billing.balance }}</strong><span>PLATFORM CREDITS</span></div><i class="credit-ring ring-a" /><i class="credit-ring ring-b" /></div></section>

    <section class="billing-meter-grid"><article v-for="meter in meters" :key="meter.code" class="billing-meter panel" :class="`meter-${meter.color}`"><div class="meter-top"><div><p class="eyebrow">{{ meter.code }}</p><h3>{{ meter.label }}</h3></div><button type="button" aria-label="记录一次用量" @click="billing.simulateUsage(meter.code)">＋</button></div><div class="meter-number"><strong>{{ billing.getEntitlement(meter.code).used }}</strong><span>/ {{ billing.getEntitlement(meter.code).limit }} {{ meter.unit }}</span></div><div class="meter-track"><span :style="{ width: `${Math.min(100, (billing.getEntitlement(meter.code).used / billing.getEntitlement(meter.code).limit) * 100)}%` }" /></div><p class="meter-foot">剩余 {{ billing.getEntitlement(meter.code).remaining }} {{ meter.unit }} <span>来源 · {{ billing.getEntitlement(meter.code).source }}</span></p></article></section>

    <section class="billing-grid"><article class="panel plan-panel"><div class="panel-heading"><div><p class="eyebrow">VERSIONED PLAN CATALOG</p><h3>选择下一段增长容量</h3></div><span class="mono muted-text">历史版本不可变</span></div><div class="plan-list"><div v-for="plan in planCards" :key="plan.id" class="plan-row" :class="{ selected: plan.current }"><div class="plan-mark">{{ plan.planCode === 'TRIAL' ? 'T' : plan.planCode === 'GROWTH' ? 'G' : 'E' }}</div><div class="plan-copy"><strong>{{ plan.displayName }}</strong><span>{{ plan.version }} · {{ plan.entitlements.STORES.limit }} 家门店 · {{ plan.entitlements.AI_IMAGE.limit }} 张图片</span></div><b>{{ formatMoney(plan.prices[0].amountMinor) }}</b><button v-if="!plan.current" type="button" @click="billing.requestUpgrade(plan)">咨询升级 <span>→</span></button><em v-else>当前使用</em></div></div></article><article class="panel ledger-panel"><div class="panel-heading"><div><p class="eyebrow">CREDIT LEDGER</p><h3>最近点数流水</h3></div><span class="ledger-domain">不可变 / 可重建</span></div><div class="ledger-list"><div v-for="entry in billing.ledgerEntries" :key="entry.id" class="ledger-row"><span class="ledger-glyph" :class="entry.amount < 0 ? 'out' : 'in'">{{ entry.amount < 0 ? '−' : '+' }}</span><div><strong>{{ entry.reason || entry.type }}</strong><small>{{ entry.type }} · {{ entry.createdAt?.slice(0, 10) || '本地演示' }}</small></div><b :class="entry.amount < 0 ? 'out' : 'in'">{{ entry.amount > 0 ? '+' : '' }}{{ entry.amount }}</b></div></div></article></section>
    <section class="billing-live-plans" aria-labelledby="billing-live-title">
      <div class="panel-heading"><div><p class="eyebrow">LIVE COMPUTE / PREVIEW</p><h3 id="billing-live-title">直播云算力套餐 <small>（预览）</small></h3></div></div>
      <div class="billing-live-overview">
        <div><span class="eyebrow">计费状态</span><strong>尚未接入</strong></div>
        <label>每天计划开播<select v-model.number="plannedDailyHours"><option v-for="hours in [2, 4, 6, 8, 12]" :key="hours" :value="hours">{{ hours }} 小时</option></select></label>
        <p>按 30 天估算每月约 {{ estimatedMonthlyHours }} 小时。仅供对比方案；购买、余额和实际扣费尚未接入。</p>
      </div>
      <div class="billing-live-grid">
        <article v-for="plan in livePlans" :key="plan.id" class="billing-live-card" :class="{ selected: comparedLivePlan === plan.id }">
          <h4>{{ plan.name }}</h4>
          <p class="billing-live-price"><strong>{{ plan.price }}</strong><small>{{ plan.unit }}</small></p>
          <p class="billing-live-hours">{{ plan.hours }}</p>
          <p class="billing-live-desc">{{ plan.desc }}</p>
          <ul><li v-for="perk in plan.perks" :key="perk">{{ perk }}</li></ul>
          <button type="button" :aria-pressed="comparedLivePlan === plan.id" @click="comparedLivePlan = plan.id">{{ comparedLivePlan === plan.id ? '正在对比' : '对比方案' }}</button>
        </article>
      </div>
    </section>
    <p v-if="billing.message" class="billing-message" role="status">{{ billing.message }}</p>
    <footer class="billing-footer"><span>账务域隔离：SaaS Billing ≠ Consumer Commerce</span><span>价格、权益、点数和支付均为版本化服务端待接入契约</span></footer>
  </div>
</template>
