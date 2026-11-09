<script setup>
import { Headset } from 'lucide-vue-next'
import { LEGAL_ENTITY } from '../domain/legalPolicies'

const phoneDisplay = LEGAL_ENTITY.phone.replace(/^(\d{3})(\d{4})(\d{4})$/, '$1 $2 $3')
</script>

<template>
  <footer class="site-footer" aria-label="网站备案及服务信息">
    <div class="site-footer-identity">
      <span class="site-footer-wordmark">一方志</span>
      <span>运营主体：{{ LEGAL_ENTITY.name }}</span>
      <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">浙ICP备2026081720号</a>
    </div>
    <div class="site-footer-service">
      <nav class="site-legal-links" aria-label="平台协议与售后服务"><RouterLink to="/policies/terms" target="_blank" rel="noopener noreferrer">用户注册协议</RouterLink><RouterLink to="/policies/privacy" target="_blank" rel="noopener noreferrer">隐私协议</RouterLink><RouterLink to="/policies/merchant" target="_blank" rel="noopener noreferrer">商家入驻</RouterLink><RouterLink to="/policies/aftersales" target="_blank" rel="noopener noreferrer">售后与退款</RouterLink></nav>
      <a class="site-footer-phone" :href="`tel:${LEGAL_ENTITY.phone}`" :aria-label="`人工客服：${LEGAL_ENTITY.phone}`"><Headset :size="13" aria-hidden="true" />人工客服 <span>{{ phoneDisplay }}</span></a>
    </div>
  </footer>
</template>

<style scoped>
.site-footer { display: flex; flex: none; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 8px 24px; padding: 14px 32px max(14px, env(safe-area-inset-bottom)); border-top: 1px solid var(--line); background: var(--night); color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
.site-footer-identity { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 14px; }
.site-footer-wordmark { color: var(--ink); font-family: var(--font-serif); font-size: 13px; font-weight: 700; letter-spacing: .08em; }
.site-footer-service { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; }
.site-legal-links { display: flex; flex-wrap: wrap; align-items: center; row-gap: 4px; }
.site-legal-links a + a::before { content: ''; display: inline-block; width: 1px; height: 10px; margin: 0 12px; background: var(--line-strong); vertical-align: -1px; }
.site-footer a { color: inherit; white-space: nowrap; }
.site-footer a:hover, .site-footer a:focus-visible { color: var(--ink); }
.site-legal-links a:hover, .site-legal-links a:focus-visible, .site-footer-identity a:hover, .site-footer-identity a:focus-visible { text-decoration: underline; text-underline-offset: 3px; }
.site-footer-phone { display: inline-flex; align-items: center; gap: 6px; padding: 3px 11px 3px 9px; border: 1px solid var(--line-strong); border-radius: 999px; background: var(--night-panel); color: var(--ink) !important; }
.site-footer-phone span { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.site-footer-phone:hover, .site-footer-phone:focus-visible { border-color: var(--ink-muted); }
@media (max-width: 640px) {
  .site-footer { flex-direction: column; align-items: flex-start; gap: 12px; padding: 16px 16px max(16px, env(safe-area-inset-bottom)); }
  .site-footer-identity { order: 1; flex-direction: column; align-items: flex-start; gap: 0; }
  .site-footer-service { flex-direction: column-reverse; align-items: flex-start; gap: 12px; }
}
@media print { .site-footer { display: none; } }
</style>
