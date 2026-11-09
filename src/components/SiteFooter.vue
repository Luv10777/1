<script setup>
import { LEGAL_ENTITY } from '../domain/legalPolicies'

const COPYRIGHT_YEAR = 2026
</script>

<template>
  <footer class="site-footer" aria-label="网站备案及服务信息">
    <nav class="site-footer-row site-footer-links" aria-label="平台协议与售后服务">
      <RouterLink to="/policies/terms" target="_blank" rel="noopener noreferrer">用户注册协议</RouterLink>
      <RouterLink to="/policies/privacy" target="_blank" rel="noopener noreferrer">隐私协议</RouterLink>
      <RouterLink to="/policies/merchant" target="_blank" rel="noopener noreferrer">商家入驻</RouterLink>
      <RouterLink to="/policies/aftersales" target="_blank" rel="noopener noreferrer">售后与退款</RouterLink>
      <a :href="`tel:${LEGAL_ENTITY.phone}`">人工客服 {{ LEGAL_ENTITY.phone }}</a>
    </nav>
    <div class="site-footer-row site-footer-identity">
      <span>运营主体：{{ LEGAL_ENTITY.name }}</span>
      <span><a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">浙ICP备2026081720号</a></span>
    </div>
    <p class="site-footer-copyright">© {{ COPYRIGHT_YEAR }} 一方志 · 保留所有权利</p>
  </footer>
</template>

<style scoped>
.site-footer { --site-footer-link: #245fa6; display: flex; flex: none; flex-direction: column; justify-content: center; align-items: center; gap: 6px; padding: 16px 16px max(16px, env(safe-area-inset-bottom)); border-top: 1px solid var(--line); background: var(--night); color: var(--ink-muted); font-size: 12px; line-height: 1.7; text-align: center; }
.site-footer-row { display: flex; flex-wrap: wrap; justify-content: center; align-items: center; column-gap: 0; row-gap: 4px; }
.site-footer-row > * + *::before { content: ''; display: inline-block; width: 1px; height: 11px; margin: 0 12px; vertical-align: -1px; background: var(--line); }
.site-footer-identity { color: var(--ink-muted); }
.site-footer-copyright { margin: 2px 0 0; font-size: 11px; letter-spacing: 0.02em; opacity: 0.72; }
.site-footer a { color: var(--site-footer-link); white-space: nowrap; text-decoration: none; }
.site-footer a:hover, .site-footer a:focus-visible { text-decoration: underline; text-underline-offset: 3px; }
:global(:root.dark .site-footer), :global([data-theme='dark'] .site-footer) { --site-footer-link: #8dbdff; }
@media print { .site-footer { display: none; } }
</style>
