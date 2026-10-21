<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ExternalLink, Printer, Scale } from 'lucide-vue-next'
import { LEGAL_ENTITY, LEGAL_POLICY_NAVIGATION, LEGAL_POLICY_UPDATED_AT, legalPolicies } from '../domain/legalPolicies.js'
import '../legal.css'
import { auth } from '../stores/auth'
import { inDesktop } from '../utils/desktop'

const route = useRoute()
const page = ref(null)
const policy = computed(() => legalPolicies[route.params.policy] || legalPolicies.terms)
const returnLocation = computed(() => ({
  name: auth.isAuthenticated ? (inDesktop ? 'digital-human' : 'image-create') : 'login',
  query: {
    ...(typeof route.query.redirect === 'string' ? { redirect: route.query.redirect } : {}),
    ...(route.query.mode === 'register' ? { mode: 'register' } : {}),
  },
}))
const returnLabel = computed(() => auth.isAuthenticated ? '返回平台' : route.query.mode === 'register' ? '返回注册' : '返回登录')

function resetReadingPosition() {
  document.title = `${policy.value.title} · 一方志`
  page.value?.closest('.site-content')?.scrollTo({ top: 0, behavior: 'instant' })
}
onMounted(resetReadingPosition)
watch(() => policy.value.slug, async () => { await nextTick(); resetReadingPosition() })

const articleId = index => `policy-${policy.value.slug}-${index + 1}`

function printPolicy() {
  window.print()
}

function scrollToArticle(index) {
  const section = document.getElementById(articleId(index))
  const container = page.value?.closest('.site-content')
  if (!section || !container) return
  container.scrollTo({ top: container.scrollTop + section.getBoundingClientRect().top - container.getBoundingClientRect().top - 85, behavior: 'instant' })
  section.focus({ preventScroll: true })
}
</script>

<template>
  <main ref="page" class="legal-page">
    <header class="legal-topbar">
      <RouterLink class="legal-brand" :to="returnLocation" aria-label="返回一方志"><span class="legal-brand-seal"><img src="/images/brand/yifangzhi-mark.png" alt="" /></span><span><strong>一方志</strong><small>平台协议与服务规则</small></span></RouterLink>
      <RouterLink class="legal-return" :to="returnLocation">{{ returnLabel }}</RouterLink>
    </header>

    <div class="legal-container">
      <header class="legal-heading">
        <p class="legal-eyebrow"><Scale :size="16" aria-hidden="true" />一方志 · 协议中心</p>
        <div class="legal-title-row">
          <div><h1>{{ policy.title }}</h1><p class="legal-summary">{{ policy.summary }}</p></div>
          <button class="legal-print" type="button" title="打印或保存为 PDF" aria-label="打印或保存协议为 PDF" @click="printPolicy"><Printer :size="17" aria-hidden="true" /><span>打印</span></button>
        </div>
        <p class="legal-version">更新日期：{{ LEGAL_POLICY_UPDATED_AT }} <span>·</span> {{ policy.effective }}</p>
      </header>

      <nav class="legal-mobile-nav" aria-label="选择协议">
        <RouterLink v-for="item in LEGAL_POLICY_NAVIGATION" :key="item.slug" :to="{ name: 'legal-policy', params: { policy: item.slug }, query: route.query }" :aria-current="policy.slug === item.slug ? 'page' : undefined" :class="{ active: policy.slug === item.slug }">{{ item.shortTitle }}</RouterLink>
      </nav>

      <div class="legal-layout">
        <aside class="legal-sidebar" aria-label="协议目录">
          <nav class="legal-document-nav" aria-label="全部协议">
            <RouterLink v-for="item in LEGAL_POLICY_NAVIGATION" :key="item.slug" :to="{ name: 'legal-policy', params: { policy: item.slug }, query: route.query }" :aria-current="policy.slug === item.slug ? 'page' : undefined" :class="{ active: policy.slug === item.slug }">{{ item.shortTitle }}</RouterLink>
          </nav>
          <nav class="legal-article-nav" aria-label="本协议章节"><span>本协议</span><a v-for="(section, index) in policy.sections" :key="section.title" :href="`#${articleId(index)}`" @click.prevent="scrollToArticle(index)">{{ String(index + 1).padStart(2, '0') }} {{ section.title }}</a></nav>
        </aside>

        <article class="legal-document" aria-label="协议全文">
          <section v-for="(section, index) in policy.sections" :id="articleId(index)" :key="section.title" class="legal-article" tabindex="-1">
            <h2><span>{{ String(index + 1).padStart(2, '0') }}</span>{{ section.title }}</h2>
            <p v-for="paragraph in section.paragraphs" :key="paragraph">{{ paragraph }}</p>
          </section>

          <section class="legal-references" aria-labelledby="legal-references-title">
            <h2 id="legal-references-title">法律依据与官方资料</h2>
            <p class="legal-references-intro">以下法规依据发布机关或政府法定信息平台查证；具体条文适用以生效的现行法律为准。</p>
            <a v-for="reference in policy.citations" :key="reference.title" :href="reference.url" target="_blank" rel="noopener noreferrer"><span><strong>{{ reference.title }}</strong><small>{{ reference.organization }}</small></span><ExternalLink :size="14" aria-hidden="true" /></a>
          </section>

          <section class="legal-operator" aria-label="运营主体与联系渠道">
            <h2>运营主体与联系渠道</h2>
            <dl><div><dt>运营主体</dt><dd>{{ LEGAL_ENTITY.name }}</dd></div><div><dt>统一社会信用代码</dt><dd>{{ LEGAL_ENTITY.creditCode }}</dd></div><div><dt>注册地址</dt><dd>{{ LEGAL_ENTITY.address }}</dd></div><div><dt>客服、售后与隐私事务联系</dt><dd><a :href="`tel:${LEGAL_ENTITY.phone}`">{{ LEGAL_ENTITY.phone }}</a></dd></div></dl>
            <p>涉及个人信息查询、修改、删除、撤回同意、账号处理、交易售后、侵权通知或内容投诉，可拨打上述公示电话。提出申请时请说明账号和具体问题；无需提供短信验证码、密码或与事项无关的完整身份证照片。</p>
            <RouterLink class="legal-back-link" :to="returnLocation">阅读完毕，{{ returnLabel }}</RouterLink>
          </section>
        </article>
      </div>

      <footer class="legal-site-footer"><span>一方志 · 平台协议与服务规则</span><a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">浙ICP备2026081720号</a></footer>
    </div>
  </main>
</template>
