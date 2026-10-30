import test from 'node:test'
import assert from 'node:assert/strict'
import { inviteLink, parseRatePercent, ratePercent } from './referral.js'

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
