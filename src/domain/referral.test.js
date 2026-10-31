import test from 'node:test'
import assert from 'node:assert/strict'
import { formatCardNumber, inviteLink, parseRatePercent, parseYuan, ratePercent, withdrawable } from './referral.js'

test('rates read as percentages and the admin input stays within 0–50% with two decimals', () => {
  assert.equal(ratePercent(1000), '10%')
  assert.equal(ratePercent(1050), '10.5%')
  assert.equal(ratePercent(1005), '10.05%')
  assert.equal(ratePercent(0), '0%')
  assert.deepEqual(parseRatePercent('12.5'), { bp: 1250 })
  assert.deepEqual(parseRatePercent(' 50 '), { bp: 5000 })
  for (const bad of ['', '50.01', '60', '-1', '10.123', 'abc', '1e1']) assert.ok(parseRatePercent(bad).error, bad)
})

test('the invite link opens registration with the code', () => {
  assert.equal(inviteLink('AB3K9XYZ', 'https://yifangzhi.com'), 'https://yifangzhi.com/login?mode=register&invite=AB3K9XYZ')
})

test('a withdrawal takes everything available minus what is owed, never below zero', () => {
  assert.equal(withdrawable({ availableMinor: 17_000, owedMinor: 5000 }), 12_000)
  assert.equal(withdrawable({ availableMinor: 3000, owedMinor: 5000 }), 0)
})

test('finance enters tax in yuan and it becomes exact cents', () => {
  assert.deepEqual(parseYuan('0'), { minor: 0 })
  assert.deepEqual(parseYuan(' 12.5 '), { minor: 1250 })
  assert.deepEqual(parseYuan('0.07'), { minor: 7 })
  assert.deepEqual(parseYuan('1999.99'), { minor: 199_999 })
  for (const bad of ['', '-1', '1.234', 'abc', '1e2', '12.']) assert.ok(parseYuan(bad).error, bad)
})

test('card numbers group by four digits while typing', () => {
  assert.equal(formatCardNumber('6222000000001234'), '6222 0000 0000 1234')
  assert.equal(formatCardNumber('6222-0000 12'), '6222 0000 12')
  assert.equal(formatCardNumber('1'.repeat(25)).replace(/ /g, '').length, 19)
})
