import test from 'node:test'
import assert from 'node:assert/strict'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { videoCompletionNotice } from '../services/videoConversations.js'

test('completed video preview uses its own aspect ratio without parameter notices', async t => {
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-video-qa-tests',
    plugins: [vue()],
    optimizeDeps: { noDiscovery: true, entries: [] },
    server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  try {
    const { default: Preview } = await vite.ssrLoadModule('/src/components/VideoPreviewPanel.vue')
    const render = props => renderToString(createSSRApp(Preview, { modelLabel: 'Seedance 2.5', ...props }))
    await t.test('a completed video has its own player and no device frame or parameter warnings', async () => {
      const html = await render({ outputUrl: '/test/output.mp4', resolution: '1080p', duration: 5, format: '16:9',
        actualWidth: 720, actualHeight: 1280 })
      assert.match(html, /<video[^>]+src="\/test\/output.mp4"/)
      assert.match(html, /class="video-result-stage"/)
      assert.match(html, /--video-aspect-ratio:0.5625/)
      assert.doesNotMatch(html, /device-player|device-island|device-mac-ui/)
      assert.doesNotMatch(html, /实际成片|请求：|成片参数需核对|重新生成可能再次计费/)
    })
    await t.test('an old response remains playable while dimensions are loaded from the file', async () => {
      const old = await render({ outputUrl: '/test/old.mp4' })
      assert.match(old, /<video[^>]+src="\/test\/old.mp4"/)
      assert.match(old, /preload="metadata"/)
      assert.doesNotMatch(old, /尺寸未记录|时长未记录/)
      assert.equal(videoCompletionNotice({ status: 'SUCCEEDED' }), '视频已完成并保存到作品库。')
    })
    await t.test('the progress and failure views remain available', async () => {
      const html = await render({ isGenerating: true })
      assert.doesNotMatch(html, /aria-label="成片参数"/)
      assert.doesNotMatch(html, /成片参数需核对/)
      assert.match(html, /正在生成视频/)
      const failed = await render({ error: '生成失败，请稍后重试。' })
      assert.match(failed, /视频生成失败/)
      assert.match(failed, /生成失败，请稍后重试。/)
    })
  } finally { await vite.close() }
})
