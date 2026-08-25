<script setup>
import { computed } from 'vue'
import { commerce } from '../stores/commerce.js'
import { OFFERING_KINDS } from '../domain/commerce.js'

const formatPrice = (minor) => `¥${(minor / 100).toFixed(2)}`
const isService = (offering) => offering.kind === OFFERING_KINDS.SERVICE
const cartItems = computed(() => commerce.cart.items)
</script>

<template>
  <main class="consumer-page">
    <header class="consumer-nav">
      <a class="consumer-brand" href="/consumer" aria-label="梧曜星枢消费者预览首页">
        <span class="consumer-brand-mark"><i /><i /><i /></span>
        <span><strong>梧曜星枢</strong><small>WUYAO NEXUS / PREVIEW</small></span>
      </a>
      <div class="consumer-nav-actions">
        <span class="preview-pill"><b /> 演示模式 · 未接入支付</span>
        <button class="consumer-cart-button" type="button" @click="commerce.message = commerce.cartCount ? '接力单已展开，请确认下方商品与服务。' : '接力单还是空的。'">接力单 <em>{{ commerce.cartCount }}</em></button>
      </div>
    </header>

    <section class="consumer-context">
      <div class="context-backdrop" />
      <div class="context-copy">
        <p class="consumer-kicker">青岚茶事 · 杭州城西店 <span>／</span> CAMPAIGN ENTRY</p>
        <h1>给今天留一段<br /><em>不赶时间的下午。</em></h1>
        <p class="context-intro">从一杯清爽的冷萃，到一个预约好的慢时段。AI 可以帮你挑选，但每一个订单动作都会回到清楚、可确认的页面。</p>
        <div class="context-source"><span class="source-dot" /> 本次入口来自「夏日到店」内容 · 小红书</div>
      </div>
      <div class="context-orbit" aria-hidden="true"><span class="orbit-line orbit-one" /><span class="orbit-line orbit-two" /><span class="orbit-core">青</span><span class="orbit-node node-one" /><span class="orbit-node node-two" /></div>
    </section>

    <section class="consumer-body">
      <div class="body-heading"><div><p class="consumer-kicker">01 / AI CONCIERGE</p><h2>先说你的下午<br /><span>想要什么感觉？</span></h2></div><p>AI 只基于已确认的商品、价格、门店和档期回答。需要下单时，会把你送进确定性接力页。</p></div>

      <div class="concierge-strip">
        <div class="concierge-avatar">✦</div>
        <div class="concierge-copy"><strong>“我想找一个适合两个人、不会太甜的周末下午。”</strong><span>已识别：双人 · 周末 · 清爽口感</span></div>
        <button class="concierge-action" type="button" @click="commerce.selectOffering(commerce.offerings[0])">查看推荐 <span>→</span></button>
      </div>

      <div class="catalog-heading"><div><p class="consumer-kicker">02 / CURATED OFFERS</p><h2>给你的选择</h2></div><span class="catalog-note">{{ commerce.offerings.length }} 项可用 · 价格与库存实时重算</span></div>
      <div class="offering-grid">
        <article v-for="offering in commerce.offerings" :key="offering.id" class="offering-card" :class="`offering-${offering.cover}`" :data-selected="commerce.selectedOffering?.id === offering.id">
          <div class="offering-art"><span>{{ offering.cover === 'tea' ? '双人' : offering.cover === 'peach' ? '冷萃' : '60 MIN' }}</span><i>{{ isService(offering) ? '预约' : '到店' }}</i></div>
          <div class="offering-copy"><p>{{ offering.category }}</p><h3>{{ offering.name }}</h3><span>{{ offering.summary }}</span><div class="offering-foot"><strong>{{ formatPrice(offering.priceMinor) }}</strong><small>{{ isService(offering) ? `${offering.durationMinutes} 分钟 · ${offering.slots.length} 个时段` : `余量 ${offering.inventory}` }}</small></div></div>
          <button class="offering-select" type="button" @click="commerce.selectOffering(offering)">{{ commerce.selectedOffering?.id === offering.id ? '已选中' : '了解这项' }} <span>↗</span></button>
        </article>
      </div>

      <section v-if="commerce.selectedOffering" class="selection-panel">
        <div><p class="consumer-kicker">03 / HANDOFF PREVIEW</p><h2>{{ commerce.selectedOffering.name }}</h2><p>{{ commerce.selectedOffering.summary }}</p></div>
        <div v-if="isService(commerce.selectedOffering)" class="slot-picker"><span>选择到店时段</span><button v-for="slot in commerce.selectedOffering.slots" :key="slot" type="button" :class="{ active: commerce.selectedSlot === slot }" @click="commerce.selectedSlot = slot">{{ slot }}</button></div>
        <div class="selection-actions"><button class="secondary-action" type="button" @click="commerce.addSelected">加入接力单 <span>＋</span></button><button class="primary-action" type="button" :disabled="!commerce.cartCount" @click="commerce.createOrder">创建待支付订单 <span>→</span></button></div>
      </section>

      <section class="handoff-panel" :class="{ visible: commerce.order }">
        <div class="handoff-stamp">HANDOFF<br /><strong>READY</strong></div>
        <div class="handoff-copy"><p class="consumer-kicker">确定性接力页</p><h2>{{ commerce.order ? '你的接力单已经准备好。' : 'AI 推荐不会替你越过确认。' }}</h2><p>{{ commerce.order ? `订单草稿 ${commerce.order.id} · 应付 ${formatPrice(commerce.order.totalMinor)}。下一步应重新校验价格、库存与身份。` : '选择商品或服务后，系统会生成一个短期、一次性、带商家归属的页面引用。' }}</p></div>
        <div v-if="commerce.handoff" class="handoff-token"><span>HANDOFF TOKEN</span><code>{{ commerce.handoff.token }}</code><small>仅 Mock 引用 · TTL 5 min · 一次性消费</small></div>
        <button v-if="commerce.order" class="secondary-action" type="button" @click="commerce.resetOrder">重新选择</button>
      </section>
      <p v-if="commerce.message" class="consumer-message" role="status">{{ commerce.message }}</p>
    </section>

    <footer class="consumer-footer"><span>统一消费者入口 · 多商家 / 多门店上下文</span><span>真实微信登录、Handoff、支付与核销尚未接入</span></footer>
  </main>
</template>

