import test from 'node:test'
import assert from 'node:assert/strict'
import { upcomingMoment } from './posterStyles.js'

test('the seasonal card counts down to the next solar term or fixed festival', () => {
  assert.deepEqual(upcomingMoment(new Date(2026, 9, 21, 15, 30)), { name: '霜降', month: 10, day: 23, days: 2, next: '立冬' })
  assert.equal(upcomingMoment(new Date(2026, 9, 23)).days, 0, 'the day itself still counts')
  assert.deepEqual(upcomingMoment(new Date(2026, 10, 8)), { name: '双十一', month: 11, day: 11, days: 3, next: '小雪' })
  assert.deepEqual(upcomingMoment(new Date(2026, 11, 26)), { name: '元旦', month: 1, day: 1, days: 6, next: '小寒' })
})

test('solar term dates match the published calendar, including the 2026 correction', () => {
  const on = (y, m, d) => { const moment = upcomingMoment(new Date(y, m - 1, d)); return [moment.name, moment.month, moment.day] }
  assert.deepEqual(on(2026, 2, 15), ['雨水', 2, 18])
  assert.deepEqual(on(2026, 3, 9), ['春分', 3, 20])
  assert.deepEqual(on(2026, 12, 8), ['冬至', 12, 22])
  assert.deepEqual(on(2027, 1, 21), ['立春', 2, 4])
})
