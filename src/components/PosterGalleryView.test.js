import test from 'node:test'
import assert from 'node:assert/strict'
import { createSSRApp, h, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createServer } from 'vite'

test('poster gallery offers an answer box only while the current turn waits for input', async () => {
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  try {
    const { default: Gallery } = await vite.ssrLoadModule('/src/components/PosterGalleryView.vue')
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:path(.*)*', component: { render: () => null } }] })
    await router.push('/image/create/poster')
    const turn = { id: 9, parentId: null, brief: '新店开业海报', status: 'NEEDS_INPUT', question: '开业日期是哪天？',
      quality: '2K', ratio: '3:4', completed: 0, count: 1, items: [], createdAt: '2026-10-20T00:00:00Z' }
    const studio = reactive({ workspaceTab: 'result', history: [], thread: [turn], current: turn, busy: false, pending: null,
      configured: true, revision: '', error: '', optimistic: false, locked: false, style: '', dimensions: { width: 1536, height: 2048 } })
    const render = () => renderToString(createSSRApp({ render: () => h(Gallery, { studio }) }).use(router))

    let html = await render()
    assert.match(html, /开业日期是哪天？/)
    assert.match(html, /<button[^>]*disabled[^>]*>补充并继续/, 'an empty answer must not be submittable')
    studio.revision = '10 月 1 日'
    html = await render()
    assert.match(html, /<button type="submit" class="poster-conversation-submit"(?![^>]*disabled)[^>]*>补充并继续/)

    studio.current = { ...turn, status: 'SUCCEEDED', question: '' }
    studio.thread = [studio.current]
    html = await render()
    assert.doesNotMatch(html, /补充并继续/)
  } finally { await vite.close() }
})


test('drawing controls and style gallery describe general images without a purpose picker', async () => {
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  try {
    const { default: Control } = await vite.ssrLoadModule('/src/components/PosterControlPanel.vue')
    const { default: Gallery } = await vite.ssrLoadModule('/src/components/PosterGalleryView.vue')
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:path(.*)*', component: { render: () => null } }] })
    await router.push('/image/create/poster')
    const studio = reactive({ workspaceTab: 'inspiration', history: [], thread: [], current: null, busy: false, pending: null,
      configured: true, capabilityState: 'ready', revision: '', error: '', optimistic: false, locked: false,
      style: '手绘水彩', ratio: '3:4', quality: '2K', purpose: '朋友圈活动宣传', files: [],
      supportsRatio: () => true, supportsQuality: () => true })
    const html = await renderToString(createSSRApp({ render: () => h('main', [h(Control, { studio }), h(Gallery, { studio })]) }).use(router))
    assert.match(html, /灵感绘图/)
    assert.match(html, /生成图片/)
    assert.match(html, /森林里.*小狐狸/)
    for (const style of ['写实摄影', '电影质感', '手绘水彩', '国风意境', '极简留白', '胶片纪实', '平面设计', '暗调光影']) assert.ok(html.includes(style))
    assert.doesNotMatch(html, /poster-purpose-picker|朋友圈活动宣传|海报风格|营销海报/)
    assert.match(html, /drawing-styles\/watercolor\.webp/)
  } finally { await vite.close() }
})
