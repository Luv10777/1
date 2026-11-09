import test from 'node:test'
import assert from 'node:assert/strict'
import { isOverdue, reviewInputError, settleRateBp, sortReviewItems, toReviewItems, waitedLabel } from './adminReview.js'

const now = Date.parse('2026-11-09T07:00:00Z')

test('each pending source becomes one review item shape with its own waiting start', () => {
  const [info] = toReviewItems.information([{ id: 3, tenantId: 9, title: '周末烘焙课', merchantName: '禾木', category: 'activity', region: '西湖区', createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-09T02:00:00Z' }])
  assert.equal(info.key, 'information-9-3')
  assert.equal(info.detail, '门店活动 · 西湖区')
  assert.equal(info.since, Date.parse('2026-11-09T02:00:00Z'), 'a resubmitted item waits from its last update')

  const [offer] = toReviewItems.offer([{ tenantId: 4, supplierName: '禾木咖啡', offer: { id: 7, productName: '拿铁', faceValueMinor: 1000, totalQty: 200, saleUnit: '杯', createdAt: '2026-11-08T00:00:00Z' } }])
  assert.equal(offer.key, 'offer-4-7')
  assert.equal(offer.detail, '面值 ¥10.00 · 200 杯')

  const [dispute] = toReviewItems.dispute([{ ref: 'abc', holderName: '邻里鲜生', productName: '拿铁', storeName: '古翠路店', faceValueMinor: 1000, disputedAt: null }])
  assert.equal(dispute.since, null)

  const [account] = toReviewItems.account([{ tenantId: 4, supplierName: '禾木咖啡', account: { accountName: '杭州禾木', accountType: 'CORPORATE', updatedAt: '2026-11-09T06:00:00Z' } }])
  assert.equal(account.key, 'account-4')
  assert.equal(account.detail, '对公账户')
})

test('the longest-waiting item comes first and items over a day are overdue', () => {
  const items = [{ key: 'b', since: now - 2 * 3_600_000 }, { key: 'none', since: null }, { key: 'a', since: now - 30 * 3_600_000 }]
  assert.deepEqual(sortReviewItems(items).map(item => item.key), ['a', 'b', 'none'])
  assert.equal(isOverdue(items[2], now), true)
  assert.equal(isOverdue(items[0], now), false)
  assert.equal(isOverdue(items[1], now), false)
  assert.equal(waitedLabel(now - 26 * 3_600_000, now), '1 天 2 小时')
  assert.equal(waitedLabel(now - 5 * 60_000, now), '5 分钟')
  assert.equal(waitedLabel(null, now), '—')
})

test('notes and settlement discounts are checked before anything is sent', () => {
  assert.match(reviewInputError('information', true, { note: '可以' }), /至少 5 字/)
  assert.equal(reviewInputError('information', true, { note: '内容与门店一致' }), '')
  assert.equal(reviewInputError('account', true, {}), '', 'approving an account needs no note')
  assert.match(reviewInputError('account', false, { note: ' ' }), /写明原因/)
  assert.match(reviewInputError('offer', true, { rate: '' }), /结算折扣/)
  assert.match(reviewInputError('offer', true, { rate: '12' }), /结算折扣/)
  assert.equal(reviewInputError('offer', true, { rate: '8.5' }), '')
  assert.match(reviewInputError('dispute', false, { note: 'x'.repeat(301) }), /最多 300 字/)
  assert.equal(settleRateBp('5'), 5000)
  assert.equal(settleRateBp('0'), null)
})
