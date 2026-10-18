<script setup>
import { LEGAL_ENTITY } from '../domain/legalPolicies'
</script>

<template>
  <footer class="site-footer" aria-label="网站备案及服务信息">
    <div class="site-footer-row site-footer-identity">
      <span>ICP备案号：<a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">浙ICP备2026081720号</a></span>
      <span>运营主体：{{ LEGAL_ENTITY.name }}</span>
    </div>
    <div class="site-footer-row site-footer-service">
      <nav class="site-legal-links" aria-label="平台协议与售后服务"><RouterLink to="/policies/terms" target="_blank" rel="noopener noreferrer">用户注册协议</RouterLink><RouterLink to="/policies/privacy" target="_blank" rel="noopener noreferrer">隐私协议</RouterLink><RouterLink to="/policies/merchant" target="_blank" rel="noopener noreferrer">商家入驻</RouterLink><RouterLink to="/policies/aftersales" target="_blank" rel="noopener noreferrer">售后与退款</RouterLink></nav>
      <a :href="`tel:${LEGAL_ENTITY.phone}`">人工客服：{{ LEGAL_ENTITY.phone }}</a>
    </div>
  </footer>
</template>

<style scoped>
.site-footer { --site-footer-link: #245fa6; display: flex; flex: none; flex-direction: column; justify-content: center; align-items: center; gap: 2px; padding: 12px 16px max(12px, env(safe-area-inset-bottom)); border-top: 1px solid var(--line); background: var(--night); color: var(--ink-muted); font-size: 12px; line-height: 1.8; text-align: center; }
.site-footer-row { display: flex; flex-wrap: wrap; justify-content: center; align-items: center; gap: 2px 20px; }
.site-footer-service { gap: 3px 16px; }
.site-legal-links { display: flex; flex-wrap: wrap; justify-content: center; gap: 3px 14px; }
.site-footer a { color: var(--site-footer-link); white-space: nowrap; text-decoration: underline; text-underline-offset: 3px; }
.site-footer a:hover, .site-footer a:focus-visible { text-decoration-thickness: 2px; }
:global(:root.dark) .site-footer, :global([data-theme='dark']) .site-footer { --site-footer-link: #8dbdff; }
@media print { .site-footer { display: none; } }
</style>
