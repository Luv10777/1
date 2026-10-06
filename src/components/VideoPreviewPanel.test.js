import test from 'node:test'
import assert from 'node:assert/strict'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { videoCompletionNotice } from '../services/videoConversations.js'

test('video preview displays actual metadata, requested settings and persistent QA warnings', async t => {
  const vite = await createServer({ configFile: false, cacheDir: 'node_modules/.vite-video-qa-tests',
    plugins: [vue()],
    optimizeDeps: { noDiscovery: true, entries: [] },
    server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  try {
    const { default: Preview } = await vite.ssrLoadModule('/src/components/VideoPreviewPanel.vue')
    const render = props => renderToString(createSSRApp(Preview, { modelLabel: 'Seedance 2.5', ...props }))
    await t.test('a mismatched result remains playable and shows actual values and additional-cost guidance', async () => {
      const html = await render({ outputUrl: '/test/output.mp4', resolution: '1080p', duration: 5, format: '16:9',
        actualWidth: 720, actualHeight: 1280, actualDurationMs: 5201,
        qaWarnings: [{ code: 'VIDEO_ORIENTATION_MISMATCH', message: '画面方向与请求不一致' }] })
      assert.match(html, /<video[^>]+src="\/test\/output.mp4"/)
      assert.match(html, /720 × 1280 px · 5.201 秒/)
      assert.match(html, /请求：1080p · 5 秒 · 16:9/)
      assert.match(html, /成片参数需核对/)
      assert.match(html, /画面方向与请求不一致/)
      assert.match(html, /重新生成可能再次计费/)
    })
    await t.test('a matching result still shows actual values while an old response stays compatible', async () => {
      const matching = await render({ outputUrl: '/test/output.mp4', actualWidth: 1088, actualHeight: 1920, actualDurationMs: 5200 })
      assert.match(matching, /1088 × 1920 px · 5.2 秒/)
      assert.doesNotMatch(matching, /成片参数需核对/)
      const old = await render({ outputUrl: '/test/old.mp4' })
      assert.match(old, /尺寸未记录 · 时长未记录/)
      assert.equal(videoCompletionNotice({ status: 'SUCCEEDED' }), '视频已完成并保存到作品库。')
    })
    await t.test('a generating result does not present unknown metadata as a mismatch', async () => {
      const html = await render({ isGenerating: true })
      assert.doesNotMatch(html, /aria-label="成片参数"/)
      assert.doesNotMatch(html, /成片参数需核对/)
    })
  } finally { await vite.close() }
})
