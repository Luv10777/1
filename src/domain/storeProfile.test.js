import test from 'node:test'
import assert from 'node:assert/strict'
import { addAmenity, blankSpecialHour, describeSpecialHour, profileForm, profilePayload } from './storeProfile.js'

test('an arrangement reads the way the live assistant is told it', () => {
  assert.equal(describeSpecialHour({ scope: 'WEEKLY', weekday: 1, closed: true, note: ' 固定店休 ' }), '每周一休息（固定店休）')
  assert.equal(describeSpecialHour({ scope: 'DATE', date: '2026-10-08', closed: false, opensAt: '10:00', closesAt: '18:00' }), '2026年10月8日营业 10:00 至 18:00')
  assert.equal(describeSpecialHour({ scope: 'DATE', date: '', closed: true }), '未选日期休息')
})

test('amenities are short, unique and capped', () => {
  assert.deepEqual(addAmenity(['免费停车'], ' 包间 '), ['免费停车', '包间'])
  assert.deepEqual(addAmenity(['免费停车'], '免费停车'), ['免费停车'])
  assert.deepEqual(addAmenity([], '  '), [])
  assert.equal(addAmenity([], '一二三四五六七八九十一二三四五六七八九十多出来的')[0].length, 20)
  const full = Array.from({ length: 20 }, (_, index) => `服务${index}`)
  assert.equal(addAmenity(full, '第二十一项'), full)
})

test('the whole profile is sent, so an emptied field is cleared, and only the fields that apply to each arrangement', () => {
  const payload = profilePayload({
    transportGuide: ' 地铁 2 号线 B 口 ',
    amenities: ['免费停车', ' 包间 ', '免费停车', ''],
    specialHours: [
      { ...blankSpecialHour(), weekday: 1, note: ' 固定店休 ' },
      { scope: 'DATE', weekday: 3, date: '2026-10-08', closed: false, opensAt: '10:00', closesAt: '18:00', note: '' },
    ],
  })
  assert.deepEqual(payload, {
    transportGuide: '地铁 2 号线 B 口',
    amenities: ['免费停车', '包间'],
    specialHours: [
      { scope: 'WEEKLY', closed: true, weekday: 1, note: '固定店休' },
      { scope: 'DATE', closed: false, date: '2026-10-08', opensAt: '10:00', closesAt: '18:00' },
    ],
  })
  assert.deepEqual(profilePayload({}), { transportGuide: '', amenities: [], specialHours: [] })
})

test('an arrangement that does not add up is caught before it is sent', () => {
  const one = rule => () => profilePayload({ specialHours: [blankSpecialHour(), rule] })
  assert.throws(one({ scope: 'DATE', date: '', closed: true }), /第 2 条：请选择日期/)
  assert.throws(one({ scope: 'WEEKLY', weekday: null, closed: true }), /第 2 条：请选择星期几/)
  assert.throws(one({ scope: 'WEEKLY', weekday: 2, closed: false, opensAt: '10:00', closesAt: '' }), /营业时间/)
})

test('a saved store fills the form, and a store that never had a profile gives an empty one', () => {
  assert.deepEqual(profileForm({ name: '老店' }), { transportGuide: '', amenities: [], specialHours: [] })
  const form = profileForm({ transportGuide: 'B 口', amenities: ['包间'], specialHours: [{ scope: 'DATE', date: '2026-10-08', closed: true }] })
  assert.equal(form.transportGuide, 'B 口')
  assert.deepEqual(form.amenities, ['包间'])
  assert.deepEqual(form.specialHours[0], { scope: 'DATE', weekday: 1, date: '2026-10-08', closed: true, opensAt: '10:00', closesAt: '22:00', note: '' })
})
