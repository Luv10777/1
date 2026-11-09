import test from 'node:test'
import assert from 'node:assert/strict'
import { capabilityShares, change, periodStats, windowRows } from './creditStats.js'

const day = (date, consumed, extra = {}) => ({ date, consumed, purchased: 0, granted: 0, refunded: 0, consumedByCapability: {}, ...extra })

test('a period is compared with the same length of time right before it', () => {
  const daily = [day('11-03', 5), day('11-04', 10, { consumedByCapability: { IMAGE: 10 } }), day('11-05', 20, { purchased: 100, consumedByCapability: { IMAGE: 15, VIDEO: 5 } })]
  const today = periodStats(daily, 1)
  assert.equal(today.current.consumed, 20)
  assert.equal(today.previous.consumed, 10, 'today is compared with yesterday')
  assert.deepEqual(today.current.byCapability, { IMAGE: 15, VIDEO: 5 })
  assert.equal(today.current.purchased, 100)

  const twoDays = periodStats(daily, 2)
  assert.equal(twoDays.current.consumed, 30)
  assert.equal(twoDays.previous, null, 'not enough days to cover the previous period')
})

test('changes read as green increases, red decreases and plain text otherwise', () => {
  assert.deepEqual(change(120, 100), { tone: 'up', text: '+20.0%' })
  assert.deepEqual(change(75, 100), { tone: 'down', text: '-25.0%' })
  assert.deepEqual(change(100, 100), { tone: 'flat', text: '持平' })
  assert.deepEqual(change(5, 0), { tone: 'up', text: '新增' })
  assert.deepEqual(change(0, 0), { tone: 'flat', text: '持平' })
  assert.deepEqual(change(5, null), { tone: 'none', text: '—' })
})

test('feature shares are sorted by use and keep features that dropped to zero', () => {
  const shares = capabilityShares({ byCapability: { IMAGE: 15, VIDEO: 5 } }, { byCapability: { IMAGE: 10, COPY: 4 } })
  assert.deepEqual(shares.map(item => [item.key, item.share, item.previous]), [['IMAGE', 75, 10], ['VIDEO', 25, 0], ['COPY', 0, 4]])
  assert.deepEqual(capabilityShares({ byCapability: {} }, null), [])
})

test('today shows the last two weeks for context, longer ranges show the whole range', () => {
  const daily = Array.from({ length: 30 }, (_, index) => day(String(index), index))
  assert.equal(windowRows(daily, 1).length, 14)
  assert.equal(windowRows(daily, 7).length, 7)
  assert.equal(windowRows(daily, 90).length, 30)
})
