import test from 'node:test'
import assert from 'node:assert/strict'
import { aiLevel, copyApi, copyFormFromBatch, copySegments, copyText, groupByDay, industryPrompt, missingFacts, topicLink, validateCopyImage } from './copywriting.js'

const store = { 店名: '梧桐小馆', 地址: '梧桐路 8 号' }

test('store tokens show the store value and missing facts stay marked', () => {
  assert.deepEqual(copySegments('开店第【几】年，〔店名〕在〔地址〕，〔电话〕', store), [
    { kind: 'plain', text: '开店第' },
    { kind: 'missing', text: '【几】' },
    { kind: 'plain', text: '年，' },
    { kind: 'store', text: '梧桐小馆', label: '店名' },
    { kind: 'plain', text: '在' },
    { kind: 'store', text: '梧桐路 8 号', label: '地址' },
    { kind: 'plain', text: '，' },
    { kind: 'missing', text: '【电话】' },
  ])
})

test('copied text fills the store, keeps paragraphs and puts the topics last', () => {
  const version = { title: '雨天小店｜一人食', body: '今天下雨\n\n店名：〔店名〕', tags: ['#小店日常', '#下雨天'] }
  assert.equal(copyText(version, store), '雨天小店｜一人食\n\n今天下雨\n\n店名：梧桐小馆\n\n#小店日常 #下雨天')
  assert.equal(copyText({ title: '', body: '下雨天', tags: [] }, store), '下雨天')
})

test('missing facts are listed before copying', () => {
  assert.deepEqual(missingFacts({ title: '开店第【几】年', body: '营业到〔营业时间〕' }, store), ['【几】', '【营业时间】'])
  assert.deepEqual(missingFacts({ title: '', body: '〔店名〕' }, store), [])
})

test('ai level is a three step label', () => {
  assert.deepEqual(aiLevel(1), { value: 1, label: '低', high: false })
  assert.deepEqual(aiLevel(3), { value: 3, label: '中', high: false })
  assert.deepEqual(aiLevel(9), { value: 5, label: '偏高', high: true })
})

test('history groups by today, yesterday and date', () => {
  const now = new Date(2026, 9, 28, 15)
  const groups = groupByDay([
    { id: 1, createdAt: new Date(2026, 9, 28, 9).toISOString() },
    { id: 2, createdAt: new Date(2026, 9, 27, 20).toISOString() },
    { id: 3, createdAt: new Date(2026, 9, 25, 9).toISOString() },
    { id: 4, createdAt: new Date(2026, 9, 25, 8).toISOString() },
  ], now)
  assert.deepEqual(groups.map(group => [group.label, group.items.map(item => item.id)]), [['今天', [1]], ['昨天', [2]], ['10月25日', [3, 4]]])
})

test('opening a record restores its settings and pictures', () => {
  const form = copyFormFromBatch({ platform: 'DOUYIN', style: '幽默玩梗', storeLevel: 'VISIT', voiceOn: false, note: null,
    images: [{ kind: 'WORK', id: 3, name: '海报', url: 'https://cdn/3' }], products: [{ id: 4, name: '牛肉面' }] })
  assert.equal(form.note, '')
  assert.deepEqual(form.pictures, [{ key: 'WORK-3', kind: 'WORK', id: 3, name: '海报', url: 'https://cdn/3', file: null }])
  assert.deepEqual(form.products, [{ id: 4, name: '牛肉面' }])
})

test('history leaves the platform out when listing everything', async () => {
  const requests = []
  globalThis.fetch = async url => { requests.push(String(url)); return { ok: true, status: 200, json: async () => ({ code: 200, data: { items: [] } }) } }
  await copyApi.history(7)
  await copyApi.history(7, { platform: 'DOUYIN' })
  assert.doesNotMatch(requests[0], /platform/)
  assert.match(requests[1], /platform=DOUYIN/)
})

test('what is good to post today opens the workspace with its platform, style and the solar term', () => {
  const link = topicLink({ title: '晒一杯暖茶', platform: 'MOMENTS', style: '文艺治愈' }, '霜降')
  const query = new URLSearchParams(link.split('?')[1])
  assert.equal(link.split('?')[0], '/copy/create')
  assert.equal(query.get('platform'), 'MOMENTS')
  assert.equal(query.get('style'), '文艺治愈')
  assert.equal(query.get('note'), '今日宜：晒一杯暖茶（霜降）')
  // 10-29 以前生成的选题还带一句思路，照样带进去。
  assert.equal(new URLSearchParams(topicLink({ title: '老板的备餐日常', platform: 'DOUYIN', style: '真诚日常', idea: '拍后厨' }).split('?')[1]).get('note'),
    '今日宜：老板的备餐日常。拍后厨')
})

test('owners are sent to complete the brand, staff only see a hint', () => {
  assert.equal(industryPrompt({ industryStatus: 'KNOWN' }, true), null)
  assert.deepEqual(industryPrompt({ industryStatus: 'MISSING', brandId: 3 }, true),
    { text: '补充行业，推荐更贴合你的店', label: '去品牌库完善', link: '/brands?edit=3' })
  assert.equal(industryPrompt({ industryStatus: 'NO_BRAND' }, true).link, '/brands')
  assert.equal(industryPrompt({ industryStatus: 'MISSING', brandId: 3 }, false).link, null)
})

test('only small enough jpg, png and webp images are accepted', () => {
  assert.equal(validateCopyImage({ type: 'image/webp', size: 1024 }), '')
  assert.match(validateCopyImage({ type: 'image/gif', size: 1024 }), /JPG/)
  assert.match(validateCopyImage({ type: 'image/png', size: 21 * 1024 * 1024 }), /20 MB/)
})
