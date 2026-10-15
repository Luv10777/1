<script setup>
import { computed } from 'vue'

const props = defineProps({ content: { type: String, default: '' } })

function splitLinks(text) {
  const parts = []
  let cursor = 0
  for (const match of text.matchAll(/https?:\/\/[^\s<>"']+/g)) {
    const href = match[0].replace(/[。，、；：！？）》」』,.!?;:)\]]+$/u, '')
    parts.push({ text: text.slice(cursor, match.index) })
    try {
      const url = new URL(href)
      const internal = url.origin === 'https://yifangzhi.com'
      parts.push({ text: href, href, to: internal ? url.pathname + url.search + url.hash : null })
    } catch {
      parts.push({ text: href })
    }
    cursor = match.index + href.length
  }
  parts.push({ text: text.slice(cursor) })
  return parts
}

const paragraphs = computed(() => props.content.trim().split(/\r?\n\s*\r?\n/).filter(Boolean).map(splitLinks))
</script>

<template>
  <div class="information-article-body">
    <p v-for="(paragraph, index) in paragraphs" :key="index"><template v-for="(part, partIndex) in paragraph" :key="partIndex"><RouterLink v-if="part.to" :to="part.to">{{ part.text }}</RouterLink><a v-else-if="part.href" :href="part.href" target="_blank" rel="noopener noreferrer">{{ part.text }}<span class="sr-only">（在新窗口打开）</span></a><template v-else>{{ part.text }}</template></template></p>
  </div>
</template>

<style scoped>
.information-article-body { padding: 12px 0 24px; font-size: 15px; line-height: 2.05; overflow-wrap: anywhere; }
.information-article-body p { margin: 24px 0; white-space: pre-line; }
.information-article-body a { color: var(--color-accent-text); text-decoration: underline; text-underline-offset: 4px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }
:global(.business-preview-body) .information-article-body { padding: 0; font: inherit; white-space: normal; }
:global(.business-preview-body) .information-article-body p { margin: 0 0 14px; }
</style>
